package dev.kuml.langsupport.completion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class KumlCompletionItemsTest :
    FunSpec({

        test("ALL has unique names") {
            val names = KumlCompletionItems.ALL.map { it.name }
            names.size shouldBe names.toSet().size
        }

        test("byName returns item for known name classOf") {
            val item = KumlCompletionItems.byName("classOf")
            item.shouldNotBeNull()
            item.name shouldBe "classOf"
            item.group shouldBe KumlCompletionItems.Group.UML
        }

        test("byName returns null for unknown name") {
            KumlCompletionItems.byName("notADslFunction").shouldBeNull()
        }

        test("startingWith is case-insensitive") {
            val lower = KumlCompletionItems.startingWith("class")
            val upper = KumlCompletionItems.startingWith("CLASS")
            lower.map { it.name }.toSet() shouldBe upper.map { it.name }.toSet()
            lower.isNotEmpty().shouldBeTrue()
        }

        test("every item has non-blank insertText") {
            KumlCompletionItems.ALL.forEach { item ->
                item.insertText.shouldNotBeBlank()
            }
        }

        test("ENTRY items contain newline placeholder") {
            val entryItems = KumlCompletionItems.ALL.filter { it.group == KumlCompletionItems.Group.ENTRY }
            // Items that have a lambda block should contain a newline in their insert text
            val lambdaItems = entryItems.filter { it.insertText.contains("{") }
            lambdaItems.forEach { item ->
                item.insertText.contains("\n").shouldBeTrue()
            }
        }

        test("umlModel is in ENTRY group") {
            val item = KumlCompletionItems.byName("umlModel")
            item.shouldNotBeNull()
            item.group shouldBe KumlCompletionItems.Group.ENTRY
        }

        test("SysML2 partDef has correct tail") {
            val item = KumlCompletionItems.byName("partDef")
            item.shouldNotBeNull()
            item.tail shouldBe "(name: String) { … }"
            item.group shouldBe KumlCompletionItems.Group.SYSML2
        }

        test("ALL list has expected count of 38 items") {
            KumlCompletionItems.ALL shouldHaveSize 38
        }

        test("startingWith prefix 'sys' returns sysml2Model") {
            val results = KumlCompletionItems.startingWith("sys")
            results.any { it.name == "sysml2Model" }.shouldBeTrue()
        }

        test("C4 group items include person and softwareSystem") {
            val c4Items = KumlCompletionItems.ALL.filter { it.group == KumlCompletionItems.Group.C4 }
            c4Items.any { it.name == "person" }.shouldBeTrue()
            c4Items.any { it.name == "softwareSystem" }.shouldBeTrue()
        }

        test("SHARED group items include typeRef and literal") {
            val sharedItems = KumlCompletionItems.ALL.filter { it.group == KumlCompletionItems.Group.SHARED }
            sharedItems.any { it.name == "typeRef" }.shouldBeTrue()
            sharedItems.any { it.name == "literal" }.shouldBeTrue()
        }
    })
