package dev.kuml.profile

import dev.kuml.profile.builder.profile

internal class TestProfileProvider : KumlProfileProvider {
    override val profile: KumlProfile =
        profile(name = "TestProfile") {
            namespace = "dev.kuml.test.profile"
            stereotype(name = "TestStereotype") { extends(UmlMetaclass.Class) }
        }
}
