package dev.kuml.profile

import kotlin.reflect.KClass

/**
 * Discover [KumlProfileProvider] instances available to this platform.
 *
 * On the JVM this delegates to [java.util.ServiceLoader] (classpath discovery).
 * Classpath/module discovery is a JVM-only concept — on JS and Wasm there is no
 * equivalent, so those platforms return an empty list. Profile registration on
 * JS/Wasm is expected to happen explicitly via [ProfileRegistry.register].
 */
internal expect fun discoverProfileProviders(): List<KumlProfileProvider>

/**
 * Platform-specific check whether [this] [KClass] represents an `enum class`.
 *
 * On the JVM this delegates to `java.lang.Class.isEnum` (exact, reflection-based).
 * On JS/Wasm there is no generic reflection-based enum check available for an
 * arbitrary [KClass], so this conservatively returns `false` there — meaning
 * [StereotypeProperty] validation is strict on JVM and lax (permissive) for
 * enum types on JS/Wasm. This does not change any JVM-observable behavior.
 */
internal expect fun KClass<*>.isEnumClass(): Boolean

/**
 * Platform-specific "best available name" for [this] class.
 *
 * `KClass.qualifiedName` is not supported by the Kotlin/JS reflection API (only
 * `simpleName` is universally available in common code). On the JVM this
 * delegates to `qualifiedName` (falling back to `simpleName`) to preserve the
 * pre-KMP behavior exactly; on JS/Wasm it uses `simpleName` directly.
 */
internal expect fun KClass<*>.qualifiedOrSimpleName(): String?
