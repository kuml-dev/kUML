package dev.kuml.plugin.examples.tsreverse

import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class TypeScriptReversePluginTest :
    FunSpec({

        val plugin = TypeScriptReversePlugin()
        val engine = TypeScriptReverseEngine()

        // ── Plugin descriptor ──────────────────────────────────────────────────

        test("engines() gibt genau eine Engine zurück") {
            plugin.engines() shouldHaveSize 1
        }

        test("engine id ist 'typescript'") {
            engine.id shouldBe "typescript"
        }

        test("descriptor capabilities enthält REVERSE") {
            plugin.descriptor.capabilities shouldContain PluginCapability.REVERSE
        }

        test("descriptor requiredPermissions enthält FS_READ") {
            plugin.descriptor.requiredPermissions shouldContain PluginPermission.FS_READ
        }

        test("descriptor kumlVersionRange enthält 0.12.0") {
            plugin.descriptor.kumlVersionRange.contains(PluginVersion(0, 12, 0)) shouldBe true
        }

        // ── TypeScript parsing ─────────────────────────────────────────────────

        test("parst 'interface Foo {}' → UmlInterface") {
            val elements = engine.parseTypeScriptFile("export interface Foo {}", "test")
            val iface = elements.filterIsInstance<UmlInterface>().firstOrNull()
            iface?.name shouldBe "Foo"
        }

        test("parst 'class Bar {}' → UmlClass") {
            val elements = engine.parseTypeScriptFile("export class Bar {}", "test")
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull()
            cls?.name shouldBe "Bar"
        }

        test("parst 'abstract class Baz {}' → UmlClass(isAbstract=true)") {
            val elements = engine.parseTypeScriptFile("export abstract class Baz {}", "test")
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull()
            cls?.isAbstract shouldBe true
        }

        test("parst 'enum Status { A, B }' → UmlEnumeration mit 2 Literals") {
            val elements = engine.parseTypeScriptFile("export enum Status { A, B }", "test")
            val enum = elements.filterIsInstance<UmlEnumeration>().firstOrNull()
            enum?.name shouldBe "Status"
            enum?.literals?.shouldHaveSize(2)
        }

        test("parst Property 'name: string' → UmlProperty mit Type String") {
            val elements =
                engine.parseTypeScriptFile(
                    "export interface IUser { name: string; }",
                    "test",
                )
            val iface = elements.filterIsInstance<UmlInterface>().first()
            val prop = iface.attributes.firstOrNull { it.name == "name" }
            prop?.type?.name shouldBe "String"
        }

        test("leere TypeScript-Datei ergibt leere Element-Liste") {
            engine.parseTypeScriptFile("", "empty") shouldHaveSize 0
        }

        // ── analyze() ─────────────────────────────────────────────────────────

        test("analyze() mit leerem Verzeichnis gibt ReverseResult.Success zurück") {
            val dir = Files.createTempDirectory("ts-reverse-test")
            val request =
                ReverseRequest(
                    sourceRoots = listOf(dir),
                    targetModelName = "EmptyModel",
                )
            val result = runBlocking { engine.analyze(request) }
            result.shouldBeInstanceOf<ReverseResult.Success>()
            (result as ReverseResult.Success).filesAnalysed shouldBe 0
        }

        test("analyze() mit .ts-Datei erkennt Klassen") {
            val dir = Files.createTempDirectory("ts-reverse-test")
            dir.resolve("Order.ts").toFile().writeText("export class Order { id: string; }")
            val request =
                ReverseRequest(
                    sourceRoots = listOf(dir),
                    targetModelName = "OrderModel",
                )
            val result = runBlocking { engine.analyze(request) }
            result.shouldBeInstanceOf<ReverseResult.Success>()
            val success = result as ReverseResult.Success
            success.filesAnalysed shouldBe 1
            val kumlDiagram = success.model.root as dev.kuml.core.model.KumlDiagram
            kumlDiagram.elements.filterIsInstance<UmlClass>().map { cls -> cls.name } shouldContain "Order"
        }

        // ── Type mapping ───────────────────────────────────────────────────────

        test("mapTsType: string → String") {
            engine.mapTsType("string") shouldBe "String"
        }

        test("mapTsType: number → Double") {
            engine.mapTsType("number") shouldBe "Double"
        }

        test("mapTsType: unbekannt wird durchgereicht") {
            engine.mapTsType("MyType") shouldBe "MyType"
        }
    })
