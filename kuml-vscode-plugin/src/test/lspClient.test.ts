/**
 * Unit tests for the pure `kuml-lsp` launcher discovery logic in
 * `src/lspLocator.ts`. No `vscode` import there, so this runs in plain Node
 * (no Extension Development Host needed) — same approach as manifest.test.ts.
 */
import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { launcherName, resolveLspLauncher } from '../lspLocator';

function makeExecutable(p: string): void {
    fs.writeFileSync(p, '#!/bin/sh\necho hi\n');
    fs.chmodSync(p, 0o755);
}

function withTempDir<T>(fn: (dir: string) => T): T {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'kuml-lsp-locator-test-'));
    try {
        return fn(dir);
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

test('launcherName() returns kuml-lsp on non-win32', () => {
    if (process.platform === 'win32') {
        assert.equal(launcherName(), 'kuml-lsp.bat');
    } else {
        assert.equal(launcherName(), 'kuml-lsp');
    }
});

test('resolveLspLauncher returns an explicit lspPath when it points to a usable executable', () => {
    withTempDir((dir) => {
        const exe = path.join(dir, 'kuml-lsp');
        makeExecutable(exe);
        const originalEnv = process.env.KUML_LSP;
        delete process.env.KUML_LSP;
        try {
            const resolved = resolveLspLauncher({ lspPath: exe, workspaceDirs: [] });
            assert.equal(resolved, exe);
        } finally {
            if (originalEnv !== undefined) {
                process.env.KUML_LSP = originalEnv;
            }
        }
    });
});

test('resolveLspLauncher treats a blank lspPath as unset and falls through', () => {
    const originalEnv = process.env.KUML_LSP;
    delete process.env.KUML_LSP;
    try {
        const resolved = resolveLspLauncher({ lspPath: '   ', workspaceDirs: [] });
        assert.notEqual(resolved.trim(), '');
        assert.notEqual(resolved, '   ');
    } finally {
        if (originalEnv !== undefined) {
            process.env.KUML_LSP = originalEnv;
        }
    }
});

test('KUML_LSP env var wins over a provided lspPath', () => {
    withTempDir((dir) => {
        const envExe = path.join(dir, 'from-env');
        const configuredExe = path.join(dir, 'from-config');
        makeExecutable(envExe);
        makeExecutable(configuredExe);

        const originalEnv = process.env.KUML_LSP;
        process.env.KUML_LSP = envExe;
        try {
            const resolved = resolveLspLauncher({ lspPath: configuredExe, workspaceDirs: [] });
            assert.equal(resolved, envExe);
        } finally {
            if (originalEnv === undefined) {
                delete process.env.KUML_LSP;
            } else {
                process.env.KUML_LSP = originalEnv;
            }
        }
    });
});

test('resolveLspLauncher finds a local Gradle installDist build by walking up from a workspace dir', () => {
    withTempDir((dir) => {
        const projDir = path.join(dir, 'proj');
        const binDir = path.join(projDir, 'kuml-language-server', 'build', 'install', 'kuml-lsp', 'bin');
        fs.mkdirSync(binDir, { recursive: true });
        const launcher = launcherName();
        const exe = path.join(binDir, launcher);
        makeExecutable(exe);

        const subDir = path.join(projDir, 'sub', 'dir');
        fs.mkdirSync(subDir, { recursive: true });

        const originalEnv = process.env.KUML_LSP;
        delete process.env.KUML_LSP;
        try {
            const resolved = resolveLspLauncher({ lspPath: '', workspaceDirs: [subDir] });
            assert.equal(resolved, exe);
        } finally {
            if (originalEnv !== undefined) {
                process.env.KUML_LSP = originalEnv;
            }
        }
    });
});

test('resolveLspLauncher falls back to the bare launcher name when nothing resolves', () => {
    withTempDir((dir) => {
        const originalEnv = process.env.KUML_LSP;
        delete process.env.KUML_LSP;
        try {
            // An empty, otherwise-unrelated workspace dir with no installDist tree,
            // and `kuml-lsp` is not installed anywhere on this dev machine (PATH,
            // Homebrew, ~/.local/bin) — verified manually. So resolution must
            // exhaust every strategy and return the documented last resort: the
            // bare launcher name, so ServerOptions still tries a PATH lookup at
            // spawn time.
            const resolved = resolveLspLauncher({ lspPath: '', workspaceDirs: [dir] });
            assert.equal(resolved, launcherName());
        } finally {
            if (originalEnv !== undefined) {
                process.env.KUML_LSP = originalEnv;
            }
        }
    });
});
