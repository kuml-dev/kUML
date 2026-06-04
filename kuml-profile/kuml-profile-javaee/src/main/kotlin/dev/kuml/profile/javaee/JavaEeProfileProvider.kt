package dev.kuml.profile.javaee

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileProvider

/**
 * ServiceLoader provider for the JavaEE profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * so that [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers it automatically.
 */
internal class JavaEeProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = javaEeProfile
}
