// kUML Markdown preview bridge.
//
// The IntelliJ Markdown preview no longer lets us generate custom HTML for a fence
// directly (that hook, CodeFenceGeneratingProvider, is @ApiStatus.Internal and blocks
// the JetBrains Marketplace verifier). Instead we render the standard
// `<pre><code class="language-kuml">...</code></pre>` fence and replace it client-side:
// this script finds those fences, asks the IDE (over the JCEF message pipe, see
// BrowserPipe.js) to render each one via the kUML CLI, and swaps in the returned HTML.
//
// No external URLs, no eval — everything here is self-contained and the rendered HTML
// itself is sanitized JVM-side (KumlPreviewHtml.sanitizeSvg) before it ever reaches
// innerHTML.
"use strict";

(function () {
  var REQUEST_EVENT = "kuml.markdown.render.request";
  var RESPONSE_EVENT = "kuml.markdown.render.response";
  var SEP = String.fromCharCode(0x1f);

  // How long a fence sits behind a blank, size-stable placeholder before the "still
  // rendering" hint text appears. Short enough that a genuinely slow render (a large
  // diagram, a cold `kuml` CLI JVM start) doesn't look stuck; long enough that the
  // common case — a small diagram rendering in well under 150ms — never flashes any
  // text at all, just holds its reserved height for an instant. No spinner, no
  // animation: a static line of text is the whole "loading" affordance.
  var PLACEHOLDER_REVEAL_DELAY_MS = 150;
  // Height reserved for a fence that has never rendered successfully in this preview
  // session yet, so there is SOME size-stable box to show instead of the raw <pre>
  // even on the very first render. Deliberately a rough "typical small diagram" guess,
  // not a promise — the point is only to avoid the raw-text-to-large-SVG jump, not to
  // predict the exact upcoming height.
  var DEFAULT_PLACEHOLDER_HEIGHT_PX = 240;

  var pending = {};
  var nextRequestSeq = 0;
  var scheduled = false;
  // codeEl -> px, the height of the LAST successful (kuml-diagram-container) render of
  // that exact fence. Never set from an error/empty container — those don't tell us
  // anything about how tall a real diagram for this fence would be, and letting them
  // overwrite this map would size the placeholder to the WRONG kind of content the
  // next time the same fence re-renders after being fixed.
  var lastRenderedHeightByElement = new WeakMap();
  // codeEl -> setTimeout handle for the pending "reveal the hint text" timer, so
  // onResponse() can cancel it if the real render lands before PLACEHOLDER_REVEAL_DELAY_MS
  // elapses (otherwise the hint text would flash onto a placeholder that's about to be
  // torn out immediately after).
  var pendingRevealTimers = new WeakMap();

  function base64EncodeUtf8(text) {
    var bytes = new TextEncoder().encode(text);
    var binary = "";
    for (var i = 0; i < bytes.length; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
  }

  function base64DecodeUtf8(base64) {
    var binary = atob(base64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return new TextDecoder("utf-8").decode(bytes);
  }

  // IntelliJ's DefaultCodeFenceGeneratingProvider does NOT preserve the fence's info
  // string verbatim in the `class` attribute. It rebuilds it as:
  //
  //   "language-" + fenceLang.trim().split(" ").joinToString("-")
  //
  // (verified against the IDE's own intellij.markdown.jar bytecode across builds
  // 243/251/252/253/261/262 — see the review finding this fixed). So a fence written
  // as ```` ```kuml {theme="plain" name="order"} ```` ends up with class
  // `language-kuml-{theme="plain"-name="order"}`, and ```` ```kuml theme=plain name=order ````
  // ends up with `language-kuml-theme=plain-name=order` — never the bare
  // `language-kuml` an exact/word-boundary match would require. A naive exact-match
  // check therefore never recognizes an attributed fence at all, which both hides the
  // diagram AND shifts every subsequent fence's ordinal (see
  // KumlMarkdownPreviewExtension.Provider.fenceLookupFor, which filters PSI fences via
  // KumlMarkdownFenceInfo.isKumlFence and DOES count attributed fences) — the two sides
  // must agree on which fences count, or fence N's diagram request can render fence M's
  // source under fence N's theme/name.
  //
  // A bare `kuml{...}` (no space before the brace) keeps the brace glued directly onto
  // the "kuml" token (no join-inserted "-"); a bare `kuml-custom` (an unrelated,
  // hypothetical fence language that merely starts with "kuml-") must NOT match — see
  // KumlMarkdownFenceInfoTest for the Kotlin-side equivalent of that same guard. Braced
  // and key=value attribute forms are distinguishable from that case because the
  // attribute content always contributes a "{" or an "=" right after the token.
  //
  // Two residual divergences from `KumlMarkdownFenceInfo.isKumlFence` survived the
  // first fix (see the MAJOR finding this addresses):
  //
  // (a) `kuml <token>` with NEITHER `=` nor `{` — e.g. `kuml diagram1`, and crucially
  //     every intermediate state while typing an attribute (`kuml t`, `kuml th`, ...,
  //     `kuml theme`, all BEFORE the `=` lands). The PSI side's isKumlFence is true for
  //     all of these (anything starting with "kuml" + a space/tab/`{`/end boundary).
  //     The rebuilt class collapses to `language-kuml-<token>` — but a REAL, unrelated
  //     sibling fence language that merely starts with "kuml-" (`kuml-animated`,
  //     `kuml-include`; see the fence-language grep counts in the finding this
  //     addresses) collapses to the EXACT SAME shape, because the class rebuild turns
  //     both an original space and an original literal "-" into the same joining "-" —
  //     that boundary information is already gone by the time this script sees the
  //     class string. There is no way to tell "kuml theme" (space) apart from
  //     "kuml-theme" (hyphen) from the class alone in the general case, so this script
  //     resolves the ambiguity with a small, explicit exclusion list of the sibling
  //     fence-language names actually in use (kept in sync with CLAUDE.md's kUML-Repo-
  //     Konventionen — grep the repo/vault for other "kuml-"-prefixed fence languages
  //     before adding a new one there without updating this list too). Anything else
  //     that shows up as a single bareword segment right after "kuml-" is treated as a
  //     (possibly still-being-typed) kuml-fence attribute token.
  //
  //     This is a heuristic, not a proof — a brand-new "kuml-<word>" sibling language
  //     not yet added below could still be misdetected. `KumlMarkdownPreviewExtension.
  //     Provider.fenceLookupFor`'s content-verified fallback (see its kdoc) is the
  //     actual safety net against that residual case: even if this script's ordinal
  //     count drifts from the PSI's, the Kotlin side recovers the CORRECT fence by
  //     matching the request's source text instead of trusting the ordinal blindly, so
  //     a leftover misclassification here can cause a harmless double-render attempt,
  //     never the wrong diagram sitting under someone else's fence.
  //
  // (b) `kuml-<sibling> key=value` — e.g. `kuml-animated speed=2` — collapses to
  //     `language-kuml-animated-speed=2`. The old `KUML_ATTR_CLASS_RE` (`/^language-
  //     kuml-.*=/i`) matched because it only asked "does an `=` appear anywhere after
  //     `language-kuml-`", not whether it belongs to the FIRST segment. Requiring the
  //     `=` to appear before any further `-` (`[^-{}]*=`) fixes this structurally, no
  //     exclusion list needed: a real attributed kuml fence always has its first
  //     `key=value` pair glued directly onto "kuml" (`kuml theme=plain` ->
  //     `language-kuml-theme=plain`), while a sibling language's own attribute is
  //     preceded by the sibling's bareword name segment (`animated-speed=2`), which the
  //     tightened regex can no longer skip over.
  var KUML_EXACT_CLASS = "language-kuml";
  var KUML_BRACE_CLASS_RE = /^language-kuml-?\{/i;
  var KUML_ATTR_CLASS_RE = /^language-kuml-[^-{}]*=/i;
  var KUML_BAREWORD_CLASS_RE = /^language-kuml-([^-{}=]+)$/i;
  var KUML_SIBLING_LANG_SUFFIXES = ["animated", "include", "custom"];

  function isKumlClassToken(token) {
    if (token.toLowerCase() === KUML_EXACT_CLASS) {
      return true;
    }
    if (KUML_BRACE_CLASS_RE.test(token)) {
      return true;
    }
    if (KUML_ATTR_CLASS_RE.test(token)) {
      return true;
    }
    var bareMatch = KUML_BAREWORD_CLASS_RE.exec(token);
    if (bareMatch) {
      var suffix = bareMatch[1].toLowerCase();
      return KUML_SIBLING_LANG_SUFFIXES.indexOf(suffix) === -1;
    }
    return false;
  }

  function isKumlFence(codeEl) {
    // classList (not className!) — browsers split the HTML class attribute on any
    // ASCII whitespace (including a literal tab, which `kuml\tname=diag` info strings
    // carry straight into the attribute value since IntelliJ's join only splits on the
    // space character), so classList already gives us the same token boundaries the
    // IDE's own PSI-side fenceLanguage parsing would.
    var classList = codeEl.classList;
    if (!classList) {
      return false;
    }
    for (var i = 0; i < classList.length; i++) {
      if (isKumlClassToken(classList[i])) {
        return true;
      }
    }
    return false;
  }

  function collectPending() {
    var codeBlocks = document.querySelectorAll("pre > code");
    var ordinal = -1;
    var found = [];
    for (var i = 0; i < codeBlocks.length; i++) {
      var codeEl = codeBlocks[i];
      if (!isKumlFence(codeEl)) {
        continue;
      }
      ordinal++;

      // textContent (not innerHTML!) — MarkdownCodeFencePreviewHighlighter may wrap the
      // fence body in syntax-highlighting <span>s; textContent still returns the raw source.
      var currentSource = codeEl.textContent || "";

      // Already rendered for exactly this content — nothing to do. This check exists
      // because the rendered fence's <pre> deliberately STAYS in the DOM (hidden, see
      // onResponse): without it, every MutationObserver tick would re-request every
      // already-rendered fence forever. Kept as an expando rather than an attribute so
      // multi-KB fence bodies never land in the DOM's attribute table.
      if (codeEl.__kumlRenderedSource === currentSource) {
        continue;
      }

      var pendingRequestId = codeEl.getAttribute("data-kuml-pending");
      if (pendingRequestId) {
        var inFlight = pending[pendingRequestId];
        if (inFlight && inFlight.source === currentSource) {
          // A request for this exact content is already in flight — nothing to do.
          continue;
        }
        // Either the bookkeeping for that request was lost, or (far more commonly)
        // the user kept typing after we sent it: the fence's live content no longer
        // matches what that request asked the CLI to render. Fall through and issue a
        // fresh request for the CURRENT content; requestRender() overwrites
        // data-kuml-pending with the new request id, so the stale response (whenever
        // it arrives) gets recognized as superseded in onResponse() and is discarded
        // instead of overwriting the preview with outdated content.
      }
      found.push({ ordinal: ordinal, codeEl: codeEl, pre: codeEl.parentElement, source: currentSource });
    }
    return found;
  }

  // Shared by insertPlaceholder() and onResponse(): both need to place exactly one
  // element right after `pre` and, if a PREVIOUS such element (an earlier placeholder,
  // or an earlier render from before this fence's last edit) is already sitting there,
  // replace it instead of piling up a second one. Only elements this file itself
  // placed there (marked below) are ever treated as replaceable — anything else
  // (arbitrary surrounding Markdown content) is left alone and `container` is simply
  // inserted right after `pre`.
  function isSwapNode(node) {
    return !!(node && node.getAttribute && (node.getAttribute("data-kuml-rendered") === "true" || node.getAttribute("data-kuml-placeholder") === "true"));
  }

  function swapAfterPre(pre, container) {
    var previous = pre.nextElementSibling;
    if (isSwapNode(previous)) {
      pre.parentNode.replaceChild(container, previous);
    } else {
      pre.parentNode.insertBefore(container, pre.nextSibling);
    }
  }

  // Inserts a blank, size-stable box right after `entry.pre` and hides `entry.pre` —
  // called only once requestRender() has confirmed the render request actually made it
  // onto the message pipe (see requestRender below). Reserves either the height this
  // exact fence's last successful render measured, or DEFAULT_PLACEHOLDER_HEIGHT_PX for
  // a fence that has never rendered yet — so swapping the raw <pre> for the eventual
  // diagram does not also visibly grow/shrink the page under the reader. No spinner: a
  // static hint line fades in after PLACEHOLDER_REVEAL_DELAY_MS, purely so a render that
  // takes long enough to notice doesn't look like nothing is happening; a render that
  // completes before the delay elapses never shows any text at all.
  function insertPlaceholder(entry) {
    var height = lastRenderedHeightByElement.get(entry.codeEl) || DEFAULT_PLACEHOLDER_HEIGHT_PX;

    var placeholder = document.createElement("div");
    placeholder.className = "kuml-diagram-placeholder";
    placeholder.setAttribute("data-kuml-placeholder", "true");
    placeholder.style.minHeight = height + "px";

    swapAfterPre(entry.pre, placeholder);
    entry.pre.style.display = "none";

    var existingTimer = pendingRevealTimers.get(entry.codeEl);
    if (existingTimer) {
      clearTimeout(existingTimer);
    }
    var timer = setTimeout(function () {
      pendingRevealTimers.delete(entry.codeEl);
      // The placeholder may already be gone by now (the real render landed, or a
      // newer edit superseded it with a fresh placeholder of its own) — only touch it
      // if it is still the element actually sitting right after `pre`.
      if (entry.pre.nextElementSibling === placeholder) {
        placeholder.textContent = "Rendering kUML diagram…";
      }
    }, PLACEHOLDER_REVEAL_DELAY_MS);
    pendingRevealTimers.set(entry.codeEl, timer);
  }

  function requestRender(entry) {
    var requestId = "r" + Date.now() + "-" + nextRequestSeq++;
    entry.codeEl.setAttribute("data-kuml-pending", requestId);
    pending[requestId] = entry;

    var payload = [requestId, String(entry.ordinal), base64EncodeUtf8(entry.source)].join(SEP);
    try {
      window.__IntelliJTools.messagePipe.post(REQUEST_EVENT, payload);
      // Reached ONLY after a successful post() — if the pipe itself is broken (catch
      // below), the raw fence source stays visible exactly as before this placeholder
      // was introduced, rather than being hidden behind a placeholder that will now
      // never be replaced.
      insertPlaceholder(entry);
    } catch (error) {
      console.error("kuml-markdown-preview: failed to post render request", error);
    }
  }

  function onResponse(payload) {
    var parts = String(payload).split(SEP);
    if (parts.length !== 3) {
      return;
    }
    var requestId = parts[0];
    var entry = pending[requestId];
    delete pending[requestId];
    if (!entry) {
      return;
    }

    // A newer edit may have superseded this request with a fresh one for the same
    // fence (see collectPending()) — if the element's current pending marker no longer
    // names THIS request, a more recent response is already on the way (or has already
    // landed); applying this stale one would show outdated content with nothing left to
    // correct it. Drop it and let the newer request's response win.
    if (entry.codeEl.getAttribute("data-kuml-pending") !== requestId) {
      return;
    }

    // The reveal timer (if any) belongs to the placeholder this response is about to
    // replace — cancel it so the hint text never flashes onto an element that is about
    // to be torn out, and so the timer's own nextElementSibling re-check never runs
    // against a placeholder slot a LATER request may since have reused.
    var pendingTimer = pendingRevealTimers.get(entry.codeEl);
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingRevealTimers.delete(entry.codeEl);
    }

    var html;
    try {
      html = base64DecodeUtf8(parts[2]);
    } catch (error) {
      console.error("kuml-markdown-preview: failed to decode render response", error);
      // Clear the marker so the next pass retries instead of leaving this fence
      // permanently pending (collectPending() skips a fence whose in-flight request
      // still matches its current content).
      entry.codeEl.removeAttribute("data-kuml-pending");
      return;
    }

    var pre = entry.pre;
    if (!pre || !pre.parentNode) {
      return;
    }

    var container = document.createElement("div");
    container.innerHTML = html;
    container.setAttribute("data-kuml-rendered", "true");

    // The original <pre> is HIDDEN, never removed. The ordinal the browser sends is a
    // count over the live `pre > code` set, while the IDE side counts over the pristine
    // PSI (KumlMarkdownPreviewExtension.Provider.fenceLookupFor). Removing each fence
    // from the DOM as it renders made those two counts diverge by exactly the number of
    // already-rendered fences, so from the second pass onward EVERY ordinal was wrong
    // and the Kotlin side's content-match fallback was load-bearing for correctness —
    // and that fallback cannot resolve a fence whose text the browser has not caught up
    // to yet (mid-typing, the preview DOM lags the PSI by the debounce interval).
    // Keeping the <pre> in place keeps the two counts identical for the whole lifetime
    // of the preview document, which is what makes the ordinal trustworthy at all.
    pre.style.display = "none";
    swapAfterPre(pre, container);

    entry.codeEl.__kumlRenderedSource = entry.source;
    entry.codeEl.removeAttribute("data-kuml-pending");

    // Only a genuinely successful diagram render (a ".kuml-diagram-container" child —
    // see KumlPreviewHtml.buildSvgContainer) tells us anything about how tall THIS
    // fence's diagram actually is. An error or "(Empty kUML diagram)" container's
    // height reflects that container's own unrelated styling, not a preview of the next
    // real render — recording it would size the NEXT placeholder for the wrong kind of
    // content once the underlying error is fixed. Measured a frame later
    // (requestAnimationFrame), matching schedule()'s own idiom elsewhere in this file,
    // so the read happens after the browser has actually laid out the freshly inserted
    // SVG rather than on whatever pre-layout value getBoundingClientRect() would return
    // synchronously right after the innerHTML assignment above.
    if (container.querySelector(".kuml-diagram-container")) {
      requestAnimationFrame(function () {
        lastRenderedHeightByElement.set(entry.codeEl, container.getBoundingClientRect().height);
      });
    }
  }

  function schedule() {
    if (scheduled) {
      return;
    }
    scheduled = true;
    requestAnimationFrame(function () {
      scheduled = false;
      var entries = collectPending();
      for (var i = 0; i < entries.length; i++) {
        requestRender(entries[i]);
      }
    });
  }

  function init() {
    if (!window.__IntelliJTools || !window.__IntelliJTools.messagePipe) {
      // Bridge not ready yet (documentReady event race) — retry shortly.
      setTimeout(init, 50);
      return;
    }
    window.__IntelliJTools.messagePipe.subscribe(RESPONSE_EVENT, onResponse);

    var observer = new MutationObserver(function () {
      schedule();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });

    schedule();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
