// kUML Web UI — ES module, CodeMirror 6 via CDN
// Provides: editor, live SVG preview, examples/theme/layout dropdowns,
//           SVG and PNG download

// ── CodeMirror 6 from CDN (importmap in index.html would be cleaner,
//    but CDN ESM bundles avoid a build step entirely)
import { basicSetup } from 'https://esm.sh/@codemirror/basic-setup@0.20.0';
import { EditorView, keymap } from 'https://esm.sh/@codemirror/view@6.36.3';
import { EditorState } from 'https://esm.sh/@codemirror/state@6.5.2';
import { defaultKeymap } from 'https://esm.sh/@codemirror/commands@6.8.1';
import { oneDark } from 'https://esm.sh/@codemirror/theme-one-dark@6.1.2';

// ── DOM refs ─────────────────────────────────────────────────────────────────
const previewEl = document.getElementById('preview');
const errorBannerEl = document.getElementById('error-banner');
const renderTimeEl = document.getElementById('render-time');
const examplesSelect = document.getElementById('examples-select');
const themeSelect = document.getElementById('theme-select');
const layoutSelect = document.getElementById('layout-select');
const downloadSvgBtn = document.getElementById('download-svg');
const downloadPngBtn = document.getElementById('download-png');
const downloadLatexBtn = document.getElementById('download-latex');

// ── State ─────────────────────────────────────────────────────────────────────
let lastSvg = null;
let lastLatex = null;
let debounceTimer = null;

// ── Editor ────────────────────────────────────────────────────────────────────
const updateListener = EditorView.updateListener.of((update) => {
  if (update.docChanged) {
    scheduleRender();
  }
});

const editorView = new EditorView({
  state: EditorState.create({
    doc: '',
    extensions: [basicSetup, oneDark, keymap.of(defaultKeymap), updateListener],
  }),
  parent: document.getElementById('editor'),
});

// ── Render ────────────────────────────────────────────────────────────────────
function scheduleRender() {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(renderSvg, 300);
}

async function renderSvg() {
  const script = editorView.state.doc.toString().trim();
  if (!script) {
    previewEl.innerHTML = '<em>Type a kUML script to preview...</em>';
    errorBannerEl.textContent = '';
    errorBannerEl.classList.add('hidden');
    lastSvg = null;
    lastLatex = null;
    downloadLatexBtn.disabled = true;
    renderTimeEl.textContent = '';
    return;
  }

  const theme = themeSelect.value || null;
  const layout = layoutSelect.value || 'auto';

  try {
    const res = await fetch('/api/render', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ script, format: 'svg', theme, layout }),
    });

    const data = await res.json();

    if (data.ok && data.svg) {
      lastSvg = data.svg;
      previewEl.innerHTML = data.svg;
      errorBannerEl.textContent = '';
      errorBannerEl.classList.add('hidden');
      renderTimeEl.textContent = `Rendered in ${data.durationMs}ms`;
      downloadLatexBtn.disabled = false;
    } else {
      showError(data.error || 'Unknown error');
      renderTimeEl.textContent = '';
      downloadLatexBtn.disabled = true;
    }
  } catch (err) {
    showError(`Network error: ${err.message}`);
    renderTimeEl.textContent = '';
  }
}

function showError(msg) {
  errorBannerEl.textContent = msg;
  errorBannerEl.classList.remove('hidden');
}

// ── Load examples ─────────────────────────────────────────────────────────────
async function loadExamples() {
  try {
    const res = await fetch('/api/examples');
    const data = await res.json();
    for (const example of data.examples) {
      const opt = document.createElement('option');
      opt.value = example.name;
      opt.textContent = example.title;
      examplesSelect.appendChild(opt);
    }
  } catch (err) {
    console.warn('Failed to load examples:', err);
  }
}

examplesSelect.addEventListener('change', async () => {
  const name = examplesSelect.value;
  if (!name) return;
  try {
    const res = await fetch(`/api/examples/${name}`);
    if (res.ok) {
      const source = await res.text();
      editorView.dispatch({
        changes: { from: 0, to: editorView.state.doc.length, insert: source },
      });
      scheduleRender();
    }
  } catch (err) {
    console.warn('Failed to load example:', err);
  }
  examplesSelect.value = '';
});

// ── Load themes ───────────────────────────────────────────────────────────────
async function loadThemes() {
  try {
    const res = await fetch('/api/themes');
    const data = await res.json();
    for (const theme of data.themes) {
      const opt = document.createElement('option');
      opt.value = theme;
      opt.textContent = `Theme: ${theme}`;
      themeSelect.appendChild(opt);
    }
  } catch (err) {
    console.warn('Failed to load themes:', err);
  }
}

themeSelect.addEventListener('change', scheduleRender);
layoutSelect.addEventListener('change', scheduleRender);

// ── SVG Download ──────────────────────────────────────────────────────────────
downloadSvgBtn.addEventListener('click', () => {
  if (!lastSvg) return;
  const blob = new Blob([lastSvg], { type: 'image/svg+xml' });
  triggerDownload(blob, 'diagram.svg');
});

// ── PNG Download ──────────────────────────────────────────────────────────────
downloadPngBtn.addEventListener('click', async () => {
  const script = editorView.state.doc.toString().trim();
  if (!script) return;

  const theme = themeSelect.value || null;
  const layout = layoutSelect.value || 'auto';

  try {
    const res = await fetch('/api/render', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ script, format: 'png', theme, layout }),
    });
    const data = await res.json();
    if (data.ok && data.pngBase64) {
      const binary = atob(data.pngBase64);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      const blob = new Blob([bytes], { type: 'image/png' });
      triggerDownload(blob, 'diagram.png');
    } else {
      showError(data.error || 'PNG render failed');
    }
  } catch (err) {
    showError(`PNG download error: ${err.message}`);
  }
});

// ── LaTeX Download ────────────────────────────────────────────────────────────
downloadLatexBtn.addEventListener('click', async () => {
  const script = editorView.state.doc.toString().trim();
  if (!script) return;

  const theme = themeSelect.value || null;
  const layout = layoutSelect.value || 'auto';
  const standaloneTex = document.getElementById('standalone-tex').checked;

  try {
    const res = await fetch('/api/render', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ script, format: 'latex', theme, layout, standaloneTex }),
    });
    const data = await res.json();
    if (data.ok && data.latex) {
      lastLatex = data.latex;
      const blob = new Blob([lastLatex], { type: 'application/x-tex' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = 'diagram.tex';
      a.click(); URL.revokeObjectURL(url);
    } else {
      showError(data.error || 'LaTeX render failed');
    }
  } catch (err) {
    showError(`LaTeX download error: ${err.message}`);
  }
});

function triggerDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

// ── Init ──────────────────────────────────────────────────────────────────────
(async function init() {
  await Promise.all([loadExamples(), loadThemes()]);
})();
