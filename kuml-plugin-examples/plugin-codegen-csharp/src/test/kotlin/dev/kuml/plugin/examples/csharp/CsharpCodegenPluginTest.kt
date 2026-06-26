package dev.kuml.plugin.examples.csharp

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class CsharpCodegenPluginTest :
    FunSpec({

        val plugin = CsharpCodegenPlugin()
        val generator = plugin.generators().first()

        fun tempDir(): File =
            Files
                .createTempDirectory("csharp-codegen-test")
                .toFile()
                .also { it.deleteOnExit() }

        fun diagram(vararg elements: KumlElement) =
            KumlDiagram(
                id = "test",
                name = "Test",
                type = DiagramType.CLASS,
                elements = elements.toList(),
            )

        // ── Plugin descriptor ──────────────────────────────────────────────────

        test("generators() liefert genau einen Generator") {
            plugin.generators() shouldHaveSize 1
        }

        test("generator id ist csharp") {
            generator.id shouldBe "csharp"
        }

        test("descriptor capabilities enthält CODEGEN") {
            plugin.descriptor.capabilities shouldContain PluginCapability.CODEGEN
        }

        test("descriptor requiredPermissions enthält FS_WRITE") {
            plugin.descriptor.requiredPermissions shouldContain PluginPermission.FS_WRITE
        }

        test("descriptor id ist dev.kuml.plugin.codegen.csharp") {
            plugin.descriptor.id shouldBe "dev.kuml.plugin.codegen.csharp"
        }

        test("descriptor kumlVersionRange enthält 0.12.0") {
            plugin.descriptor.kumlVersionRange.contains(PluginVersion(0, 12, 0)) shouldBe true
        }

        // ── Basic generation ───────────────────────────────────────────────────

        test("leeres Diagram → leere Datei-Liste") {
            generator.generate(diagram(), tempDir(), emptyMap()) shouldHaveSize 0
        }

        test("UmlClass erzeugt genau eine .cs Datei") {
            val cls = UmlClass(id = "Order", name = "Order")
            val files = generator.generate(diagram(cls), tempDir(), emptyMap())
            files shouldHaveSize 1
            files.first().name shouldBe "Order.cs"
        }

        test("Klasse mit String-Property nutzt C# auto-property Syntax") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(id = "Order.name", name = "Name", type = UmlTypeRef("String")),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.cs").readText() shouldContain "public string Name { get; set; }"
        }

        test("Properties verschiedener Typen werden korrekt gemappt") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(id = "p1", name = "id", type = UmlTypeRef("String")),
                            UmlProperty(id = "p2", name = "total", type = UmlTypeRef("Double")),
                            UmlProperty(id = "p3", name = "count", type = UmlTypeRef("Int")),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            val content = File(out, "Order.cs").readText()
            content shouldContain "public string id { get; set; }"
            content shouldContain "public double total { get; set; }"
            content shouldContain "public int count { get; set; }"
        }

        test("UmlOperation erzeugt C# Methode") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    operations =
                        listOf(
                            UmlOperation(id = "op1", name = "submit", returnType = UmlTypeRef("Void")),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.cs").readText() shouldContain "public void submit("
        }

        // ── Interface ─────────────────────────────────────────────────────────

        test("UmlInterface erzeugt interface IFoo") {
            val iface = UmlInterface(id = "Orderable", name = "Orderable")
            val out = tempDir()
            val files = generator.generate(diagram(iface), out, emptyMap())
            files shouldHaveSize 1
            files.first().name shouldBe "IOrderable.cs"
            File(out, "IOrderable.cs").readText() shouldContain "public interface IOrderable"
        }

        test("Interface-Methoden ohne Body (mit Semikolon)") {
            val iface =
                UmlInterface(
                    id = "Repo",
                    name = "Repo",
                    operations =
                        listOf(
                            UmlOperation(id = "op1", name = "save", returnType = UmlTypeRef("Void")),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(iface), out, emptyMap())
            val content = File(out, "IRepo.cs").readText()
            content shouldContain "void save();"
            content shouldNotContain "{ }"
        }

        // ── Abstract class ────────────────────────────────────────────────────

        test("abstrakte Klasse erzeugt 'public abstract class'") {
            val cls = UmlClass(id = "Base", name = "Base", isAbstract = true)
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Base.cs").readText() shouldContain "public abstract class Base"
        }

        // ── Inheritance & Realization ─────────────────────────────────────────

        test("UmlGeneralization erzeugt : BaseClass") {
            val base = UmlClass(id = "Base", name = "Base")
            val derived = UmlClass(id = "Derived", name = "Derived")
            val gen = UmlGeneralization(id = "g1", specificId = "Derived", generalId = "Base")
            val out = tempDir()
            generator.generate(diagram(base, derived, gen), out, emptyMap())
            File(out, "Derived.cs").readText() shouldContain "class Derived : Base"
        }

        test("UmlInterfaceRealization erzeugt : IInterface") {
            val iface = UmlInterface(id = "IRepo", name = "IRepo")
            val cls = UmlClass(id = "Service", name = "Service")
            val real = UmlInterfaceRealization(id = "r1", implementingId = "Service", interfaceId = "IRepo")
            val out = tempDir()
            generator.generate(diagram(iface, cls, real), out, emptyMap())
            File(out, "Service.cs").readText() shouldContain ": IRepo"
        }

        test("Mehrere Interface-Realizations komma-separiert") {
            val ifaceA = UmlInterface(id = "RepoA", name = "RepoA")
            val ifaceB = UmlInterface(id = "RepoB", name = "RepoB")
            val cls = UmlClass(id = "Service", name = "Service")
            val realA = UmlInterfaceRealization(id = "r1", implementingId = "Service", interfaceId = "RepoA")
            val realB = UmlInterfaceRealization(id = "r2", implementingId = "Service", interfaceId = "RepoB")
            val out = tempDir()
            generator.generate(diagram(ifaceA, ifaceB, cls, realA, realB), out, emptyMap())
            File(out, "Service.cs").readText() shouldContain ": IRepoA, IRepoB"
        }

        test("Generalization + Realization: Basisklasse vor Interfaces") {
            val base = UmlClass(id = "Base", name = "Base")
            val iface = UmlInterface(id = "Repo", name = "Repo")
            val cls = UmlClass(id = "Impl", name = "Impl")
            val gen = UmlGeneralization(id = "g1", specificId = "Impl", generalId = "Base")
            val real = UmlInterfaceRealization(id = "r1", implementingId = "Impl", interfaceId = "Repo")
            val out = tempDir()
            generator.generate(diagram(base, iface, cls, gen, real), out, emptyMap())
            File(out, "Impl.cs").readText() shouldContain ": Base, IRepo"
        }

        // ── Nullable Reference Types ──────────────────────────────────────────

        test("Nullable Property bekommt ? Suffix bei useNullableReferenceTypes=true, lower=0") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "p1",
                                name = "note",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(0, 1),
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.cs").readText() shouldContain "string? note"
        }

        test("Nicht-nullable Property ohne ? bei lower=1") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "p1",
                                name = "name",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(1, 1),
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.cs").readText() shouldNotContain "string?"
        }

        test("Value-Type Property (int) bekommt kein ? Suffix auch bei lower=0") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "p1",
                                name = "count",
                                type = UmlTypeRef("Int"),
                                multiplicity = Multiplicity(0, 1),
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            // 'int?' is Nullable<int> — not a valid NRT annotation; value types must not get '?'
            File(out, "Order.cs").readText() shouldNotContain "int?"
        }

        test("Value-Type Property (bool) bekommt kein ? Suffix auch bei lower=0") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "p1",
                                name = "active",
                                type = UmlTypeRef("Boolean"),
                                multiplicity = Multiplicity(0, 1),
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.cs").readText() shouldNotContain "bool?"
        }

        test("useNullableReferenceTypes=false unterdrückt ?") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(
                                id = "p1",
                                name = "note",
                                type = UmlTypeRef("String"),
                                multiplicity = Multiplicity(0, 1),
                            ),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, mapOf("useNullableReferenceTypes" to "false"))
            File(out, "Order.cs").readText() shouldNotContain "?"
        }

        test("useNullableReferenceTypes=true erzeugt #nullable enable Direktive") {
            val cls = UmlClass(id = "Order", name = "Order")
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.cs").readText() shouldContain "#nullable enable"
        }

        // ── Namespace ─────────────────────────────────────────────────────────

        test("namespace block umschließt Klasse") {
            val cls = UmlClass(id = "Foo", name = "Foo")
            val out = tempDir()
            generator.generate(diagram(cls), out, mapOf("namespace" to "App.Model"))
            val content = File(out, "Foo.cs").readText()
            content shouldContain "namespace App.Model"
            content shouldContain "    public"
        }

        test("kein namespace → kein namespace-Block") {
            val cls = UmlClass(id = "Foo", name = "Foo")
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Foo.cs").readText() shouldNotContain "namespace"
        }

        test("UmlPackage-Mitglieder erhalten Namespace aus Package-Name") {
            val pkg =
                UmlPackage(
                    id = "model",
                    name = "model",
                    members = listOf(UmlClass(id = "Foo", name = "Foo")),
                )
            val out = tempDir()
            generator.generate(diagram(pkg), out, emptyMap())
            File(out, "Foo.cs").readText() shouldContain "namespace model"
        }

        test("verschachtelter Package erzeugt punkt-separierten Namespace") {
            val inner =
                UmlPackage(
                    id = "inner",
                    name = "inner",
                    members = listOf(UmlClass(id = "Bar", name = "Bar")),
                )
            val outer = UmlPackage(id = "outer", name = "outer", members = listOf(inner))
            val out = tempDir()
            generator.generate(diagram(outer), out, emptyMap())
            File(out, "Bar.cs").readText() shouldContain "namespace outer.inner"
        }

        // ── Using directives ──────────────────────────────────────────────────

        test("generateUsings=true erzeugt 'using System;' für Guid-Property") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    attributes =
                        listOf(
                            UmlProperty(id = "p1", name = "id", type = UmlTypeRef("UUID")),
                        ),
                )
            val out = tempDir()
            generator.generate(diagram(cls), out, emptyMap())
            File(out, "Order.cs").readText() shouldContain "using System;"
        }

        // ── Enumeration ────────────────────────────────────────────────────────

        test("UmlEnumeration erzeugt enum") {
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
            val content = File(out, "Status.cs").readText()
            content shouldContain "enum Status"
            content shouldContain "DRAFT"
            content shouldContain "DONE"
        }

        // ── Security: path-traversal sanitization ─────────────────────────────

        test("Pfad-Traversal-Name wird sanitisiert (kein Schreiben außerhalb outputDir)") {
            val cls = UmlClass(id = "evil", name = "../../evil")
            val out = tempDir()
            val files = generator.generate(diagram(cls), out, emptyMap())
            files.forEach { file ->
                file.canonicalPath.startsWith(out.canonicalPath) shouldBe true
            }
            files.forEach { file ->
                file.name shouldNotContain "/"
                file.name shouldNotContain ".."
            }
        }

        // ── Type mapper unit tests ─────────────────────────────────────────────

        val mapper = CsharpTypeMapper()

        test("mapType: String → string") {
            mapper.mapType("String") shouldBe "string"
        }

        test("mapType: Int → int") {
            mapper.mapType("Int") shouldBe "int"
        }

        test("mapType: Boolean → bool") {
            mapper.mapType("Boolean") shouldBe "bool"
        }

        test("mapType: Double → double") {
            mapper.mapType("Double") shouldBe "double"
        }

        test("mapType: Long → long") {
            mapper.mapType("Long") shouldBe "long"
        }

        test("mapType: unbekannter Typ wird durchgereicht") {
            mapper.mapType("MyType") shouldBe "MyType"
        }
    })
