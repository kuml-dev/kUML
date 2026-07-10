/**
 * Sanitizes a `kuml render`-produced SVG string before it is inlined into the
 * live-preview webview. This is defense-in-depth on top of the webview's own
 * `enableScripts: false` + strict CSP — the SVG is a static asset, not
 * attacker-controlled input in the usual sense (it comes from the local CLI
 * or a locally-configured `kuml.serverUrl`), but stripping active content
 * keeps the preview safe even if a future renderer regression, plugin, or
 * theme accidentally emits something it shouldn't.
 *
 * No `vscode` import here — kept pure so it's unit-testable in plain Node.
 */

/** Strips `<script>...</script>` blocks (including empty ones), case-insensitively. */
function stripScriptTags(svg: string): string {
    return svg.replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, '');
}

/** Strips `<foreignObject>...</foreignObject>` blocks — can smuggle arbitrary HTML/JS. */
function stripForeignObject(svg: string): string {
    return svg.replace(/<foreignObject\b[^>]*>[\s\S]*?<\/foreignObject\s*>/gi, '');
}

/** Strips `on*="..."` / `on*='...'` inline event-handler attributes from any tag. */
function stripEventHandlerAttrs(svg: string): string {
    return svg.replace(/\s+on[a-z]+\s*=\s*(?:"[^"]*"|'[^']*')/gi, '');
}

/**
 * Neutralizes `href`/`xlink:href` attributes whose value is not a same-document
 * anchor (`#...`) or an inline image data URI (`data:image/...`). External
 * references (http(s), file, javascript: URIs) are dropped entirely.
 */
function neutralizeExternalHrefs(svg: string): string {
    return svg.replace(
        /\s(xlink:href|href)\s*=\s*("([^"]*)"|'([^']*)')/gi,
        (full, attrName: string, _quoted: string, dq?: string, sq?: string) => {
            const value = (dq ?? sq ?? '').trim();
            if (value.startsWith('#') || /^data:image\//i.test(value)) {
                return full;
            }
            return '';
        },
    );
}

/** Strips a leading XML declaration and DOCTYPE so the fragment embeds cleanly into HTML. */
function stripXmlPreamble(svg: string): string {
    return svg
        .replace(/<\?xml[^>]*\?>/gi, '')
        .replace(/<!DOCTYPE[^>]*>/gi, '')
        .trim();
}

export function sanitizeSvg(raw: string): string {
    let svg = raw;
    svg = stripXmlPreamble(svg);
    svg = stripScriptTags(svg);
    svg = stripForeignObject(svg);
    svg = stripEventHandlerAttrs(svg);
    svg = neutralizeExternalHrefs(svg);
    return svg;
}
