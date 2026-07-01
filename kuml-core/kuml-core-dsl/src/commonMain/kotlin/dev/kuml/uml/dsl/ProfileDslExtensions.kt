package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlStereotype
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.toTagValue
import dev.kuml.uml.dsl.profile.StereotypeResolution
import dev.kuml.uml.dsl.profile.StereotypeValidator

// ── applyProfile ─────────────────────────────────────────────────────────────

/**
 * Applies a [KumlProfile] to this container scope.
 *
 * Call this at diagram or model root level before using [stereotype] on any element.
 *
 * ```kotlin
 * diagram("Domain", DiagramType.CLASS) {
 *     applyProfile(javaEeProfile)
 *
 *     classOf("User") {
 *         stereotype("Entity") { "tableName" to "users" }
 *     }
 * }
 * ```
 */
fun UmlContainerScope.applyProfile(profile: KumlProfile) {
    addAppliedProfile(profile)
}

/**
 * Looks up a profile by [namespace] in [ProfileRegistry] and applies it.
 *
 * @throws IllegalStateException if no profile with the given namespace is registered.
 */
fun UmlContainerScope.applyProfile(namespace: String) {
    val profile =
        ProfileRegistry.get(namespace)
            ?: error(
                "applyProfile('$namespace') failed: no profile registered with this namespace. " +
                    "Registered: ${ProfileRegistry.all().map { it.namespace }}",
            )
    addAppliedProfile(profile)
}

// ── stereotype ────────────────────────────────────────────────────────────────

/**
 * Applies a stereotype to the element under construction.
 *
 * The [name] can be unqualified (`"Entity"`) or qualified (`"javaee:Entity"`).
 * Qualified form is required when two applied profiles define stereotypes with the
 * same name (ambiguity resolution, D1).
 *
 * ```kotlin
 * classOf("User") {
 *     stereotype("Entity") {
 *         "tableName" to "users"
 *         "schema"    to "auth"
 *     }
 * }
 * ```
 */
fun UmlElementScope.stereotype(
    name: String,
    block: StereotypeApplicationBuilder.() -> Unit = {},
) {
    val resolved =
        StereotypeResolution.resolve(
            rawName = name,
            appliedProfiles = container.appliedProfiles(),
            elementMetaclass = metaclass,
        )
    val builder = StereotypeApplicationBuilder(resolved.profile.namespace, resolved.stereotype)
    builder.block()
    val app = builder.build()
    StereotypeValidator.validateBuildTime(
        stereotype = resolved.stereotype,
        application = app,
        elementMetaclass = metaclass,
    )
    addStereotype(app)
}

/**
 * Convenience for applying multiple stereotypes without configuration blocks.
 *
 * ```kotlin
 * classOf("Order") {
 *     applyStereotypes("Entity", "OrderLogic")
 * }
 * ```
 */
fun UmlElementScope.applyStereotypes(vararg names: String) {
    names.forEach { stereotype(it) }
}

// ── StereotypeApplicationBuilder ──────────────────────────────────────────────

/**
 * Builder for the tagged-value block inside a [stereotype] call.
 *
 * ```kotlin
 * stereotype("Entity") {
 *     "tableName" to "users"
 *     "schema"    to "auth"
 *     "cacheable" to true
 * }
 * ```
 */
@KumlDsl
class StereotypeApplicationBuilder internal constructor(
    val profileNamespace: String,
    val stereotype: KumlStereotype,
) {
    private val rawTags = mutableMapOf<String, Any?>()

    /** Infix form for tag assignments: `"tableName" to "users"`. */
    infix fun String.to(value: Any?) {
        rawTags[this] = value
    }

    internal fun build(): KumlStereotypeApplication =
        KumlStereotypeApplication(
            profileNamespace = profileNamespace,
            stereotypeName = stereotype.name,
            tags = rawTags.mapValues { (_, v) -> v.toTagValue() },
        )
}
