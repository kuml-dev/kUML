package dev.kuml.profile.autosar

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileProvider

/**
 * ServiceLoader provider for the AUTOSAR Classic profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * so that [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers it automatically.
 */
internal class AutosarProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = autosarProfile
}

/**
 * ServiceLoader provider for the AUTOSAR Adaptive Platform profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * alongside [AutosarProfileProvider] so that
 * [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers both profiles.
 *
 * V3.1.35 — initial implementation.
 */
internal class AutosarAdaptiveProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = autosarAdaptiveProfile
}
