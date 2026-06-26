package dev.kuml.plugin.examples.csharpreverse

import dev.kuml.core.model.KumlElement
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CsharpReversePluginTest :
    FunSpec({

        val plugin = CsharpReversePlugin()
        val engine = CsharpReverseEngine()

        // Helper: parse a C# source string and build UML elements
        fun parseElements(src: String): List<KumlElement> =
            CsharpUmlMapper(CsharpTypeMapper()).buildElements(listOf(engine.parseCsharpSource(src))).first

        // ── T1: Plugin descriptor ─────────────────────────────────────────────

        test("engines() returns exactly one engine") {
            plugin.engines() shouldHaveSize 1
        }

        // ── T2: Engine id ─────────────────────────────────────────────────────

        test("engine id is csharp") {
            engine.id shouldBe "csharp"
        }

        // ── T3: Capabilities ──────────────────────────────────────────────────

        test("descriptor capabilities contains REVERSE") {
            plugin.descriptor.capabilities shouldContain PluginCapability.REVERSE
        }

        // ── T4: Permissions ───────────────────────────────────────────────────

        test("descriptor requiredPermissions contains FS_READ") {
            plugin.descriptor.requiredPermissions shouldContain PluginPermission.FS_READ
        }

        // ── T5: Descriptor id equals manifest id ──────────────────────────────

        test("descriptor id equals manifest id dev.kuml.plugins.reverse-csharp") {
            plugin.descriptor.id shouldBe "dev.kuml.plugins.reverse-csharp"
        }

        // ── T6: Simple class ──────────────────────────────────────────────────

        test("simple public class Foo produces one UmlClass with id cs::Foo name Foo visibility PUBLIC") {
            val elements = parseElements("public class Foo {}")
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Foo" }
            cls shouldNotBe null
            cls!!.id shouldBe "cs::Foo"
            cls.visibility shouldBe Visibility.PUBLIC
        }

        // ── T7: Auto-property ─────────────────────────────────────────────────

        test("auto-property 'public string Name { get; set; }' produces UmlProperty name=Name type=String visibility PUBLIC") {
            val elements =
                parseElements(
                    """
                    public class Person {
                        public string Name { get; set; }
                    }
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Person" }
            cls shouldNotBe null
            val prop = cls!!.attributes.find { it.name == "Name" }
            prop shouldNotBe null
            prop!!.type.name shouldBe "String"
            prop.visibility shouldBe Visibility.PUBLIC
        }

        // ── T8: Method with params ────────────────────────────────────────────

        test("method 'public int Add(int a, int b)' produces UmlOperation name=Add returnType=Int two params") {
            val elements =
                parseElements(
                    """
                    public class Calculator {
                        public int Add(int a, int b) { return a + b; }
                    }
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Calculator" }
            cls shouldNotBe null
            val op = cls!!.operations.find { it.name == "Add" }
            op shouldNotBe null
            op!!.returnType?.name shouldBe "Int"
            op.parameters shouldHaveSize 2
        }

        // ── T9: Abstract class ────────────────────────────────────────────────

        test("'abstract class Shape' produces UmlClass with isAbstract=true") {
            val elements = parseElements("public abstract class Shape {}")
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Shape" }
            cls shouldNotBe null
            cls!!.isAbstract.shouldBeTrue()
        }

        // ── T10: Interface ────────────────────────────────────────────────────

        test("'interface IShape { void Draw(); }' produces UmlInterface with one operation") {
            val elements =
                parseElements(
                    """
                    public interface IShape {
                        void Draw();
                    }
                    """.trimIndent(),
                )
            val iface = elements.filterIsInstance<UmlInterface>().find { it.name == "IShape" }
            iface shouldNotBe null
            iface!!.operations shouldHaveSize 1
            iface.operations.first().name shouldBe "Draw"
        }

        // ── T11: InterfaceRealization ─────────────────────────────────────────

        test("'class Circle : IShape' produces UmlInterfaceRealization implementingId=cs::Circle interfaceId=cs::IShape") {
            val elements =
                parseElements(
                    """
                    public interface IShape { void Draw(); }
                    public class Circle : IShape {
                        public void Draw() {}
                    }
                    """.trimIndent(),
                )
            val real = elements.filterIsInstance<UmlInterfaceRealization>().find { it.implementingId == "cs::Circle" }
            real shouldNotBe null
            real!!.interfaceId shouldBe "cs::IShape"
        }

        // ── T12: Generalization (base class) ──────────────────────────────────

        test("'class Dog : Animal' produces UmlGeneralization specific=cs::Dog general=cs::Animal") {
            val elements =
                parseElements(
                    """
                    public class Animal {}
                    public class Dog : Animal {}
                    """.trimIndent(),
                )
            val gen = elements.filterIsInstance<UmlGeneralization>().find { it.specificId == "cs::Dog" }
            gen shouldNotBe null
            gen!!.generalId shouldBe "cs::Animal"
        }

        // ── T13: Mixed inheritance ─────────────────────────────────────────────

        test("'class Square : Shape, IDrawable, IComparable' produces one UmlGeneralization + two UmlInterfaceRealization") {
            val elements =
                parseElements(
                    """
                    public class Shape {}
                    public interface IDrawable {}
                    public interface IComparable {}
                    public class Square : Shape, IDrawable, IComparable {}
                    """.trimIndent(),
                )
            val gens = elements.filterIsInstance<UmlGeneralization>().filter { it.specificId == "cs::Square" }
            val reals = elements.filterIsInstance<UmlInterfaceRealization>().filter { it.implementingId == "cs::Square" }
            gens shouldHaveSize 1
            reals shouldHaveSize 2
        }

        // ── T14: File-scoped namespace ─────────────────────────────────────────

        test("file-scoped namespace 'namespace App;' produces id cs::App::Foo") {
            val elements =
                parseElements(
                    """
                    namespace App;
                    public class Foo {}
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Foo" }
            cls shouldNotBe null
            cls!!.id shouldBe "cs::App::Foo"
        }

        // ── T15: Block namespace ───────────────────────────────────────────────

        test("block namespace 'namespace App { class Foo {} }' produces id cs::App::Foo") {
            val elements =
                parseElements(
                    """
                    namespace App {
                        public class Foo {}
                    }
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Foo" }
            cls shouldNotBe null
            cls!!.id shouldBe "cs::App::Foo"
        }

        // ── T16: Nested/dotted namespace ───────────────────────────────────────

        test("nested namespace 'namespace A.B' with class Foo produces id cs::A.B::Foo") {
            val elements =
                parseElements(
                    """
                    namespace A.B {
                        public class Foo {}
                    }
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Foo" }
            cls shouldNotBe null
            cls!!.id shouldBe "cs::A.B::Foo"
        }

        // ── T17: Enum ─────────────────────────────────────────────────────────

        test("'enum Color { Red, Green, Blue }' produces UmlEnumeration with 3 literals") {
            val elements =
                parseElements(
                    """
                    public enum Color { Red, Green, Blue }
                    """.trimIndent(),
                )
            val enum = elements.filterIsInstance<UmlEnumeration>().find { it.name == "Color" }
            enum shouldNotBe null
            enum!!.literals shouldHaveSize 3
            val literalNames = enum.literals.map { it.name }
            literalNames shouldContain "Red"
            literalNames shouldContain "Green"
            literalNames shouldContain "Blue"
        }

        // ── T18: Sealed class ─────────────────────────────────────────────────

        test("sealed class still parses to UmlClass") {
            val elements = parseElements("public sealed class MySingleton {}")
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "MySingleton" }
            cls shouldNotBe null
        }

        // ── T19: Record ───────────────────────────────────────────────────────

        test("record 'public record Point(int X, int Y)' produces UmlClass (no crash, best-effort)") {
            val elements =
                parseElements(
                    """
                    public record Point(int X, int Y);
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Point" }
            cls shouldNotBe null
        }

        // ── T20: Readonly field ───────────────────────────────────────────────

        test("'private readonly int x;' produces UmlProperty isReadOnly=true visibility PRIVATE") {
            val elements =
                parseElements(
                    """
                    public class Foo {
                        private readonly int x;
                    }
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Foo" }
            cls shouldNotBe null
            val prop = cls!!.attributes.find { it.name == "x" }
            prop shouldNotBe null
            prop!!.isReadOnly.shouldBeTrue()
            prop.visibility shouldBe Visibility.PRIVATE
        }

        // ── T21: Static method ────────────────────────────────────────────────

        test("'public static void Main()' produces UmlOperation isStatic=true") {
            val elements =
                parseElements(
                    """
                    public class Program {
                        public static void Main() {}
                    }
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Program" }
            cls shouldNotBe null
            val op = cls!!.operations.find { it.name == "Main" }
            op shouldNotBe null
            op!!.isStatic.shouldBeTrue()
        }

        // ── T22: Attribute → stereotype (best-effort) ─────────────────────────

        test("[Serializable] attribute on class does not crash parsing") {
            val elements =
                parseElements(
                    """
                    [Serializable]
                    public class DataObject {
                        public int Id { get; set; }
                    }
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "DataObject" }
            cls shouldNotBe null
        }

        // ── T23: Generic property ─────────────────────────────────────────────

        test("'public List<string> Names { get; set; }' produces type List<String>") {
            val elements =
                parseElements(
                    """
                    public class Container {
                        public List<string> Names { get; set; }
                    }
                    """.trimIndent(),
                )
            val cls = elements.filterIsInstance<UmlClass>().find { it.name == "Container" }
            cls shouldNotBe null
            val prop = cls!!.attributes.find { it.name == "Names" }
            prop shouldNotBe null
            prop!!.type.name shouldBe "List<String>"
        }

        // ── T24: Malformed input — no crash ───────────────────────────────────

        test("malformed input 'public class {{{ ' does not throw and produces empty or partial elements") {
            // Must not throw
            val result =
                runCatching {
                    parseElements("public class {{{ ")
                }
            result.isSuccess.shouldBeTrue()
        }
    })
