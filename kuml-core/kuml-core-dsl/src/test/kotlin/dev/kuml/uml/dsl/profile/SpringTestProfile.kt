package dev.kuml.uml.dsl.profile

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/**
 * Test fixture — a minimal Spring profile with a `Service` stereotype that
 * collides with [javaEeTestProfile]'s `Service` stereotype.
 * Used for ambiguity and qualified-form tests.
 */
internal val springTestProfile: KumlProfile =
    profile("Spring") {
        namespace = "dev.kuml.test.profiles.spring"
        stereotype("Service") {
            extends(UmlMetaclass.Class)
            property<Boolean>("singleton") { default = true }
        }
        stereotype("Repository") {
            extends(UmlMetaclass.Class)
        }
    }
