package dev.kuml.profile

import dev.kuml.uml.TagValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class StereotypeApplicationTest :
    StringSpec({

        "application stores tags as map" {
            val tags =
                mapOf<String, TagValue>(
                    "tableName" to TagValue.StringVal("users"),
                    "cacheable" to TagValue.BoolVal(true),
                )
            val app =
                KumlStereotypeApplication(
                    profileNamespace = "dev.example",
                    stereotypeName = "Entity",
                    tags = tags,
                )
            app.tags shouldContainKey "tableName"
            app.tags["tableName"] shouldBe TagValue.StringVal("users")
            app.tags["cacheable"] shouldBe TagValue.BoolVal(true)
        }

        "data-class equality for two applications with same tags" {
            val tags = mapOf<String, TagValue>("x" to TagValue.IntVal(42))
            val a1 = KumlStereotypeApplication("ns", "St", tags)
            val a2 = KumlStereotypeApplication("ns", "St", tags)
            a1 shouldBe a2
        }

        "blank namespace throws in init" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    KumlStereotypeApplication(
                        profileNamespace = "",
                        stereotypeName = "Entity",
                    )
                }
            ex.message shouldContain "profileNamespace must not be blank"
        }

        "blank stereotypeName throws in init" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    KumlStereotypeApplication(
                        profileNamespace = "dev.example",
                        stereotypeName = "",
                    )
                }
            ex.message shouldContain "stereotypeName must not be blank"
        }

        "toTagValue converts String" {
            val tv = "hello".toTagValue()
            tv shouldBe TagValue.StringVal("hello")
        }

        "toTagValue converts Int" {
            val tv = 42.toTagValue()
            tv shouldBe TagValue.IntVal(42)
        }

        "toTagValue converts Boolean" {
            val tv = true.toTagValue()
            tv shouldBe TagValue.BoolVal(true)
        }

        "toTagValue converts List of primitives" {
            val tv = listOf("a", "b").toTagValue()
            tv shouldBe TagValue.ListVal(listOf(TagValue.StringVal("a"), TagValue.StringVal("b")))
        }
    })
