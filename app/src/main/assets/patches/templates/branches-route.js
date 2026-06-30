// PATCHED BY STANDROID v3: Replaced /branches with isomorphic-git implementation
router.post('/branches', async (request, response) => {
    try {
        if (typeof request.body.extensionName !== 'string') {
            return response.status(400).send('Bad Request: A valid extensionName is required in the request body.');
        }

        const { extensionName, global } = request.body;
        const extensionNameSanitized = sanitize(extensionName);
        if (!extensionNameSanitized) {
            return response.status(400).send('Bad Request: A valid extensionName is required in the request body.');
        }

        if (global && !request.user.profile.admin) {
            console.error(`User ${request.user.profile.handle} does not have permission to list branches of global extensions.`);
            return response.status(403).send('Forbidden: No permission to list branches of global extensions.');
        }

        const basePath = global ? PUBLIC_DIRECTORIES.globalExtensions : request.user.directories.extensions;
        const extensionPath = path.join(basePath, extensionNameSanitized);

        if (!fs.existsSync(extensionPath)) {
            return response.status(404).send(`Directory does not exist at ${extensionPath}`);
        }

        // Fetch all branches from remote
        await git.fetch({
            fs,
            http,
            dir: extensionPath,
            remote: 'origin',
            prune: true,
            depth: 999999,
        });

        // Get local branches
        const localBranches = await git.listBranches({ fs, dir: extensionPath });
        
        // Get remote branches
        const remoteBranches = await git.listBranches({ fs, dir: extensionPath, remote: 'origin' });

        // Get current branch
        const currentBranchName = await git.currentBranch({ fs, dir: extensionPath });

        const result = [];
        
        // Add local branches
        for (const branchName of localBranches) {
            try {
                const commit = await git.resolveRef({ fs, dir: extensionPath, ref: branchName });
                result.push({
                    current: branchName === currentBranchName,
                    commit: commit.slice(0, 7),
                    name: branchName,
                    label: branchName,
                });
            } catch (e) {
                // Skip if ref resolution fails
            }
        }

        // Add remote branches
        for (const branchName of remoteBranches) {
            try {
                const commit = await git.resolveRef({
                    fs,
                    dir: extensionPath,
                    ref: `refs/remotes/origin/${branchName}`,
                });
                const fullName = `origin/${branchName}`;
                result.push({
                    current: false,
                    commit: commit.slice(0, 7),
                    name: fullName,
                    label: fullName,
                });
            } catch (e) {
                // Skip if ref resolution fails
            }
        }

        return response.send(result);
    } catch (error) {
        console.error('Getting branches failed', error);
        return response.status(500).send('Internal Server Error. Check the server logs for more details.');
    }
});