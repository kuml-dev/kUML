import * as vscode from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
} from 'vscode-languageclient/node';
import { resolveLspLauncher } from './lspLocator';

/**
 * Builds and manages the `kuml-lsp` language client. The LSP server itself is
 * render-agnostic (diagnostics + completion only, no custom render request —
 * see `kuml-language-server`'s capabilities) so this module's only job is to
 * discover the launcher, wire stdio transport, and forward `kuml.*` settings
 * via `synchronize.configurationSection`.
 */

function workspaceDirs(): string[] {
    return (vscode.workspace.workspaceFolders ?? []).map((f) => f.uri.fsPath);
}

/**
 * Builds an un-started `LanguageClient`. `context` is currently unused beyond
 * keeping the signature stable for callers that may want to register
 * disposables alongside client creation in the future.
 */
export function createClient(_context: vscode.ExtensionContext): LanguageClient {
    const config = vscode.workspace.getConfiguration('kuml');
    const lspPath = config.get<string>('lspPath', '');

    const resolved = resolveLspLauncher({
        lspPath,
        workspaceDirs: workspaceDirs(),
    });

    const executable = {
        command: resolved,
        args: [] as string[],
        transport: TransportKind.stdio,
    };

    const serverOptions: ServerOptions = {
        run: executable,
        debug: executable,
    };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ language: 'kuml' }],
        synchronize: {
            // Pushes the whole `kuml.*` settings object to the server on
            // activation and on every change, via workspace/didChangeConfiguration.
            // KumlWorkspaceService.didChangeConfiguration parses exactly the
            // { cliPath, diagnostics: { enable, debounceMs } } shape this produces.
            configurationSection: 'kuml',
        },
        outputChannelName: 'kUML Language Server',
    };

    return new LanguageClient('kuml-lsp', 'kUML Language Server', serverOptions, clientOptions);
}

export async function startClient(client: LanguageClient): Promise<void> {
    await client.start();
}

export async function stopClient(client: LanguageClient | undefined): Promise<void> {
    if (!client) {
        return;
    }
    await client.stop();
}
