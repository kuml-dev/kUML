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

// V3.2 Wave 3 — drag-and-drop gesture state. Geometry from the last successful
// SVG render (Wave 2 payload: /api/render → { nodes, grid }).
let currentNodes = []; // NodeBox[]  {id,x,y,w,h}  in viewBox user-space
let currentGrid = null; // GridGeometry|null {cols,rows,cellW,cellH,originX,originY}
let dragController = null; // constructed in init(), used by renderSvg()

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
    currentNodes = [];
    currentGrid = null;
    dragController?.setDraggable(false);
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
      currentNodes = data.nodes || [];
      currentGrid = data.grid || null;
      dragController?.setDraggable(currentGrid !== null);
    } else {
      showError(data.error || 'Unknown error');
      renderTimeEl.textContent = '';
      downloadLatexBtn.disabled = true;
      currentNodes = [];
      currentGrid = null;
      dragController?.setDraggable(false);
    }
  } catch (err) {
    showError(`Network error: ${err.message}`);
    renderTimeEl.textContent = '';
    currentNodes = [];
    currentGrid = null;
    dragController?.setDraggable(false);
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

// ── Drag-and-drop gesture (V3.2 Wave 3) ────────────────────────────────────────
// Maps a client (screen) coordinate to SVG root/viewBox user-space, cancelling
// the CSS scaling applied by `#preview svg { max-width:100% }`. All hit-testing
// and cell math below happens in user units so it matches NodeBox/GridGeometry
// (both come from the same /api/render response — see Wave 2 payload).
function clientToUser(svg, clientX, clientY) {
  const ctm = svg.getScreenCTM();
  if (!ctm) return null;
  return new DOMPoint(clientX, clientY).matrixTransform(ctm.inverse());
}

// Mirror of dev.kuml.web.layout.GridCellResolver (server-side, authoritative,
// tested by GridCellResolverTest). Keep both in sync — floor into the containing
// cell, clamp to [0, count-1]; a degenerate (<=0) cell extent collapses to 0.
function resolveAxis(origin, cellExtent, count, pos) {
  if (cellExtent <= 0) return 0;
  const idx = Math.floor((pos - origin) / cellExtent);
  return Math.max(0, Math.min(idx, Math.max(count - 1, 0)));
}

function resolveCell(grid, xUser, yUser) {
  return {
    col: resolveAxis(grid.originX, grid.cellW, grid.cols, xUser),
    row: resolveAxis(grid.originY, grid.cellH, grid.rows, yUser),
  };
}

class DragController {
  constructor({ container, getNodes, getGrid, onDrop }) {
    this.container = container; // #preview (never re-created, survives innerHTML swaps)
    this.getNodes = getNodes; // () => currentNodes
    this.getGrid = getGrid; // () => currentGrid
    this.onDrop = onDrop; // (id, col, row) => Promise<void>
    this.drag = null; // active-drag state or null
    container.addEventListener('pointerdown', this.onDown.bind(this));
    container.addEventListener('pointermove', this.onMove.bind(this));
    container.addEventListener('pointerup', this.onUp.bind(this));
    container.addEventListener('pointercancel', this.onCancel.bind(this));
  }

  setDraggable(enabled) {
    this.container.classList.toggle('drag-enabled', enabled);
  }

  onDown(e) {
    if (e.button !== 0) return; // ignore non-primary buttons
    const grid = this.getGrid();
    if (!grid) return; // non-class diagram → no drag
    const svg = this.container.querySelector('svg');
    if (!svg) return;
    const g = e.target.closest('g[id]');
    if (!g) return;
    const id = g.id; // DOM already un-escaped &apos;/&amp;/etc.
    const node = this.getNodes().find((n) => n.id === id);
    if (!node) return; // excludes structural #nodes/#edges groups and edges
    const start = clientToUser(svg, e.clientX, e.clientY);
    if (!start) return;

    const ghost = g.cloneNode(true);
    ghost.removeAttribute('id');
    ghost.style.pointerEvents = 'none';
    ghost.classList.add('kuml-drag-ghost');
    ghost.setAttribute('transform', `translate(${node.x},${node.y})`);
    svg.appendChild(ghost); // parent = root → NodeBox coords apply directly
    g.classList.add('kuml-drag-source'); // dim the original

    this.drag = { pointerId: e.pointerId, svg, node, start, ghost, source: g, dx: 0, dy: 0 };
    this.container.setPointerCapture(e.pointerId);
    e.preventDefault();
  }

  onMove(e) {
    const d = this.drag;
    if (!d || e.pointerId !== d.pointerId) return;
    const cur = clientToUser(d.svg, e.clientX, e.clientY);
    if (!cur) return;
    const dx = cur.x - d.start.x;
    const dy = cur.y - d.start.y;
    d.ghost.setAttribute('transform', `translate(${d.node.x + dx},${d.node.y + dy})`);
    d.dx = dx;
    d.dy = dy;
  }

  async onUp(e) {
    const d = this.drag;
    if (!d || e.pointerId !== d.pointerId) return;
    this.drag = null;
    const { node, source, ghost, dx, dy } = d;

    const grid = this.getGrid();
    ghost.remove();
    source.classList.remove('kuml-drag-source');
    this.container.releasePointerCapture(e.pointerId);

    if (!grid) return; // grid vanished mid-drag (e.g. doc changed) — abort quietly

    // Resolve target cell from the node's *center* (not the raw cursor), so
    // the whole box snaps predictably regardless of where inside it the user
    // grabbed.
    const cx = node.x + node.w / 2 + dx;
    const cy = node.y + node.h / 2 + dy;
    const { col, row } = resolveCell(grid, cx, cy);

    // Skip the round-trip if the node did not actually move to a new cell.
    const currentCell = resolveCell(grid, node.x + node.w / 2, node.y + node.h / 2);
    if (currentCell.col === col && currentCell.row === row) return;

    await this.onDrop(node.id, col, row);
  }

  onCancel(e) {
    const d = this.drag;
    if (!d || e.pointerId !== d.pointerId) return;
    this.drag = null;
    d.ghost.remove();
    d.source.classList.remove('kuml-drag-source');
    this.container.releasePointerCapture(e.pointerId);
  }
}

// Posts the resolved (elementId, col, row) drop to /api/layout/hint and, on
// success, replaces the editor doc with the returned re-parseable script. The
// client always sends its *current full editor text* as `script` — the
// stateless-server contract — never a cached server model. On failure the
// editor document is left untouched and the error banner is shown.
async function applyLayoutDrop(id, col, row) {
  const script = editorView.state.doc.toString();
  try {
    const res = await fetch('/api/layout/hint', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ script, elementId: id, col, row }),
    });
    const data = await res.json();
    if (res.ok && data.ok && data.script) {
      editorView.dispatch({
        changes: { from: 0, to: editorView.state.doc.length, insert: data.script },
      });
      // docChanged → existing updateListener → scheduleRender() → /api/render
      // refreshes currentNodes/currentGrid for the next drag. No manual call needed.
    } else {
      showError(data.error || 'Layout hint failed');
    }
  } catch (err) {
    showError(`Layout hint error: ${err.message}`);
  }
}

// ── Init ──────────────────────────────────────────────────────────────────────
(async function init() {
  dragController = new DragController({
    container: previewEl,
    getNodes: () => currentNodes,
    getGrid: () => currentGrid,
    onDrop: applyLayoutDrop,
  });
  await Promise.all([loadExamples(), loadThemes()]);
})();
