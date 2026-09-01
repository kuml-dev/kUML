package dev.kuml.jetbrains.markdown;

import org.intellij.plugins.markdown.ui.preview.BrowserPipe;
import org.jetbrains.annotations.NotNull;

/**
 * Plain Java implementation of {@link BrowserPipe.Handler}, used by {@link KumlMarkdownPreviewExtension}.
 *
 * <p>This has to be a Java class, not a Kotlin {@code object : BrowserPipe.Handler { ... }} expression.
 * {@code BrowserPipe.Handler} declares two JVM {@code default} methods:
 * {@code processMessageReceived} (the one we implement) and the deprecated
 * {@code messageReceived} (removed entirely on IDE build 262+). When a Kotlin anonymous
 * object implements this interface and overrides only {@code processMessageReceived},
 * the Kotlin compiler still synthesizes a forwarding override for the untouched
 * {@code messageReceived} default method, compiled as an {@code invokespecial} call
 * back into the interface's own default implementation — verified empirically via
 * {@code javap -c} on the compiled anonymous class. The JetBrains Marketplace Plugin
 * Verifier correctly flags that synthesized call as an unresolved-method compatibility
 * problem on any IDE build where {@code messageReceived} no longer exists (a real
 * {@code NoSuchMethodError} risk at runtime), even though the Kotlin source never
 * mentions the method. A plain Java implementation does not have this quirk: Java
 * simply inherits whichever default methods it doesn't override, with no synthetic
 * bridge generated — exactly the behavior this interop point needs.
 */
final class KumlBrowserPipeRequestHandler implements BrowserPipe.Handler {
    /** Single-abstract-method callback, usable as a Kotlin lambda via SAM conversion. */
    interface Callback {
        void onRequest(@NotNull String data);
    }

    private final Callback callback;

    KumlBrowserPipeRequestHandler(@NotNull Callback callback) {
        this.callback = callback;
    }

    @Override
    public boolean processMessageReceived(@NotNull String data) {
        callback.onRequest(data);
        return true;
    }
}
