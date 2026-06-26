package dev.kuml.plugin.examples.cppreverse

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class CppReversePluginTest :
    FunSpec({

        val plugin = CppReversePlugin()
        val engine = CppReverseEngine()

        // Helper: parse a C++ source string and build UML elements
        fun parseElements(src: String): List<KumlElement> = CppUmlMapper(CppTypeMapper()).buildElements(listOf(engine.parseCppSource(src)))

        // ── T1: Plugin descriptor ─────────────────────────────────────────────

        test("engines() returns exactly one engine") {
            plugin.engines() shouldHaveSize 1
        }

        // ── T2: Engine id ─────────────────────────────────────────────────────

        test("engine id is cpp") {
            engine.id shouldBe "cpp"
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

        test("descriptor id equals manifest id") {
            plugin.descriptor.id shouldBe "dev.kuml.plugins.reverse-cpp"
        }

        // ── T6: Version range ─────────────────────────────────────────────────

        test("descriptor kumlVersionRange contains 0.12.0") {
            plugin.descriptor.kumlVersionRange.contains(PluginVersion(0, 12, 0)) shouldBe true
        }

        // ── T7: Simple class ──────────────────────────────────────────────────

        test("parses class Foo {} → UmlClass named Foo") {
            val elements = parseElements("class Foo {};")
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull()
            cls?.name shouldBe "Foo"
        }

        // ── T8: Class with 3 fields and 2 methods ─────────────────────────────

        test("class with 3 fields and 2 methods → 3 props + 2 ops with correct types") {
            val src =
                """
                class MyClass {
                public:
                    int a;
                    double b;
                    std::string c;
                    void run();
                    int calc(int x);
                };
                """.trimIndent()
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first { it.name == "MyClass" }
            cls.attributes shouldHaveSize 3
            cls.operations shouldHaveSize 2
            cls.attributes
                .first { it.name == "a" }
                .type.name shouldBe "Int"
            cls.attributes
                .first { it.name == "b" }
                .type.name shouldBe "Double"
            cls.attributes
                .first { it.name == "c" }
                .type.name shouldBe "String"
        }

        // ── T9: Struct defaults to public ─────────────────────────────────────

        test("struct defaults members to public") {
            val src = "struct P { int x; };"
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.first { it.name == "x" }
            prop.visibility shouldBe Visibility.PUBLIC
        }

        // ── T10: Class defaults to private ────────────────────────────────────

        test("class defaults members to private") {
            val src = "class C { int x; };"
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.first { it.name == "x" }
            prop.visibility shouldBe Visibility.PRIVATE
        }

        // ── T11: public: label flips visibility ───────────────────────────────

        test("public label flips visibility") {
            val src =
                """
                class C {
                    int priv;
                public:
                    int pub;
                };
                """.trimIndent()
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first()
            cls.attributes.first { it.name == "pub" }.visibility shouldBe Visibility.PUBLIC
            cls.attributes.first { it.name == "priv" }.visibility shouldBe Visibility.PRIVATE
        }

        // ── T12: Single inheritance ───────────────────────────────────────────

        test("inheritance class Derived public Base → UmlGeneralization") {
            val src =
                """
                class Base {};
                class Derived : public Base {};
                """.trimIndent()
            val elements = parseElements(src)
            val gen = elements.filterIsInstance<UmlGeneralization>().firstOrNull()
            gen shouldNotBe null
            gen!!.specificId shouldBe "cpp::Derived"
            gen.generalId shouldBe "cpp::Base"
        }

        // ── T13: Multiple inheritance ─────────────────────────────────────────

        test("multiple inheritance → two UmlGeneralization") {
            val src =
                """
                class A {};
                class B {};
                class C : public A, public B {};
                """.trimIndent()
            val elements = parseElements(src)
            val gens = elements.filterIsInstance<UmlGeneralization>()
            gens shouldHaveSize 2
        }

        // ── T14: Forward declaration is skipped ───────────────────────────────

        test("forward declaration class Foo is gracefully skipped") {
            val src = "class Foo;"
            val elements = parseElements(src)
            elements.filterIsInstance<UmlClass>() shouldHaveSize 0
        }

        // ── T15: Forward decl + later definition → single UmlClass ───────────

        test("forward decl followed by full definition produces single UmlClass") {
            val src =
                """
                class Foo;
                class Foo { int x; };
                """.trimIndent()
            val elements = parseElements(src)
            val classes = elements.filterIsInstance<UmlClass>().filter { it.name == "Foo" }
            classes shouldHaveSize 1
            classes.first().attributes shouldHaveSize 1
        }

        // ── T16: Template class generates warning but doesn't fail ─────────────

        test("template class generates warning but does not fail") {
            val src = "template<typename T> class Box { public: T value; };"
            val ast = engine.parseCppSource(src)
            val warn = ast.diagnostics.any { it.code == "REV-CPP-001" && it.severity == ReverseDiagnostic.Severity.WARN }
            warn shouldBe true
            val elements = CppUmlMapper(CppTypeMapper()).buildElements(listOf(ast))
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull { it.name == "Box" }
            cls shouldNotBe null
        }

        // ── T17: Pure virtual method → UmlOperation isAbstract=true ──────────

        test("pure virtual method → UmlOperation isAbstract true") {
            val src = "class I { public: virtual void f() = 0; };"
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val op = cls.operations.first { it.name == "f" }
            op.isAbstract shouldBe true
        }

        // ── T18: Static member ────────────────────────────────────────────────

        test("static member → isStatic true") {
            val src = "class C { public: static int counter; };"
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.first { it.name == "counter" }
            prop.isStatic shouldBe true
        }

        // ── T19: Enum ─────────────────────────────────────────────────────────

        test("enum Color RED GREEN BLUE → UmlEnumeration with 3 literals") {
            val src = "enum Color { RED, GREEN, BLUE };"
            val elements = parseElements(src)
            val enum = elements.filterIsInstance<UmlEnumeration>().first { it.name == "Color" }
            enum.literals shouldHaveSize 3
            enum.literals.map { it.name } shouldContain "RED"
            enum.literals.map { it.name } shouldContain "GREEN"
            enum.literals.map { it.name } shouldContain "BLUE"
        }

        // ── T20: enum class with initializers drops values ────────────────────

        test("enum class with initializers drops values keeps names") {
            val src = "enum class E { A = 1, B = 2 };"
            val elements = parseElements(src)
            val enum = elements.filterIsInstance<UmlEnumeration>().first { it.name == "E" }
            enum.literals.map { it.name } shouldBe listOf("A", "B")
        }

        // ── T21: Namespace qualifier in element id ────────────────────────────

        test("namespace ns class X → element id includes ns") {
            val src =
                """
                namespace ns {
                    class X {};
                }
                """.trimIndent()
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first { it.name == "X" }
            cls.id shouldBe "cpp::ns::X"
        }

        // ── T22: std::vector field → List ─────────────────────────────────────

        test("std::vector<int> field → type mapped to List") {
            val src = "class C { public: std::vector<int> items; };"
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.first { it.name == "items" }
            prop.type.name shouldBe "List<Int>"
        }

        // ── T23: shared_ptr unwrapped ─────────────────────────────────────────

        test("std::shared_ptr<Foo> field → unwrapped to Foo") {
            val src = "class C { public: std::shared_ptr<Foo> ptr; };"
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.first { it.name == "ptr" }
            prop.type.name shouldBe "Foo"
        }

        // ── T24: Preprocessor lines are ignored ───────────────────────────────

        test("preprocessor include and define lines are ignored") {
            val src =
                """
                #include <vector>
                #include <string>
                #define MAX_SIZE 100
                class MyClass {
                public:
                    int value;
                };
                """.trimIndent()
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull { it.name == "MyClass" }
            cls shouldNotBe null
            cls!!.attributes shouldHaveSize 1
        }

        // ── T25: Comments stripped ────────────────────────────────────────────

        test("line and block comments are stripped") {
            val src =
                """
                // This is a line comment
                /* Block
                   comment */
                class Commented {
                public:
                    int x; // field comment
                    /* another block */
                    double y;
                };
                """.trimIndent()
            val elements = parseElements(src)
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull { it.name == "Commented" }
            cls shouldNotBe null
            cls!!.attributes shouldHaveSize 2
        }

        // ── T26: Engine end-to-end over temp directory ────────────────────────

        test("analyze over temp dir with one .hpp → ReverseResult.Success filesAnalysed=1") {
            val dir = Files.createTempDirectory("kuml-cpp-test")
            try {
                val hppFile = dir.resolve("sample.hpp")
                hppFile.toFile().writeText(
                    """
                    class Sample {
                    public:
                        int value;
                        void doSomething();
                    };
                    """.trimIndent(),
                )
                val result =
                    runBlocking {
                        engine.analyze(
                            ReverseRequest(
                                sourceRoots = listOf(dir),
                                includeGlobs = listOf("**/*.hpp"),
                            ),
                        )
                    }
                result shouldNotBe null
                val success = result as? ReverseResult.Success
                success shouldNotBe null
                success!!.filesAnalysed shouldBe 1
                val diagram = success.model.root as? KumlDiagram
                diagram shouldNotBe null
                val cls = diagram!!.elements.filterIsInstance<UmlClass>().firstOrNull { it.name == "Sample" }
                cls shouldNotBe null
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
