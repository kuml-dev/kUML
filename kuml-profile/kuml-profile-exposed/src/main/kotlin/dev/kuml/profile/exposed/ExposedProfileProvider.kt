package dev.kuml.profile.exposed

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileProvider

/**
 * ServiceLoader provider for the Exposed profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * so that [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers it automatically.
 */
internal class ExposedProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = exposedProfile
}
