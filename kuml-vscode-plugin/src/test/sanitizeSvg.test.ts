/**
 * Unit tests for the SVG sanitizer used by the live-preview webview
 * (`src/svgSanitize.ts`). Pure logic, no `vscode` import, runs in plain Node.
 */
import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { sanitizeSvg } from '../svgSanitize';

test('strips <script> blocks', () => {
    const raw = '<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script><rect/></svg>';
    const out = sanitizeSvg(raw);
    assert.ok(!/<script/i.test(out), 'no <script tag should survive');
    assert.ok(!/alert\(1\)/.test(out), 'script body must be removed');
    assert.ok(/<rect\/>/.test(out), 'unrelated content must survive');
});

test('strips inline event-handler attributes', () => {
    const raw = '<svg xmlns="http://www.w3.org/2000/svg"><rect onclick="alert(1)" width="1"/></svg>';
    const out = sanitizeSvg(raw);
    assert.ok(!/onclick/i.test(out), 'onclick attribute must be stripped');
    assert.ok(/width="1"/.test(out), 'unrelated attributes must survive');
});

test('drops <foreignObject> blocks', () => {
    const raw =
        '<svg xmlns="http://www.w3.org/2000/svg"><foreignObject><div>hi</div></foreignObject><circle/></svg>';
    const out = sanitizeSvg(raw);
    assert.ok(!/foreignObject/i.test(out), 'foreignObject must be dropped');
    assert.ok(/<circle\/>/.test(out), 'unrelated content must survive');
});

test('neutralizes external href/xlink:href while keeping anchors and data: images', () => {
    const raw = [
        '<svg xmlns="http://www.w3.org/2000/svg">',
        '<a href="http://evil.example/">bad</a>',
        '<a xlink:href="https://evil.example/">also bad</a>',
        '<a href="#anchor">good anchor</a>',
        '<image href="data:image/png;base64,AAAA"/>',
        '</svg>',
    ].join('');
    const out = sanitizeSvg(raw);
    assert.ok(!/href="http/i.test(out), 'external http(s) hrefs must be neutralized');
    assert.ok(/href="#anchor"/.test(out), 'same-document anchors must survive');
    assert.ok(/href="data:image\/png;base64,AAAA"/.test(out), 'data:image hrefs must survive');
});

test('passes a normal svg through intact (module structure preserved)', () => {
    const raw = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10"/></svg>';
    const out = sanitizeSvg(raw);
    assert.ok(out.includes('<svg'));
    assert.ok(out.includes('viewBox="0 0 10 10"'));
    assert.ok(out.includes('<rect width="10" height="10"/>'));
});

test('strips a leading XML declaration and DOCTYPE', () => {
    const raw =
        '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">\n<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>';
    const out = sanitizeSvg(raw);
    assert.ok(!/<\?xml/.test(out));
    assert.ok(!/<!DOCTYPE/i.test(out));
    assert.ok(out.startsWith('<svg'));
});
