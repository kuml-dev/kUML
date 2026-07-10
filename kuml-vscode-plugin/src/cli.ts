import * as cp from 'child_process';

/**
 * Shared `kuml` CLI subprocess helpers. Split out of `extension.ts` so both
 * the one-shot `kuml.renderToSvg` command and the live-preview panel's CLI
 * fallback share a single implementation — no drift between the two call
 * sites.
 */

/**
 * Spawn the `kuml` CLI and resolve once it exits cleanly. Rejects with a
 * trimmed stderr message if the CLI exits non-zero or can't be spawned at all
 * (e.g. CLI not on PATH).
 */
export function spawnCli(cliPath: string, args: string[]): Promise<void> {
    return new Promise((resolve, reject) => {
        const child = cp.spawn(cliPath, args, { shell: false });
        let stderr = '';
        child.stderr?.on('data', (chunk) => {
            stderr += chunk.toString('utf8');
        });
        child.on('error', (err) => {
            // ENOENT = CLI binary not found on PATH / explicit kuml.cliPath wrong.
            const hint = err.message.includes('ENOENT')
                ? ` (is the kUML CLI installed and on PATH? See setting "kuml.cliPath".)`
                : '';
            reject(new Error(err.message + hint));
        });
        child.on('exit', (code) => {
            if (code === 0) {
                resolve();
            } else {
                const tail = stderr.trim().split('\n').slice(-5).join('\n');
                reject(new Error(`kuml CLI exited with code ${code}\n${tail}`));
            }
        });
    });
}

/**
 * Spawn the `kuml` CLI and capture its stdout on success. Used by the preview
 * panel's CLI fallback path when it needs the rendered SVG text directly
 * rather than a file the caller writes.
 */
export function spawnCliCapture(cliPath: string, args: string[]): Promise<string> {
    return new Promise((resolve, reject) => {
        const child = cp.spawn(cliPath, args, { shell: false });
        let stdout = '';
        let stderr = '';
        child.stdout?.on('data', (chunk) => {
            stdout += chunk.toString('utf8');
        });
        child.stderr?.on('data', (chunk) => {
            stderr += chunk.toString('utf8');
        });
        child.on('error', (err) => {
            const hint = err.message.includes('ENOENT')
                ? ` (is the kUML CLI installed and on PATH? See setting "kuml.cliPath".)`
                : '';
            reject(new Error(err.message + hint));
        });
        child.on('exit', (code) => {
            if (code === 0) {
                resolve(stdout);
            } else {
                const tail = stderr.trim().split('\n').slice(-5).join('\n');
                reject(new Error(`kuml CLI exited with code ${code}\n${tail}`));
            }
        });
    });
}
