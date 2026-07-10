package dev.kuml.lsp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class DocumentStoreTest :
    FunSpec({

        test("update then text returns the stored text") {
            val store = DocumentStore()
            store.update("file:///a.kuml.kts", "class Foo")
            store.text("file:///a.kuml.kts") shouldBe "class Foo"
        }

        test("overwriting an existing uri updates the stored text") {
            val store = DocumentStore()
            store.update("file:///a.kuml.kts", "class Foo")
            store.update("file:///a.kuml.kts", "class Bar")
            store.text("file:///a.kuml.kts") shouldBe "class Bar"
        }

        test("remove makes text return null") {
            val store = DocumentStore()
            store.update("file:///a.kuml.kts", "class Foo")
            store.remove("file:///a.kuml.kts")
            store.text("file:///a.kuml.kts").shouldBeNull()
        }

        test("uris reflects current membership") {
            val store = DocumentStore()
            store.update("file:///a.kuml.kts", "a")
            store.update("file:///b.kuml.kts", "b")
            store.uris() shouldBe setOf("file:///a.kuml.kts", "file:///b.kuml.kts")

            store.remove("file:///a.kuml.kts")
            store.uris() shouldBe setOf("file:///b.kuml.kts")
        }

        test("text for an unknown uri returns null") {
            val store = DocumentStore()
            store.text("file:///missing.kuml.kts").shouldBeNull()
        }
    })
