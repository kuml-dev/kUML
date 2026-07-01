package dev.kuml.profile

/**
 * SPI for registering profiles via [java.util.ServiceLoader].
 *
 * Implementations live in profile modules (e.g. `kuml-profile-soaml`) and
 * declare themselves via
 * `META-INF/services/dev.kuml.profile.KumlProfileProvider`.
 *
 * The [ProfileRegistry] discovers and loads them via [ProfileRegistry.loadFromClasspath].
 */
public interface KumlProfileProvider {
    public val profile: KumlProfile
}
