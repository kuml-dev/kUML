package dev.kuml.plugin.examples.typescript

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class TypeScriptCodegenPluginTest :
    FunSpec({

        val plugin = TypeScriptCodegenPlugin()
        val generator = plugin.generators().first()
        val tsGenerator = generator as TypeScriptCodeGenerator

        fun tempDir(): File =
            Files
                .createTempDirectory("ts-codegen-test")
                .toFile()
                .also { it.deleteOnExit() }

        fun diagram(vararg elements: dev.kuml.core.model.KumlElement) =
            KumlDiagram(
                id = "test",
                name = "Test",
                type = DiagramType.CLASS,
                elements = elements.toList(),
            )

        // ── Plugin descriptor ──────────────────────────────────────────────────

        test("generators() gibt genau einen Generator zurück") {
            plugin.generators() shouldHaveSize 1
        }

        test("generator id ist 'typescript'") {
            generator.id shouldBe "typescript"
        }

        test("descriptor capabilities enthält CODEGEN") {
            plugin.descriptor.capabilities shouldContain PluginCapability.CODEGEN
        }

        test("descriptor requiredPermissions enthält FS_WRITE") {
            plugin.descriptor.requiredPermissions shouldContain PluginPermission.FS_WRITE
        }

        test("descriptor kumlVersionRange enthält 0.12.0") {
            plugin.descriptor.kumlVersionRange.contains(
                dev.kuml.plugin.api.core
                    .PluginVersion(0, 12, 0),
            ) shouldBe true
        }

        // ── UmlClass generation ────────────────────────────────────────────────

        test("generate() mit leerem Diagram gibt leere Datei-Liste zurück") {
            val files = generator.generate(diagram(), tempDir(), emptyMap())
            files shouldHaveSize 0
        }

        test("generate() mit UmlClass erstellt .ts-Datei") {
            val cls = UmlClass(id = "Order", name = "Order")
            val out = tempDir()
            val files = generator.generate(diagram(cls), out, emptyMap())
            files shouldHaveSize 1
            files.first().name shouldBe "Order.ts"
        }

        test("generierter Inhalt für UmlClass enthält 'export class'") {
            val cls = UmlClass(id = "Order", name = "Order")
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.ts").readText() shouldContain "export class Order"
        }

        test("abstrakte UmlClass erzeugt 'export abstract class'") {
            val cls = UmlClass(id = "Base", name = "Base", isAbstract = true)
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Base.ts").readText() shouldContain "export abstract class Base"
        }

        test("UmlClass mit Attribut erzeugt Property-Zeile") {
            val attr = UmlProperty(id = "Order.id", name = "id", type = UmlTypeRef("String"))
            val cls = UmlClass(id = "Order", name = "Order", attributes = listOf(attr))
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.ts").readText() shouldContain "id: string;"
        }

        // ── UmlInterface generation ────────────────────────────────────────────

        test("UmlInterface erzeugt 'export interface'") {
            val iface = UmlInterface(id = "Orderable", name = "Orderable")
            val out = tempDir()
            generator.generate(diagram(iface), out, emptyMap())
            File(out, "Orderable.ts").readText() shouldContain "export interface Orderable"
        }

        // ── UmlEnumeration generation ──────────────────────────────────────────

        test("UmlEnumeration erzeugt 'export enum'") {
            val enum =
                UmlEnumeration(
                    id = "Status",
                    name = "Status",
                    literals =
                        listOf(
                            UmlEnumerationLiteral(id = "Status.DRAFT", name = "DRAFT"),
                            UmlEnumerationLiteral(id = "Status.DONE", name = "DONE"),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(enum), out, emptyMap())
            val content = File(out, "Status.ts").readText()
            content shouldContain "export enum Status"
            content shouldContain "DRAFT"
            content shouldContain "DONE"
        }

        // ── Type mapping ───────────────────────────────────────────────────────

        test("mapType: String → string") {
            tsGenerator.mapType("String") shouldBe "string"
        }

        test("mapType: Int → number") {
            tsGenerator.mapType("Int") shouldBe "number"
        }

        test("mapType: Boolean → boolean") {
            tsGenerator.mapType("Boolean") shouldBe "boolean"
        }

        test("mapType: unbekannter Typ wird durchgereicht") {
            tsGenerator.mapType("MyCustomType") shouldBe "MyCustomType"
        }
    })
