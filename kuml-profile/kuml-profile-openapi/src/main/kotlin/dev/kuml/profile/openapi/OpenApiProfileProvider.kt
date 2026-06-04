package dev.kuml.profile.openapi

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileProvider

/**
 * ServiceLoader provider for the OpenAPI profile.
 *
 * Declared in `META-INF/services/dev.kuml.profile.KumlProfileProvider`
 * so that [dev.kuml.profile.ProfileRegistry.loadFromClasspath] discovers it automatically.
 */
internal class OpenApiProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile = openApiProfile
}
