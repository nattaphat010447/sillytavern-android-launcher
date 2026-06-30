// PATCHED BY STANDROID v3: Replaced /update with isomorphic-git implementation (fetch + force-reset)
router.post('/update', async (request, response) => {
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
            console.error(`User ${request.user.profile.handle} does not have permission to update global extensions.`);
            return response.status(403).send('Forbidden: No permission to update global extensions.');
        }

        const basePath = global ? PUBLIC_DIRECTORIES.globalExtensions : request.user.directories.extensions;
        const extensionPath = path.join(basePath, extensionNameSanitized);

        if (!fs.existsSync(extensionPath)) {
            return response.status(404).send(`Directory does not exist at ${extensionPath}`);
        }

        // Check if it's a git repo
        const gitDir = path.join(extensionPath, '.git');
        if (!fs.existsSync(gitDir)) {
            throw new Error(`Directory is not a Git repository at ${extensionPath}`);
        }

        const { isUpToDate, remoteUrl } = await checkIfRepoIsUpToDate(extensionPath);
        
        // Get current branch
        const currentBranch = await git.currentBranch({ fs, dir: extensionPath });
        if (!currentBranch) {
            throw new Error('Could not determine current branch');
        }

        if (!isUpToDate) {
            // Use fetch + force-reset instead of pull to handle "unrelated histories"
            // and other diverged-repo scenarios. This is equivalent to:
            //   git fetch origin && git reset --hard origin/<branch>
            await git.fetch({
                fs,
                http,
                dir: extensionPath,
                remote: 'origin',
                ref: currentBranch,
                singleBranch: true,
            });

            // Resolve the remote tracking ref to get the target commit
            const remoteCommit = await git.resolveRef({
                fs,
                dir: extensionPath,
                ref: `refs/remotes/origin/${currentBranch}`,
            });

            // Force-move the local branch pointer to match remote (equivalent to reset --hard)
            await git.writeRef({
                fs,
                dir: extensionPath,
                ref: `refs/heads/${currentBranch}`,
                value: remoteCommit,
                force: true,
            });

            // Update working directory to match (force checkout)
            await git.checkout({
                fs,
                dir: extensionPath,
                ref: currentBranch,
                force: true,
            });

            console.info(`Extension has been updated at ${extensionPath}`);
        } else {
            console.info(`Extension is up to date at ${extensionPath}`);
        }

        // Get commit hash
        const fullCommitHash = await git.resolveRef({ fs, dir: extensionPath, ref: 'HEAD' });
        const shortCommitHash = fullCommitHash.slice(0, 7);

        return response.send({ shortCommitHash, extensionPath, isUpToDate, remoteUrl });
    } catch (error) {
        console.error('Updating extension failed', error);
        return response.status(500).send('Internal Server Error. Check the server logs for more details.');
    }
});
