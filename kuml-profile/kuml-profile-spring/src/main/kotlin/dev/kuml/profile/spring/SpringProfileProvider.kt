package dev.kuml.profile.spring

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileProvider

/**
 * ServiceLoader provider for the Spring profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * so that [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers it automatically.
 *
 * Note: Spring depends on JavaEE via `api`. When both are on the classpath,
 * JavaEE is discovered first (alphabetical order of provider classes is
 * implementation-defined — tests must register both profiles or call
 * loadFromClasspath() with both on the classpath).
 */
internal class SpringProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = springProfile
}
