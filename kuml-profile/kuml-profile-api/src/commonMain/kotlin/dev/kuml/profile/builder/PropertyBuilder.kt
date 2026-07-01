package dev.kuml.profile.builder

import dev.kuml.profile.StereotypeProperty
import kotlin.reflect.KClass

/** Builder for [StereotypeProperty] instances within the profile DSL. */
@KumlProfileDsl
public class PropertyBuilder<T : Any>
    @PublishedApi
    internal constructor(
        public val name: String,
        public val type: KClass<T>,
    ) {
        public var default: T? = null
        public var required: Boolean? = null
        public var min: Int? = null

        @PublishedApi
        internal fun build(): StereotypeProperty<T> =
            StereotypeProperty(
                name = name,
                type = type,
                default = default,
                required = required ?: (default == null),
                min = min,
            )
    }
