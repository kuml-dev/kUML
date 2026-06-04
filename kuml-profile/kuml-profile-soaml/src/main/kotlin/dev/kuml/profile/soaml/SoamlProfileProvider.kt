package dev.kuml.profile.soaml

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileProvider

/**
 * ServiceLoader provider for the SoaML profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * so that [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers it automatically.
 */
internal class SoamlProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = soamlProfile
}
