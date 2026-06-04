package dev.kuml.profile.autosar

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileProvider

/**
 * ServiceLoader provider for the AUTOSAR profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * so that [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers it automatically.
 */
internal class AutosarProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = autosarProfile
}
