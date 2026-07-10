import * as vscode from 'vscode';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { spawnCli } from './cli';
import { sanitizeSvg } from './svgSanitize';

/**
 * Live-preview webview for `*.kuml.kts` documents.
 *
 * Render strategy (dual, in `renderFor`):
 *  1. If `kuml.serverUrl` is set, POST to `{serverUrl}/api/render` (the
 *     `kuml serve` HTTP API — see `kuml-web`'s `RenderRequest`/`RenderResponse`).
 *  2. Otherwise (or on any failure of the above), fall back to shelling out to
 *     the `kuml` CLI, exactly like the existing `kuml.renderToSvg` command.
 *
 * Security posture: the webview has `enableScripts: false` — the panel only
 * ever displays static, sanitized SVG markup inlined into the page (never
 * `<img src>`, so only inline `<style>`/attributes matter), plus a strict CSP
 * meta tag. `sanitizeSvg` is defense-in-depth on top of that.
 */
export class KumlPreviewPanel {
    private static current: KumlPreviewPanel | undefined;

    private readonly panel: vscode.WebviewPanel;
    private readonly context: vscode.ExtensionContext;
    private disposed = false;

    private constructor(context: vscode.ExtensionContext) {
        this.context = context;
        this.panel = vscode.window.createWebviewPanel(
            'kuml.preview',
            'kUML Preview',
            vscode.ViewColumn.Beside,
            {
                enableScripts: false,
                retainContextWhenHidden: true,
            },
        );
        this.panel.webview.html = this.wrapHtml('<p>Open a *.kuml.kts file and save it to render a preview.</p>');
        this.panel.onDidDispose(() => {
            this.disposed = true;
            if (KumlPreviewPanel.current === this) {
                KumlPreviewPanel.current = undefined;
            }
        });
    }

    /** Create the panel if none exists, otherwise reveal the existing one, then render the active editor. */
    static async show(context: vscode.ExtensionContext): Promise<void> {
        if (!KumlPreviewPanel.current) {
            KumlPreviewPanel.current = new KumlPreviewPanel(context);
        } else {
            KumlPreviewPanel.current.panel.reveal(vscode.ViewColumn.Beside);
        }

        const editor = vscode.window.activeTextEditor;
        if (editor && editor.document.languageId === 'kuml') {
            await KumlPreviewPanel.current.renderFor(editor.document);
        }
    }

    /** True if the panel is currently open (used by re-render triggers to skip work otherwise). */
    static isOpen(): boolean {
        return KumlPreviewPanel.current !== undefined && !KumlPreviewPanel.current.disposed;
    }

    static async renderIfOpen(document: vscode.TextDocument): Promise<void> {
        if (document.languageId !== 'kuml') {
            return;
        }
        if (KumlPreviewPanel.isOpen()) {
            await KumlPreviewPanel.current!.renderFor(document);
        }
    }

    static disposeAll(): void {
        KumlPreviewPanel.current?.panel.dispose();
        KumlPreviewPanel.current = undefined;
    }

    async renderFor(document: vscode.TextDocument): Promise<void> {
        const config = vscode.workspace.getConfiguration('kuml');
        const serverUrl = config.get<string>('serverUrl', '').trim();
        const theme = config.get<string>('theme', 'kuml');
        const cliPath = config.get<string>('cliPath', 'kuml');
        const script = document.getText();

        let svg: string | undefined;
        let fallbackNote: string | undefined;

        if (serverUrl) {
            try {
                svg = await this.renderViaServer(serverUrl, script, theme);
            } catch (err: unknown) {
                fallbackNote = err instanceof Error ? err.message : String(err);
            }
        }

        if (!svg) {
            try {
                svg = await this.renderViaCli(cliPath, theme, script, document.uri);
            } catch (err: unknown) {
                const message = err instanceof Error ? err.message : String(err);
                this.panel.webview.html = this.wrapHtml(this.errorCard(message, fallbackNote));
                return;
            }
        }

        const sanitized = sanitizeSvg(svg);
        const note = fallbackNote
            ? `<p class="kuml-note">kuml.serverUrl render failed, used CLI fallback: ${escapeHtml(fallbackNote)}</p>`
            : '';
        this.panel.webview.html = this.wrapHtml(`${note}<div class="kuml-svg">${sanitized}</div>`);
    }

    private async renderViaServer(serverUrl: string, script: string, theme: string): Promise<string> {
        const url = `${serverUrl.replace(/\/+$/, '')}/api/render`;
        const res = await fetch(url, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ script, format: 'svg', theme }),
        });
        if (!res.ok) {
            throw new Error(`kuml serve responded with HTTP ${res.status}`);
        }
        const body = (await res.json()) as { ok: boolean; svg?: string; error?: string };
        if (!body.ok || !body.svg) {
            throw new Error(body.error ?? 'kuml serve render returned ok=false with no error message');
        }
        return body.svg;
    }

    private async renderViaCli(
        cliPath: string,
        theme: string,
        script: string,
        sourceUri: vscode.Uri,
    ): Promise<string> {
        const baseName = path.basename(sourceUri.fsPath, '.kuml.kts') || 'diagram';
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'kuml-vscode-preview-'));
        const tmpSource = path.join(tmpDir, `${baseName}.kuml.kts`);
        const tmpOutput = path.join(tmpDir, `${baseName}.svg`);
        fs.writeFileSync(tmpSource, script, { encoding: 'utf8' });

        await spawnCli(cliPath, ['render', '--theme', theme, '--format', 'svg', '--output', tmpOutput, tmpSource]);

        if (!fs.existsSync(tmpOutput)) {
            throw new Error(`kuml render produced no output at ${tmpOutput}.`);
        }
        return fs.readFileSync(tmpOutput, { encoding: 'utf8' });
    }

    private errorCard(message: string, fallbackNote: string | undefined): string {
        const note = fallbackNote
            ? `<p class="kuml-note">kuml.serverUrl render also failed: ${escapeHtml(fallbackNote)}</p>`
            : '';
        return `${note}<div class="kuml-error"><p>kUML preview render failed:</p><pre>${escapeHtml(message)}</pre></div>`;
    }

    private wrapHtml(body: string): string {
        const csp = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'none';";
        return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta http-equiv="Content-Security-Policy" content="${csp}" />
<style>
  body { font-family: var(--vscode-font-family, sans-serif); color: var(--vscode-foreground); padding: 8px; }
  .kuml-svg svg { max-width: 100%; height: auto; }
  .kuml-error pre { white-space: pre-wrap; color: var(--vscode-errorForeground, #f48771); }
  .kuml-note { color: var(--vscode-descriptionForeground, #999); font-size: 0.9em; }
</style>
</head>
<body>
${body}
</body>
</html>`;
    }
}

function escapeHtml(s: string): string {
    return s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
