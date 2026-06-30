// PATCHED BY STANDROID v3: Replaced with isomorphic-git implementation
async function checkIfRepoIsUpToDate(extensionPath) {
    try {
        // Fetch from origin
        await git.fetch({
            fs,
            http,
            dir: extensionPath,
            remote: 'origin',
            prune: true,
        });

        // Get current branch
        const currentBranch = await git.currentBranch({ fs, dir: extensionPath });
        if (!currentBranch) {
            return { isUpToDate: true, remoteUrl: '' };
        }

        // Get current commit
        const currentCommitHash = await git.resolveRef({ fs, dir: extensionPath, ref: 'HEAD' });

        // Get remote commit
        let remoteCommitHash;
        try {
            remoteCommitHash = await git.resolveRef({
                fs,
                dir: extensionPath,
                ref: `refs/remotes/origin/${currentBranch}`,
            });
        } catch (e) {
            // Remote branch doesn't exist
            return { isUpToDate: true, remoteUrl: '' };
        }

        // Get commits between current and remote
        const commits = await git.log({
            fs,
            dir: extensionPath,
            ref: currentBranch,
        });

        const commitsBehind = commits.filter(c => {
            const commitOid = c.oid;
            return commitOid !== currentCommitHash && commitOid === remoteCommitHash;
        });

        // Get remote URL
        const config = await git.getConfig({ fs, dir: extensionPath, path: 'remote.origin.url' });
        const remoteUrl = config || '';

        return {
            isUpToDate: currentCommitHash === remoteCommitHash,
            remoteUrl,
        };
    } catch (error) {
        console.error('checkIfRepoIsUpToDate error:', error);
        return { isUpToDate: true, remoteUrl: '' };
    }
}
