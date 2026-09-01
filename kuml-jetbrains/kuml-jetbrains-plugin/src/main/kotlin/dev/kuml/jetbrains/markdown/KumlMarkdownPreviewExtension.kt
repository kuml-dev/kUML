package dev.kuml.jetbrains.markdown

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownCodeFence
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import java.util.concurrent.ExecutorService

/**
 * Renders ```` ```kuml ```` Markdown code fences as inline SVG in IntelliJ's built-in
 * (JCEF) Markdown preview.
 *
 * ## Why this replaces `KumlMarkdownCodeFenceProvider`
 *
 * `CodeFenceGeneratingProvider.generateHtml` — the previous approach — is
 * `@ApiStatus.Internal` (and `@ApiStatus.Obsolete` as of IDE build 262). Every use
 * fails the JetBrains Marketplace's Plugin Verifier with an `INTERNAL_API_USAGES`
 * problem, which blocks publishing outright (verified empirically against all
 * six `recommended()` IDE builds — see the plan behind this change).
 *
 * `MarkdownBrowserPreviewExtension` is the sanctioned, non-internal replacement — but
 * it does **not** generate per-fence HTML; it only injects JS/CSS resources into the
 * preview page (see [scripts], [resourceProvider]). So the standard
 * `<pre><code class="language-kuml">...</code></pre>` fence stays in the DOM, and the
 * bundled bridge script (`kuml-markdown-preview.js`) finds it, sends its source over
 * to the IDE via [BrowserPipe], and swaps in the rendered HTML this class returns.
 *
 * ## Threading
 *
 * [onRequest] runs on the JCEF message-pipe callback thread. Resolving the fence's
 * exact info string (theme/name/width attributes may not have made it into the DOM's
 * `class` attribute) needs a [ReadAction] over the PSI; actually rendering shells out
 * to the external `kuml` CLI ([dev.kuml.jetbrains.KumlPreviewRenderer]), which blocks.
 * Both must happen off the pipe-callback thread and off the EDT — see the bounded
 * `renderExecutor` below (not [ApplicationManager]'s unbounded pooled-thread executor,
 * which would let one document's worth of fences spawn unboundedly many concurrent
 * `kuml` CLI subprocesses).
 *
 * @param pipe the panel's [BrowserPipe], or `null` if the panel doesn't have JCEF —
 *   degrade to a no-op rather than throwing, since `getBrowserPipe()` may return null.
 * @param fenceLookup resolves a fence's DOM order index ("ordinal", 0-based, in
 *   document order) plus the source text the browser sent along, to its
 *   `(infoString, source)` pair via the PSI. Injected (rather than looking up `panel`
 *   directly) so this class is unit-testable without JCEF/PSI. The source text is used
 *   to verify (and, on mismatch, recover) the ordinal — see [Provider.fenceLookupFor].
 */
