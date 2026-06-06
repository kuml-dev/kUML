import * as vscode from 'vscode';
import * as cp from 'child_process';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';

/**
 * VS Code extension entry point for kUML.
 *
 * Activation contract:
 *  - The extension activates on `onLanguage:kuml` — i.e. the first time a
 *    `*.kuml.kts` file is opened. That's also when language + grammar +
 *    snippets become active.
 *  - The only runtime command is `kuml.renderToSvg`. Everything else
 *    (syntax highlighting, snippets, file icon) is contributed declaratively
 *    via `package.json` and needs no JS code.
 */
export function activate(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand('kuml.renderToSvg', renderActiveDocument),
    );
}

export function deactivate(): void {
    // no resources to free — child processes spawn and exit eagerly.
}

/**
 * Renders the active editor's kUML script via the `kuml` CLI and opens the
 * resulting SVG (or PNG) in a VS Code preview tab.
 *
 * Strategy:
 *  - Resolve `kuml.cliPath`, `kuml.theme`, `kuml.format` from settings.
 *  - Write the active document's text to a temp file (so dirty buffers work).
 *  - Spawn `kuml render --theme … --format … --output <tmpOut> <tmpIn>`.
 *  - On success: open the rendered asset.
 *  - On failure: show a notification with the CLI stderr tail.
 *
 * The temp file lives in `os.tmpdir()` and is named after the source. We don't
 * delete it eagerly — VS Code's preview tab keeps a handle, and `os.tmpdir()`
 * gets cleaned by the OS at next reboot.
 */
async function renderActiveDocument(): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'kuml') {
        await vscode.window.showWarningMessage('Open a *.kuml.kts file before running this command.');
        return;
    }

    const config = vscode.workspace.getConfiguration('kuml');
    const cliPath = config.get<string>('cliPath', 'kuml');
    const theme = config.get<string>('theme', 'kuml');
    const format = config.get<string>('format', 'svg');

    const sourceUri = editor.document.uri;
    const sourceName = path.basename(sourceUri.fsPath, '.kuml.kts');
    const baseName = sourceName || 'diagram';

    // Always write a snapshot to a temp file — this works even for dirty
    // buffers (unsaved changes) and for untitled documents.
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'kuml-vscode-'));
    const tmpSource = path.join(tmpDir, `${baseName}.kuml.kts`);
    const tmpOutput = path.join(tmpDir, `${baseName}.${format}`);
    fs.writeFileSync(tmpSource, editor.document.getText(), { encoding: 'utf8' });

    const args = [
        'render',
        '--theme', theme,
        '--format', format,
        '--output', tmpOutput,
        tmpSource,
    ];

    await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: `kUML: rendering ${baseName}.${format}…`,
            cancellable: false,
        },
        async () => {
            try {
                await spawnCli(cliPath, args);
            } catch (err: unknown) {
                const message = err instanceof Error ? err.message : String(err);
                await vscode.window.showErrorMessage(`kUML render failed: ${message}`);
                return;
            }

            if (!fs.existsSync(tmpOutput)) {
                await vscode.window.showErrorMessage(
                    `kUML render produced no output at ${tmpOutput}.`,
                );
                return;
            }

            await openRenderedAsset(vscode.Uri.file(tmpOutput), format);
        },
    );
}

/**
 * Spawn the `kuml` CLI and resolve once it exits cleanly. Rejects with a
 * trimmed stderr message if the CLI exits non-zero or can't be spawned at all
 * (e.g. CLI not on PATH).
 */
function spawnCli(cliPath: string, args: string[]): Promise<void> {
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
 * Open the rendered file. For SVG, prefer VS Code's built-in HTML preview
 * (which renders SVG natively in a webview-backed tab via the "Open Preview"
 * command). For PNG, hand it off to the OS default viewer because VS Code's
 * binary file handling is awkward.
 */
async function openRenderedAsset(uri: vscode.Uri, format: string): Promise<void> {
    if (format === 'svg') {
        const doc = await vscode.workspace.openTextDocument(uri);
        await vscode.window.showTextDocument(doc, { preview: true });
        // Trigger the built-in markdown/SVG preview to render visually.
        await vscode.commands.executeCommand('vscode.openWith', uri, 'default');
    } else {
        // PNG → hand off to OS viewer.
        await vscode.env.openExternal(uri);
    }
}
