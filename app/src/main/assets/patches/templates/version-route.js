// PATCHED BY STANDROID v3: Replaced /version with isomorphic-git implementation
router.post('/version', async (request, response) => {
    try {
        if (typeof request.body.extensionName !== 'string') {
            return response.status(400).send('Bad Request: A valid extensionName is required in the request body.');
        }

        const { extensionName, global } = request.body;
        const extensionNameSanitized = sanitize(extensionName);
        if (!extensionNameSanitized) {
            return response.status(400).send('Bad Request: A valid extensionName is required in the request body.');
        }

        const basePath = global ? PUBLIC_DIRECTORIES.globalExtensions : request.user.directories.extensions;
        const extensionPath = path.join(basePath, extensionNameSanitized);

        if (!fs.existsSync(extensionPath)) {
            return response.status(404).send(`Directory does not exist at ${extensionPath}`);
        }

        // Check if it's a git repo
        const gitDir = path.join(extensionPath, '.git');
        if (!fs.existsSync(gitDir)) {
            return response.send({ currentBranchName: '', currentCommitHash: '', isUpToDate: true, remoteUrl: '' });
        }

        let currentCommitHash;
        try {
            currentCommitHash = await git.resolveRef({ fs, dir: extensionPath, ref: 'HEAD' });
        } catch (error) {
            return response.send({ currentBranchName: '', currentCommitHash: '', isUpToDate: true, remoteUrl: '' });
        }

        const currentBranchName = await git.currentBranch({ fs, dir: extensionPath }) || '';
        
        // Fetch to check for updates
        try {
            await git.fetch({
                fs,
                http,
                dir: extensionPath,
                remote: 'origin',
            });
        } catch (e) {
            // Ignore fetch errors for version check
        }
        
        console.debug(extensionNameSanitized, currentBranchName, currentCommitHash);
        const { isUpToDate, remoteUrl } = await checkIfRepoIsUpToDate(extensionPath);

        return response.send({ currentBranchName, currentCommitHash, isUpToDate, remoteUrl });
    } catch (error) {
        console.error('Getting extension version failed', error);
        return response.status(500).send('Internal Server Error. Check the server logs for more details.');
    }
});