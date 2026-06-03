package dev.kuml.uml.dsl.profile

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/** Test fixture — a minimal JavaEE-style profile for unit tests. */
internal val javaEeTestProfile: KumlProfile =
    profile("JavaEE") {
        namespace = "dev.kuml.test.profiles.javaee"
        stereotype("Entity") {
            extends(UmlMetaclass.Class)
            property<String>("tableName") // required (no default)
            property<String>("schema") { default = "public" }
        }
        stereotype("Service") {
            extends(UmlMetaclass.Class)
            property<Boolean>("transactional") { default = true }
        }
    }
