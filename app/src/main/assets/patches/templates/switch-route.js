// PATCHED BY STANDROID v3: Replaced /switch with isomorphic-git implementation
router.post('/switch', async (request, response) => {
    try {
        if (typeof request.body.extensionName !== 'string') {
            return response.status(400).send('Bad Request: A valid extensionName is required in the request body.');
        }

        const { extensionName, branch, global } = request.body;
        const extensionNameSanitized = sanitize(extensionName);
        if (!extensionNameSanitized || !branch) {
            return response.status(400).send('Bad Request: A valid extensionName and branch are required in the request body.');
        }

        if (global && !request.user.profile.admin) {
            console.error(`User ${request.user.profile.handle} does not have permission to switch branches of global extensions.`);
            return response.status(403).send('Forbidden: No permission to switch branches of global extensions.');
        }

        const basePath = global ? PUBLIC_DIRECTORIES.globalExtensions : request.user.directories.extensions;
        const extensionPath = path.join(basePath, extensionNameSanitized);

        if (!fs.existsSync(extensionPath)) {
            return response.status(404).send(`Directory does not exist at ${extensionPath}`);
        }

        const currentBranch = await git.currentBranch({ fs, dir: extensionPath });
        let targetBranch = branch;

        // Handle origin/ prefix
        if (String(branch).startsWith('origin/')) {
            targetBranch = branch.replace('origin/', '');
            
            // Check if local branch exists
            const localBranches = await git.listBranches({ fs, dir: extensionPath });
            
            if (!localBranches.includes(targetBranch)) {
                console.info(`Branch ${targetBranch} does not exist locally, creating it from ${branch}`);
                
                // Create local branch tracking remote
                const remoteSha = await git.resolveRef({
                    fs,
                    dir: extensionPath,
                    ref: `refs/remotes/${branch}`,
                });
                
                await git.branch({
                    fs,
                    dir: extensionPath,
                    ref: targetBranch,
                    checkout: true,
                    object: remoteSha,
                });
                
                return response.sendStatus(204);
            }
        }

        // Check if already on target branch
        if (currentBranch === targetBranch) {
            console.info(`Branch ${targetBranch} is already checked out`);
            return response.sendStatus(204);
        }

        // Checkout the branch
        await git.checkout({
            fs,
            dir: extensionPath,
            ref: targetBranch,
        });
        
        console.info(`Checked out branch ${targetBranch} at ${extensionPath}`);

        return response.sendStatus(204);
    } catch (error) {
        console.error('Switching branches failed', error);
        return response.status(500).send('Internal Server Error. Check the server logs for more details.');
    }
});