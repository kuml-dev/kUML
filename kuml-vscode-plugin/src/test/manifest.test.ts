/**
 * Lightweight contract tests for the extension manifest, grammar, and snippets.
 *
 * These run in plain Node (no VS Code host) and catch the most common shipping
 * mistakes:
 *  - Manifest declares a language with the right extension and grammar.
 *  - The grammar's `scopeName` matches what the manifest references.
 *  - Each snippet has a valid `prefix` + `body` + `scope = "kuml"`.
 *
 * Full integration testing (activation, command execution, render flow) needs
 * `@vscode/test-electron`, which spawns a full VS Code instance — out of scope
 * for V1.1.11. Marketplace install does the equivalent smoke test for us.
 */
import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as path from 'node:path';

// __dirname at runtime = <plugin>/out/test → two `..` lands at the plugin root.
const ROOT = path.resolve(__dirname, '..', '..');
const MANIFEST = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'));
const GRAMMAR = JSON.parse(
    fs.readFileSync(path.join(ROOT, 'syntaxes', 'kuml.tmLanguage.json'), 'utf8'),
);
const SNIPPETS = JSON.parse(fs.readFileSync(path.join(ROOT, 'snippets', 'kuml.json'), 'utf8'));

test('manifest declares the kuml language with the .kuml.kts extension', () => {
    const languages = MANIFEST.contributes.languages as Array<Record<string, unknown>>;
    const kuml = languages.find((l) => l.id === 'kuml');
    assert.ok(kuml, 'language id "kuml" must be present in contributes.languages');
    assert.deepEqual(kuml!.extensions, ['.kuml.kts']);
    assert.ok(kuml!.configuration, 'language must reference a configuration file');
});

test('manifest grammar references the same scopeName the grammar declares', () => {
    const grammars = MANIFEST.contributes.grammars as Array<Record<string, unknown>>;
    const kuml = grammars.find((g) => g.language === 'kuml');
    assert.ok(kuml, 'grammar for language "kuml" must be present');
    assert.equal(kuml!.scopeName, GRAMMAR.scopeName);
    assert.equal(GRAMMAR.scopeName, 'source.kuml');
});

test('manifest registers the render command and its menu placements', () => {
    const commands = MANIFEST.contributes.commands as Array<Record<string, string>>;
    assert.ok(
        commands.find((c) => c.command === 'kuml.renderToSvg'),
        'kuml.renderToSvg command must be declared',
    );
    const menus = MANIFEST.contributes.menus as Record<string, Array<Record<string, string>>>;
    for (const surface of ['editor/title', 'editor/context', 'commandPalette']) {
        assert.ok(
            menus[surface]?.some((m) => m.command === 'kuml.renderToSvg'),
            `kuml.renderToSvg must appear in menu surface ${surface}`,
        );
    }
});

test('manifest registers the preview + restart commands', () => {
    const commands = MANIFEST.contributes.commands as Array<Record<string, string>>;
    for (const cmd of ['kuml.showPreview', 'kuml.restartServer']) {
        assert.ok(commands.find((c) => c.command === cmd), `${cmd} command must be declared`);
    }
});

test('manifest places kuml.showPreview in editor/title, editor/context, and commandPalette', () => {
    const menus = MANIFEST.contributes.menus as Record<string, Array<Record<string, string>>>;
    for (const surface of ['editor/title', 'editor/context', 'commandPalette']) {
        assert.ok(
            menus[surface]?.some((m) => m.command === 'kuml.showPreview'),
            `kuml.showPreview must appear in menu surface ${surface}`,
        );
    }
});

test('manifest declares the required configuration settings', () => {
    const props = MANIFEST.contributes.configuration.properties as Record<
        string,
        Record<string, unknown>
    >;
    for (const key of [
        'kuml.cliPath',
        'kuml.theme',
        'kuml.format',
        'kuml.lspPath',
        'kuml.serverUrl',
        'kuml.diagnostics.enable',
        'kuml.diagnostics.debounceMs',
    ]) {
        assert.ok(props[key], `setting ${key} must exist`);
    }

    const diagEnable = props['kuml.diagnostics.enable'];
    assert.equal(diagEnable.type, 'boolean');
    assert.equal(diagEnable.default, true);

    const debounce = props['kuml.diagnostics.debounceMs'];
    assert.equal(debounce.type, 'number');
    assert.equal(debounce.minimum, 0);
});

test('manifest declares a PNG marketplace icon that exists on disk', () => {
    const icon = MANIFEST.icon as string | undefined;
    assert.ok(icon, 'manifest must declare an "icon" field');
    assert.ok(icon!.endsWith('.png'), 'marketplace icon must be a PNG (vsce rejects SVG icons)');
    assert.ok(fs.existsSync(path.join(ROOT, icon!)), `icon file ${icon} must exist on disk`);
});

test('manifest version is bumped to 0.2.0 for the LSP client + live-preview wave', () => {
    assert.equal(MANIFEST.version, '0.2.0');
});

test('vscode-languageclient is declared as a runtime dependency', () => {
    const deps = MANIFEST.dependencies as Record<string, string> | undefined;
    assert.ok(deps, 'manifest must declare a "dependencies" block');
    assert.ok(
        typeof deps!['vscode-languageclient'] === 'string' && deps!['vscode-languageclient'].length > 0,
        'vscode-languageclient must be a non-empty runtime dependency',
    );
});

test('every snippet targets the kuml language and has a non-empty body', () => {
    const required = [
        'Class diagram skeleton',
        'UML model wrapper',
        'Class declaration',
        'Interface declaration',
        'Enum declaration',
        'C4 model',
        'Association between classes',
    ];
    for (const name of required) {
        const snippet = SNIPPETS[name] as Record<string, unknown> | undefined;
        assert.ok(snippet, `snippet "${name}" must be present (V1.1 spec)`);
        assert.equal(snippet!.scope, 'kuml');
        assert.ok(
            typeof snippet!.prefix === 'string' && (snippet!.prefix as string).length > 0,
            `snippet "${name}" must have a non-empty prefix`,
        );
        const body = snippet!.body as unknown;
        assert.ok(
            Array.isArray(body) && body.length > 0,
            `snippet "${name}" body must be a non-empty array`,
        );
    }
});

test('grammar covers every spec-mandated DSL entry-point keyword', () => {
    // Spec (V1.1 §"VS Code Plugin"): snippets `diagram`, `umlModel`, `classOf`,
    // `interfaceOf`, `enumOf`, `c4Model`, `association` MUST be highlighted as
    // DSL builders so the snippet expansion lights up immediately.
    const repo = GRAMMAR.repository as Record<string, { patterns?: Array<{ match?: string }> }>;
    const dslGroup = repo['kuml-dsl'];
    assert.ok(dslGroup, 'grammar must define a #kuml-dsl pattern group');
    const allMatches = (dslGroup.patterns ?? [])
        .map((p) => p.match ?? '')
        .join(' | ');
    for (const word of ['classDiagram', 'umlModel', 'classOf', 'interfaceOf', 'enumOf', 'c4Model', 'association']) {
        assert.ok(
            allMatches.includes(word),
            `DSL keyword "${word}" must appear in the #kuml-dsl pattern group`,
        );
    }
});

test('extension activates only on the kuml language to keep startup cheap', () => {
    assert.deepEqual(MANIFEST.activationEvents, ['onLanguage:kuml']);
});
