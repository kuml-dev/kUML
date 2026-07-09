package dev.kuml.profile.erm

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileProvider

/**
 * ServiceLoader provider for the ERM mapping profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * so that [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers it automatically.
 */
internal class ErmMappingProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = ermMappingProfile
}
