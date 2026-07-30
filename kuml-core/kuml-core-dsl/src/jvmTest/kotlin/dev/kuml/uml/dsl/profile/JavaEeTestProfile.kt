package dev.kuml.uml.dsl.profile

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/** Test fixture — a minimal JavaEE-style profile for unit tests. */
internal val javaEeTestProfile: KumlProfile =
    profile(name = "JavaEE") {
        namespace = "dev.kuml.test.profiles.javaee"
        stereotype(name = "Entity") {
            extends(UmlMetaclass.Class)
            property<String>(name = "tableName") // required (no default)
            property<String>(name = "schema") { default = "public" }
        }
        stereotype(name = "Service") {
            extends(UmlMetaclass.Class)
            property<Boolean>(name = "transactional") { default = true }
        }
    }