class KumlMarkdownPreviewExtension internal constructor(
    private val pipe: BrowserPipe?,
    private val fenceLookup: (Int, String) -> Pair<String, String>?,
) : MarkdownBrowserPreviewExtension,
    ResourceProvider {
    // NOTE: a plain Java class (KumlBrowserPipeRequestHandler), not a Kotlin
    // `object : BrowserPipe.Handler { ... }` expression — see that class's kdoc for why.
    // In short: Kotlin synthesizes a forwarding override for the untouched
    // `messageReceived` default method even when only `processMessageReceived` is
    // overridden, and that synthetic override breaks on IDE build 262+ (where
    // `messageReceived` no longer exists at all) — verified empirically via `javap`.
    private val handler = KumlBrowserPipeRequestHandler { data -> onRequest(data) }

    init {
        pipe?.subscribe(KumlMarkdownPreviewProtocol.REQUEST_EVENT, handler)
    }

    override val priority: MarkdownBrowserPreviewExtension.Priority
        get() = MarkdownBrowserPreviewExtension.Priority.DEFAULT

    override val scripts: List<String>
        get() = listOf(BRIDGE_SCRIPT)

    override val resourceProvider: ResourceProvider
        get() = this

    override fun canProvide(resourceName: String): Boolean = resourceName == BRIDGE_SCRIPT

    override fun loadResource(resourceName: String): ResourceProvider.Resource? {
        if (resourceName != BRIDGE_SCRIPT) return null
        return ResourceProvider.loadInternalResource(
            KumlMarkdownPreviewExtension::class.java,
            "/kuml/$BRIDGE_SCRIPT",
            "text/javascript",
        )
    }

    override fun dispose() {
        pipe?.removeSubscription(KumlMarkdownPreviewProtocol.REQUEST_EVENT, handler)
    }

    /** Package-visible for [KumlMarkdownPreviewExtensionTest]. */
    internal fun onRequest(payload: String) {
        val request = KumlMarkdownPreviewProtocol.decodeRequest(payload) ?: return
        // getApplication() can be null outside a running IDE (e.g. this class's own plain
        // Kotest unit tests, which deliberately run without the IntelliJ Platform test
        // framework — see build.gradle.kts) — degrade to a no-op rather than NPE-ing.
        ApplicationManager.getApplication() ?: return
        // Bounded [renderExecutor] (not executeOnPooledThread, which is an unbounded
        // shared pool) — each fence render shells out to the external `kuml` CLI and
        // blocks up to KumlCliRenderer's 30s timeout. A Markdown document with N kuml
        // fences (this repo's own vault-example docs have dozens) would otherwise launch
        // N concurrent JVM subprocesses the moment the preview opens — an unbounded-fanout
        // DoS against the user's own machine (CLAUDE.md's security checklist requires
        // exactly this kind of cap). On master, fences rendered sequentially within one
        // HTML-generation pass; the bounded executor restores that same bound without
        // serializing renders across different open previews entirely.
        renderExecutor.execute {
            // Every accepted request MUST produce exactly one response, success or not.
            // The bridge script marks a fence as in-flight (`data-kuml-pending`) and
            // skips it on later passes while the in-flight request still matches its
            // content — so a silently dropped response wedges that fence's preview
            // permanently (blank, no retry, no error shown) until its text changes.
            // Rendering an error container is strictly better than showing nothing.
            val html =
                try {
                    val resolved =
                        try {
                            fenceLookup(request.ordinal, request.fallbackSource)
                        } catch (_: Throwable) {
                            null
                        }
                    val (infoString, source) = resolved ?: ("kuml" to request.fallbackSource)
                    KumlMarkdownFenceHtml.render(infoString, source)
                } catch (throwable: Throwable) {
                    KumlMarkdownFenceHtml.renderError(throwable)
                }
            try {
                val responsePayload = KumlMarkdownPreviewProtocol.encodeResponse(request.requestId, request.ordinal, html)
                pipe?.send(KumlMarkdownPreviewProtocol.RESPONSE_EVENT, responsePayload)
            } catch (_: Throwable) {
                // The pipe itself is gone (panel disposed mid-render) — nothing left to
                // deliver to, and nothing that may take down the shared pooled executor.
            }
        }
    }

    class Provider : MarkdownBrowserPreviewExtension.Provider {
        override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension {
            val extension = KumlMarkdownPreviewExtension(panel.browserPipe, fenceLookupFor(panel))
            Disposer.register(panel, extension)
            return extension
        }

        /**
         * Resolves fence #[ordinal] (0-based, DOM/PSI document order) to its
         * `(infoString, source)` pair by walking the panel's PSI file. Returns `null`
         * when the panel has no resolvable project/file (e.g. a Scratch buffer, or the
         * file was closed between the browser's request and this lookup) or when no
         * matching fence can be found at all — the caller falls back to the raw fence
         * text the browser sent along.
         *
         * The actual ordinal-vs-content resolution (including the content-verified
         * fallback that recovers from a JS/PSI ordinal mismatch — see the MAJOR finding
         * this guards against) lives in [KumlMarkdownFenceInfo.resolveFence], a plain
         * function over lists/strings that can be unit-tested without PSI/[ReadAction];
         * this method's only job is gathering the PSI-side `(infoString, source)` list
         * to hand it.
         */
        private fun fenceLookupFor(panel: MarkdownHtmlPanel): (Int, String) -> Pair<String, String>? =
            { ordinal, browserSource ->
                try {
                    ReadAction.compute<Pair<String, String>?, Throwable> {
                        val project = panel.project
                        val vFile = panel.virtualFile
                        if (project == null || vFile == null) {
                            return@compute null
                        }
                        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute null
                        val allFences = PsiTreeUtil.findChildrenOfType(psiFile, MarkdownCodeFence::class.java)
                        val fences =
                            allFences
                                .filter { KumlMarkdownFenceInfo.isKumlFence(it.fenceLanguage.orEmpty()) }
                                .map { it.fenceLanguage.orEmpty() to KumlMarkdownFenceInfo.extractFenceContent(it) }
                        KumlMarkdownFenceInfo.resolveFence(fences, ordinal, browserSource)
                    }
                } catch (_: Throwable) {
                    null
                }
            }
    }

    companion object {
        private const val BRIDGE_SCRIPT = "kuml-markdown-preview.js"

        /**
         * Caps concurrent `kuml` CLI subprocesses spawned by fence-render requests
         * across ALL open Markdown previews (shared, not per-document) — see the
         * DoS-shaped finding this guards against in [onRequest]'s kdoc.
         */
        private const val MAX_CONCURRENT_RENDERS = 2

        // Platform-managed (not a raw `Executors.newFixedThreadPool`, which would need
        // its own explicit shutdown that this class never had a lifecycle hook to call
        // from — see the MINOR finding this fixes). `AppExecutorUtil`'s bounded pool is
        // owned by the IDE application, not this plugin's classloader, so it doesn't
        // pin the plugin's classes/threads alive across a dynamic plugin
        // unload/upgrade — the exact leak the finding described. Same concurrency cap,
        // no `dispose()`-side shutdown needed.
        private val renderExecutor: ExecutorService =
            AppExecutorUtil.createBoundedApplicationPoolExecutor(
                "kUML Markdown preview",
                MAX_CONCURRENT_RENDERS,
            )
    }
}
