package dev.kuml.profile

import dev.kuml.profile.builder.profile

internal class TestProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile =
        profile("TestProfile") {
            namespace = "dev.kuml.test.profile"
            stereotype("TestStereotype") { extends(UmlMetaclass.Class) }
        }
}
