import * as cp from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

/**
 * Pure (no `vscode` import) discovery logic for the `kuml-lsp` language server
 * launcher. Deliberately mirrors the resolution order of
 * `dev.kuml.langsupport.cli.KumlCliLocator.resolveLsp` (Kotlin, in
 * `kuml-lang-support`) so the VS Code client and the JetBrains/CLI world agree
 * on where the binary lives. There is no shared code path across the JVM and
 * Node worlds, so this is a small faithful reimplementation — the Kotlin
 * source is the single source of *behavior*; keep the two in sync by hand.
 *
 * Resolution order (first match wins):
 *  1. Explicit override — env var `KUML_LSP`.
 *  2. A caller-supplied `lspPath` (e.g. the `kuml.lspPath` VS Code setting).
 *  3. The launcher on `PATH` (`which` / `where`).
 *  4. Common install locations (`/opt/homebrew/bin`, `/usr/local/bin`,
 *     `~/.local/bin`).
 *  5. A local Gradle build, discovered by walking up from each workspace
 *     directory to `.../kuml-language-server/build/install/kuml-lsp/bin/<launcher>`.
 *
 * If nothing resolves, the bare launcher name is returned as a last resort so
 * `ServerOptions` still attempts to spawn it via the shell's own PATH lookup.
 */

export interface LspLauncherConfig {
    /** The `kuml.lspPath` setting value, if any (blank/undefined = unset). */
    lspPath?: string;
    /** Workspace folder roots to walk up from, looking for a local Gradle build. */
    workspaceDirs: string[];
}

/** Relative path segments of the LSP launcher inside a Gradle `installDist` output. */
const LSP_INSTALL_REL = ['kuml-language-server', 'build', 'install', 'kuml-lsp', 'bin'];

const MAX_WALK_UP_DEPTH = 40;

export function launcherName(): string {
    return process.platform === 'win32' ? 'kuml-lsp.bat' : 'kuml-lsp';
}

export function resolveLspLauncher(config: LspLauncherConfig): string {
    const launcher = launcherName();

    const envOverride = process.env.KUML_LSP;
    if (envOverride && isUsable(envOverride)) {
        return envOverride;
    }

    const configured = config.lspPath?.trim();
    if (configured && isUsable(configured)) {
        return configured;
    }

    const onPathResult = resolveOnPath(launcher);
    if (onPathResult) {
        return onPathResult;
    }

    const common = commonLocations(launcher).find(isUsable);
    if (common) {
        return common;
    }

    for (const dir of config.workspaceDirs) {
        const found = walkUpForLocalBuild(dir, launcher);
        if (found) {
            return found;
        }
    }

    // Last resort: return the bare launcher name so `ServerOptions` still
    // tries a shell-level PATH lookup at spawn time.
    return launcher;
}

function isUsable(p: string): boolean {
    try {
        if (!fs.existsSync(p) || !fs.statSync(p).isFile()) {
            return false;
        }
        // `.bat` launchers on Windows aren't marked executable via the X bit —
        // skip the access check there. On POSIX, be lenient: don't hard-fail
        // if the access check itself throws for an unrelated reason.
        if (process.platform !== 'win32') {
            try {
                fs.accessSync(p, fs.constants.X_OK);
            } catch {
                return false;
            }
        }
        return true;
    } catch {
        return false;
    }
}

function resolveOnPath(launcher: string): string | undefined {
    try {
        const tool = process.platform === 'win32' ? 'where' : 'which';
        const result = cp.spawnSync(tool, [launcher], { encoding: 'utf8' });
        const out = (result.stdout ?? '').toString();
        return out
            .split(/\r?\n/)
            .map((line) => line.trim())
            .find((line) => line.length > 0 && isUsable(line));
    } catch {
        return undefined;
    }
}

function commonLocations(launcher: string): string[] {
    const home = os.homedir();
    return [
        path.join('/opt/homebrew/bin', launcher),
        path.join('/usr/local/bin', launcher),
        path.join(home, '.local', 'bin', launcher),
    ];
}

function walkUpForLocalBuild(start: string, launcher: string): string | undefined {
    let dir: string | undefined = path.resolve(start);
    let depth = 0;
    while (dir && depth < MAX_WALK_UP_DEPTH) {
        const candidate = path.join(dir, ...LSP_INSTALL_REL, launcher);
        if (isUsable(candidate)) {
            return candidate;
        }
        const parent = path.dirname(dir);
        dir = parent === dir ? undefined : parent;
        depth++;
    }
    return undefined;
}
