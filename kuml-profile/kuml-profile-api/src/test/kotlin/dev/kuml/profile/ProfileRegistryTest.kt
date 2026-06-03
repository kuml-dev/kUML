package dev.kuml.profile

import dev.kuml.profile.builder.profile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class ProfileRegistryTest :
    StringSpec({

        beforeEach { ProfileRegistry.clear() }

        "register and lookup by namespace" {
            val p =
                profile("P") {
                    namespace = "dev.example.p"
                    stereotype("S") { extends(UmlMetaclass.Class) }
                }
            ProfileRegistry.register(p)
            ProfileRegistry.get("dev.example.p") shouldBe p
        }

        "get returns null for unknown namespace" {
            ProfileRegistry.get("dev.unknown") shouldBe null
        }

        "all() returns all registered profiles" {
            val p1 = profile("P1") { namespace = "dev.example.p1" }
            val p2 = profile("P2") { namespace = "dev.example.p2" }
            ProfileRegistry.register(p1)
            ProfileRegistry.register(p2)
            val all = ProfileRegistry.all()
            all shouldHaveSize 2
            all shouldContain p1
            all shouldContain p2
        }

        "clear() empties the registry" {
            val p = profile("P") { namespace = "dev.example.p" }
            ProfileRegistry.register(p)
            ProfileRegistry.clear()
            ProfileRegistry.all() shouldHaveSize 0
            ProfileRegistry.get("dev.example.p") shouldBe null
        }

        "loadFromClasspath finds TestProfileProvider via ServiceLoader" {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get("dev.kuml.test.profile")
            found shouldNotBe null
            found!!.name shouldBe "TestProfile"
            found.stereotype("TestStereotype") shouldNotBe null
        }

        "validateClosure detects unresolved specializes across profiles" {
            val profile =
                profile("Broken") {
                    namespace = "dev.example.broken"
                    extends("dev.example.base") // referenced but not registered
                    stereotype("Child") {
                        extends(UmlMetaclass.Class)
                        specializes = "NonExistentParent"
                    }
                }
            ProfileRegistry.register(profile)
            val ex =
                shouldThrow<IllegalArgumentException> {
                    ProfileRegistry.validateClosure()
                }
            ex.message shouldContain "specializes"
            ex.message shouldContain "NonExistentParent"
        }

        "register multiple stereotypes in one profile and look them up" {
            val p =
                profile("Multi") {
                    namespace = "dev.example.multi"
                    stereotype("A") { extends(UmlMetaclass.Class) }
                    stereotype("B") { extends(UmlMetaclass.Interface) }
                }
            ProfileRegistry.register(p)
            val found = ProfileRegistry.get("dev.example.multi")!!
            found.stereotype("A") shouldNotBe null
            found.stereotype("B") shouldNotBe null
            found.stereotype("C") shouldBe null
        }
    })
