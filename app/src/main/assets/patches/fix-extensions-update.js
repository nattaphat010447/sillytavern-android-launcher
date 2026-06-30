#!/usr/bin/env node
/**
 * STANDROID Patch Script
 * 
 * Patches SillyTavern's src/endpoints/extensions.js to use isomorphic-git
 * instead of simpleGit for extension updates, making it work on Android
 * without requiring a native git binary.
 * 
 * This script is idempotent and can be safely run multiple times.
 */

const fs = require('fs');
const path = require('path');

const PATCH_MARKER = '// PATCHED BY STANDROID';
const PATCH_VERSION = 'v3'; // Updated for unrelated-histories fix (fetch + force-reset)
const EXTENSIONS_FILE = path.join(__dirname, '..', '..', 'src', 'endpoints', 'extensions.js');

console.log('[STANDROID] Extension patch script starting...');
console.log(`[STANDROID] Target file: ${EXTENSIONS_FILE}`);

// Check if file exists
if (!fs.existsSync(EXTENSIONS_FILE)) {
    console.error(`[STANDROID] ERROR: File not found: ${EXTENSIONS_FILE}`);
    process.exit(1);
}

// Read current content
let content = fs.readFileSync(EXTENSIONS_FILE, 'utf8');

// Check if already patched
if (content.includes(PATCH_MARKER) && content.includes(PATCH_VERSION)) {
    console.log('[STANDROID] File is already patched with correct version. Skipping.');
    process.exit(0);
}
if (content.includes(PATCH_MARKER)) {
    console.log('[STANDROID] Found old patch version. Re-patching...');
    // Remove old patch markers to force re-patch
    content = content.replace(/\/\/ PATCHED BY STANDROID[^\n]*/g, '');
}

console.log('[STANDROID] Applying patch...');

// ============================================================================
// Patch 1: Add isomorphic-git import at the top
// ============================================================================
const importSection = content.match(/import.*from.*;\s*/gs);
if (!importSection) {
    console.error('[STANDROID] ERROR: Could not find import section');
    process.exit(1);
}

const lastImport = importSection[importSection.length - 1];
const isomorphicImports = `import git from 'isomorphic-git';
import http from 'isomorphic-git/http/node';
${PATCH_MARKER}: Added isomorphic-git imports\n`;

content = content.replace(lastImport, lastImport + isomorphicImports);

// ============================================================================
// Patch 2: Replace checkIfRepoIsUpToDate with isomorphic-git version
// ============================================================================
const checkIfRepoPattern = /async function checkIfRepoIsUpToDate\(extensionPath\) \{[\s\S]*?\n\}/;
const checkIfRepoMatch = content.match(checkIfRepoPattern);

if (!checkIfRepoMatch) {
    console.error('[STANDROID] ERROR: Could not find checkIfRepoIsUpToDate function');
    process.exit(1);
}

const newCheckIfRepo = `${PATCH_MARKER}: Replaced with isomorphic-git implementation
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
                ref: \`refs/remotes/origin/\${currentBranch}\`,
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
}`;

content = content.replace(checkIfRepoPattern, newCheckIfRepo);

// ============================================================================
// Patch 3: Replace /update endpoint with isomorphic-git version
// ============================================================================
const updateRoutePattern = /router\.post\('\/update',[\s\S]*?^\}\);/m;
const updateRouteMatch = content.match(updateRoutePattern);

if (!updateRouteMatch) {
    console.error('[STANDROID] ERROR: Could not find /update route');
    process.exit(1);
}

const newUpdateRoute = `${PATCH_MARKER}: Replaced /update with isomorphic-git implementation
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
            console.error(\`User \${request.user.profile.handle} does not have permission to update global extensions.\`);
            return response.status(403).send('Forbidden: No permission to update global extensions.');
        }

        const basePath = global ? PUBLIC_DIRECTORIES.globalExtensions : request.user.directories.extensions;
        const extensionPath = path.join(basePath, extensionNameSanitized);

        if (!fs.existsSync(extensionPath)) {
            return response.status(404).send(\`Directory does not exist at \${extensionPath}\`);
        }

        // Check if it's a git repo
        const gitDir = path.join(extensionPath, '.git');
        if (!fs.existsSync(gitDir)) {
            throw new Error(\`Directory is not a Git repository at \${extensionPath}\`);
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
                ref: \`refs/remotes/origin/\${currentBranch}\`,
            });

            // Force-move the local branch pointer to match remote (equivalent to reset --hard)
            await git.writeRef({
                fs,
                dir: extensionPath,
                ref: \`refs/heads/\${currentBranch}\`,
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

            console.info(\`Extension has been updated at \${extensionPath}\`);
        } else {
            console.info(\`Extension is up to date at \${extensionPath}\`);
        }

        // Get commit hash
        const fullCommitHash = await git.resolveRef({ fs, dir: extensionPath, ref: 'HEAD' });
        const shortCommitHash = fullCommitHash.slice(0, 7);

        return response.send({ shortCommitHash, extensionPath, isUpToDate, remoteUrl });
    } catch (error) {
        console.error('Updating extension failed', error);
        return response.status(500).send('Internal Server Error. Check the server logs for more details.');
    }
});`;

