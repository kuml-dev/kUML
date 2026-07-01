package dev.kuml.profile

import kotlin.reflect.KClass

// Classpath discovery is a JVM-only concept — no ServiceLoader equivalent on Wasm.
// Profiles are registered explicitly via ProfileRegistry.register() on this platform.
internal actual fun discoverProfileProviders(): List<KumlProfileProvider> = emptyList()

// No generic reflection-based enum check available for an arbitrary KClass on Wasm.
// Conservative fallback: treat as "not an enum" here, ALLOWED_TYPES check still applies.
internal actual fun KClass<*>.isEnumClass(): Boolean = false

internal actual fun KClass<*>.qualifiedOrSimpleName(): String? = this.simpleName
