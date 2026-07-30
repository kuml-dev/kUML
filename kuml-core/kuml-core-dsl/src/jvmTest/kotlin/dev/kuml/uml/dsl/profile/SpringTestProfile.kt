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
    profile(name = "Spring") {
        namespace = "dev.kuml.test.profiles.spring"
        stereotype(name = "Service") {
            extends(UmlMetaclass.Class)
            property<Boolean>(name = "singleton") { default = true }
        }
        stereotype(name = "Repository") {
            extends(UmlMetaclass.Class)
        }
    }