content = content.replace(updateRoutePattern, newUpdateRoute);

// ============================================================================
// Patch 4: Replace /branches endpoint with isomorphic-git version
// ============================================================================
const branchesRoutePattern = /router\.post\('\/branches',[\s\S]*?^\}\);/m;
const branchesRouteMatch = content.match(branchesRoutePattern);

if (!branchesRouteMatch) {
    console.warn('[STANDROID] WARNING: Could not find /branches route (may not exist in this ST version)');
} else {
    const newBranchesRoute = `${PATCH_MARKER}: Replaced /branches with isomorphic-git implementation
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
            console.error(\`User \${request.user.profile.handle} does not have permission to list branches of global extensions.\`);
            return response.status(403).send('Forbidden: No permission to list branches of global extensions.');
        }

        const basePath = global ? PUBLIC_DIRECTORIES.globalExtensions : request.user.directories.extensions;
        const extensionPath = path.join(basePath, extensionNameSanitized);

        if (!fs.existsSync(extensionPath)) {
            return response.status(404).send(\`Directory does not exist at \${extensionPath}\`);
        }

        // Fetch all branches from remote
        await git.fetch({
            fs,
            http,
            dir: extensionPath,
            remote: 'origin',
            prune: true,
            depth: 999999, // Unshallow if needed
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
                    ref: \`refs/remotes/origin/\${branchName}\`,
                });
                const fullName = \`origin/\${branchName}\`;
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
});`;

    content = content.replace(branchesRoutePattern, newBranchesRoute);
}

// ============================================================================
// Patch 5: Replace /switch endpoint with isomorphic-git version
// ============================================================================
const switchRoutePattern = /router\.post\('\/switch',[\s\S]*?^\}\);/m;
const switchRouteMatch = content.match(switchRoutePattern);

if (!switchRouteMatch) {
    console.warn('[STANDROID] WARNING: Could not find /switch route (may not exist in this ST version)');
} else {
    const newSwitchRoute = `${PATCH_MARKER}: Replaced /switch with isomorphic-git implementation
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
            console.error(\`User \${request.user.profile.handle} does not have permission to switch branches of global extensions.\`);
            return response.status(403).send('Forbidden: No permission to switch branches of global extensions.');
        }

        const basePath = global ? PUBLIC_DIRECTORIES.globalExtensions : request.user.directories.extensions;
        const extensionPath = path.join(basePath, extensionNameSanitized);

        if (!fs.existsSync(extensionPath)) {
            return response.status(404).send(\`Directory does not exist at \${extensionPath}\`);
        }

        const currentBranch = await git.currentBranch({ fs, dir: extensionPath });
        let targetBranch = branch;

        // Handle origin/ prefix
        if (String(branch).startsWith('origin/')) {
            targetBranch = branch.replace('origin/', '');
            
            // Check if local branch exists
            const localBranches = await git.listBranches({ fs, dir: extensionPath });
            
            if (!localBranches.includes(targetBranch)) {
                console.info(\`Branch \${targetBranch} does not exist locally, creating it from \${branch}\`);
                
                // Create local branch tracking remote
                const remoteSha = await git.resolveRef({
                    fs,
                    dir: extensionPath,
                    ref: \`refs/remotes/\${branch}\`,
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
            console.info(\`Branch \${targetBranch} is already checked out\`);
            return response.sendStatus(204);
        }

        // Checkout the branch
        await git.checkout({
            fs,
            dir: extensionPath,
            ref: targetBranch,
        });
        
        console.info(\`Checked out branch \${targetBranch} at \${extensionPath}\`);

        return response.sendStatus(204);
    } catch (error) {
        console.error('Switching branches failed', error);
        return response.status(500).send('Internal Server Error. Check the server logs for more details.');
    }
});`;

    content = content.replace(switchRoutePattern, newSwitchRoute);
}

// ============================================================================
// Patch 6: Replace /version endpoint with isomorphic-git version
// ============================================================================
const versionRoutePattern = /router\.post\('\/version',[\s\S]*?^\}\);/m;
const versionRouteMatch = content.match(versionRoutePattern);

if (!versionRouteMatch) {
    console.warn('[STANDROID] WARNING: Could not find /version route');
} else {
    const newVersionRoute = `${PATCH_MARKER}: Replaced /version with isomorphic-git implementation
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
            return response.status(404).send(\`Directory does not exist at \${extensionPath}\`);
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
});`;

    content = content.replace(versionRoutePattern, newVersionRoute);
}

// ============================================================================
// Write patched content back to file
// ============================================================================
try {
    fs.writeFileSync(EXTENSIONS_FILE, content, 'utf8');
    console.log('[STANDROID] ✓ Patch applied successfully!');
    console.log('[STANDROID] Modified endpoints: /update, /branches, /switch, /version');
    console.log('[STANDROID] Extensions will now work without native git binary on Android');
    process.exit(0);
} catch (error) {
    console.error('[STANDROID] ERROR: Failed to write patched file:', error.message);
    process.exit(1);
}
