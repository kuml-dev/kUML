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

  var pending = {};
  var nextRequestSeq = 0;
  var scheduled = false;

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

  function requestRender(entry) {
    var requestId = "r" + Date.now() + "-" + nextRequestSeq++;
    entry.codeEl.setAttribute("data-kuml-pending", requestId);
    pending[requestId] = entry;

    var payload = [requestId, String(entry.ordinal), base64EncodeUtf8(entry.source)].join(SEP);
    try {
      window.__IntelliJTools.messagePipe.post(REQUEST_EVENT, payload);
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

    var previous = pre.nextElementSibling;
    if (previous && previous.getAttribute && previous.getAttribute("data-kuml-rendered") === "true") {
      pre.parentNode.replaceChild(container, previous);
    } else {
      pre.parentNode.insertBefore(container, pre.nextSibling);
    }

    entry.codeEl.__kumlRenderedSource = entry.source;
    entry.codeEl.removeAttribute("data-kuml-pending");
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
