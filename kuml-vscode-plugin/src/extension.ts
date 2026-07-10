import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import { LanguageClient } from 'vscode-languageclient/node';
import { createClient, startClient, stopClient } from './lspClient';
import { KumlPreviewPanel } from './previewPanel';
import { spawnCli } from './cli';

/**
 * VS Code extension entry point for kUML.
 *
 * Activation contract:
 *  - The extension activates on `onLanguage:kuml` — i.e. the first time a
 *    `*.kuml.kts` file is opened. That's also when language + grammar +
 *    snippets become active, and when the LSP client starts.
 *  - Runtime commands: `kuml.renderToSvg` (one-shot render; SVG routes into
 *    the live-preview panel, PNG opens in the OS viewer), `kuml.showPreview`
 *    (opens/reveals the live-preview webview), and `kuml.restartServer`
 *    (stops and recreates the LSP client).
 *  - The LSP server (`kuml-lsp`) is render-agnostic — diagnostics and
 *    completion only. Live preview rendering is entirely a client-side
 *    concern (see `previewPanel.ts`'s dual server/CLI strategy).
 */

let client: LanguageClient | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    client = createClient(context);
    void startClient(client);
    context.subscriptions.push({ dispose: () => void stopClient(client) });

    context.subscriptions.push(
        vscode.commands.registerCommand('kuml.renderToSvg', renderActiveDocument),
        vscode.commands.registerCommand('kuml.showPreview', () => KumlPreviewPanel.show(context)),
        vscode.commands.registerCommand('kuml.restartServer', () => restartServer(context)),
    );

    context.subscriptions.push(
        vscode.workspace.onDidSaveTextDocument((doc) => {
            void KumlPreviewPanel.renderIfOpen(doc);
        }),
        vscode.window.onDidChangeActiveTextEditor((editor) => {
            if (editor) {
                void KumlPreviewPanel.renderIfOpen(editor.document);
            }
        }),
    );
}

export async function deactivate(): Promise<void> {
    KumlPreviewPanel.disposeAll();
    await stopClient(client);
}

async function restartServer(context: vscode.ExtensionContext): Promise<void> {
    await stopClient(client);
    client = createClient(context);
    await startClient(client);
    await vscode.window.showInformationMessage('kUML language server restarted.');
}

/**
 * Renders the active editor's kUML script via the `kuml` CLI.
 *  - `format === 'svg'`: delegate to the live-preview panel (replaces the old
 *    open-in-tab flow).
 *  - `format === 'png'`: keep the original temp-file spawn + OS-viewer open.
 */
async function renderActiveDocument(): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'kuml') {
        await vscode.window.showWarningMessage('Open a *.kuml.kts file before running this command.');
        return;
    }

    const config = vscode.workspace.getConfiguration('kuml');
    const format = config.get<string>('format', 'svg');

    if (format === 'svg') {
        await vscode.commands.executeCommand('kuml.showPreview');
        return;
    }

    const cliPath = config.get<string>('cliPath', 'kuml');
    const theme = config.get<string>('theme', 'kuml');

    const sourceUri = editor.document.uri;
    const sourceName = path.basename(sourceUri.fsPath, '.kuml.kts');
    const baseName = sourceName || 'diagram';

    // Always write a snapshot to a temp file — this works even for dirty
    // buffers (unsaved changes) and for untitled documents.
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'kuml-vscode-'));
    const tmpSource = path.join(tmpDir, `${baseName}.kuml.kts`);
    const tmpOutput = path.join(tmpDir, `${baseName}.${format}`);
    fs.writeFileSync(tmpSource, editor.document.getText(), { encoding: 'utf8' });

    const args = ['render', '--theme', theme, '--format', format, '--output', tmpOutput, tmpSource];

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
                await vscode.window.showErrorMessage(`kUML render produced no output at ${tmpOutput}.`);
                return;
            }

            // PNG → hand off to OS viewer.
            await vscode.env.openExternal(vscode.Uri.file(tmpOutput));
        },
    );
}
