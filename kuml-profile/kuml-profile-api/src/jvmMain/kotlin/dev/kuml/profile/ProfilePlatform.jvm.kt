package dev.kuml.profile

import java.util.ServiceLoader
import kotlin.reflect.KClass

internal actual fun discoverProfileProviders(): List<KumlProfileProvider> = ServiceLoader.load(KumlProfileProvider::class.java).toList()

internal actual fun KClass<*>.isEnumClass(): Boolean = this.java.isEnum

internal actual fun KClass<*>.qualifiedOrSimpleName(): String? = this.qualifiedName ?: this.simpleName
