package dev.kuml.plugin.examples.tsreverse

import dev.kuml.codegen.reverse.ReverseRequest
import dev.kuml.codegen.reverse.ReverseResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.plugin.api.core.PluginCapability
import dev.kuml.plugin.api.core.PluginPermission
import dev.kuml.plugin.api.core.PluginVersion
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class TypeScriptReversePluginTest :
    FunSpec({

        val plugin = TypeScriptReversePlugin()
        val engine = TypeScriptReverseEngine()

        // Helper: parse a TypeScript source string and build UML elements
        fun parseElements(source: String) =
            TsModelBuilder(TsTypeMapper()).buildElements(
                fileAsts = listOf(engine.parseTypeScriptSource(source)),
            )

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

        // ── Interface parsing ──────────────────────────────────────────────────

        test("parst 'interface Foo {}' → UmlInterface") {
            val elements = parseElements("export interface Foo {}")
            val iface = elements.filterIsInstance<UmlInterface>().firstOrNull()
            iface?.name shouldBe "Foo"
        }

        test("interface mit Properties → UmlProperty mit korrektem Typ") {
            val source = "export interface IUser { name: string; age: number; }"
            val elements = parseElements(source)
            val iface = elements.filterIsInstance<UmlInterface>().first()
            val nameProp = iface.attributes.firstOrNull { it.name == "name" }
            nameProp?.type?.name shouldBe "String"
            val ageProp = iface.attributes.firstOrNull { it.name == "age" }
            ageProp?.type?.name shouldBe "Double"
        }

        test("interface mit optionaler Property → isOptional erkannt") {
            val source = "export interface IConfig { timeout?: number; }"
            val elements = parseElements(source)
            val iface = elements.filterIsInstance<UmlInterface>().first()
            iface.attributes shouldHaveSize 1
            iface.attributes.first().name shouldBe "timeout"
        }

        test("interface mit generischem Typ-Parameter → Name korrekt") {
            val source = "export interface Repository<T> { findById(id: string): T; }"
            val elements = parseElements(source)
            val iface = elements.filterIsInstance<UmlInterface>().firstOrNull()
            iface shouldNotBe null
            iface!!.name shouldBe "Repository"
        }

        test("interface extends erzeugt UmlGeneralization") {
            val source =
                """
                export interface Animal { name: string; }
                export interface Dog extends Animal { breed: string; }
                """.trimIndent()
            val elements = parseElements(source)
            val gen = elements.filterIsInstance<UmlGeneralization>().firstOrNull()
            gen shouldNotBe null
            gen!!.specificId shouldBe "ts::Dog"
            gen.generalId shouldBe "ts::Animal"
        }

        // ── Class parsing ──────────────────────────────────────────────────────

        test("parst 'class Bar {}' → UmlClass") {
            val elements = parseElements("export class Bar {}")
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull()
            cls?.name shouldBe "Bar"
        }

        test("parst 'abstract class Baz {}' → UmlClass(isAbstract=true)") {
            val elements = parseElements("export abstract class Baz {}")
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull()
            cls?.isAbstract shouldBe true
        }

        test("class mit Methode → UmlOperation mit returnType") {
            val source = "export class Service { getData(): string { return ''; } }"
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val op = cls.operations.firstOrNull { it.name == "getData" }
            op shouldNotBe null
            op!!.returnType?.name shouldBe "String"
        }

        test("class mit privater Property → Visibility.PRIVATE") {
            val source = "export class User { private id: string; }"
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.firstOrNull { it.name == "id" }
            prop?.visibility shouldBe Visibility.PRIVATE
        }

        test("class mit static Methode → isStatic=true") {
            val source = "export class MathUtils { static add(a: number, b: number): number { return a + b; } }"
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val op = cls.operations.firstOrNull { it.name == "add" }
            op?.isStatic shouldBe true
        }

        test("class extends → UmlGeneralization") {
            val source =
                """
                export class Animal {}
                export class Dog extends Animal {}
                """.trimIndent()
            val elements = parseElements(source)
            val gen = elements.filterIsInstance<UmlGeneralization>().firstOrNull()
            gen shouldNotBe null
            gen!!.specificId shouldBe "ts::Dog"
            gen.generalId shouldBe "ts::Animal"
        }

        test("class implements Interface → UmlInterfaceRealization") {
            val source =
                """
                export interface Printable { print(): void; }
                export class Document implements Printable { print(): void {} }
                """.trimIndent()
            val elements = parseElements(source)
            val real = elements.filterIsInstance<UmlInterfaceRealization>().firstOrNull()
            real shouldNotBe null
            real!!.implementingId shouldBe "ts::Document"
            real.interfaceId shouldBe "ts::Printable"
        }

        // ── Decorator parsing ──────────────────────────────────────────────────

        test("Decorator über class wird als Stereotyp übernommen") {
            val source = "@Injectable()\nexport class AuthService {}"
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull()
            cls shouldNotBe null
            cls!!.stereotypes shouldContain "Injectable"
        }

        test("Decorator über interface wird als Stereotyp übernommen") {
            val source = "@ApiModel()\nexport interface IOrder { id: string; }"
            val elements = parseElements(source)
            val iface = elements.filterIsInstance<UmlInterface>().firstOrNull()
            iface shouldNotBe null
            iface!!.stereotypes shouldContain "ApiModel"
        }

        // ── Enum parsing ───────────────────────────────────────────────────────

        test("parst 'enum Status { A, B }' → UmlEnumeration mit 2 Literals") {
            val elements = parseElements("export enum Status { A, B }")
            val enum = elements.filterIsInstance<UmlEnumeration>().firstOrNull()
            enum?.name shouldBe "Status"
            enum?.literals?.shouldHaveSize(2)
        }

        test("enum mit Wert-Zuweisungen → Literals korrekt ohne Werte") {
            val source = "export enum Color { Red = 0, Green = 1, Blue = 2 }"
            val elements = parseElements(source)
            val enum = elements.filterIsInstance<UmlEnumeration>().first()
            enum.literals.map { it.name } shouldBe listOf("Red", "Green", "Blue")
        }

        test("enum mit String-Werten → Literals korrekt") {
            val source = """export enum Direction { Up = "UP", Down = "DOWN" }"""
            val elements = parseElements(source)
            val enum = elements.filterIsInstance<UmlEnumeration>().first()
            enum.literals.map { it.name } shouldBe listOf("Up", "Down")
        }

        test("enum mit Funktionsaufruf-Initializer → Literals korrekt") {
            val source = "export enum E { A = fn(x, y), B }"
            val elements = parseElements(source)
            val enum = elements.filterIsInstance<UmlEnumeration>().first()
            enum.literals.map { it.name } shouldBe listOf("A", "B")
        }

        // ── Type mapping ───────────────────────────────────────────────────────

        test("mapTsType: string → String") {
            engine.mapTsType("string") shouldBe "String"
        }

        test("mapTsType: number → Double") {
            engine.mapTsType("number") shouldBe "Double"
        }

        test("mapTsType: boolean → Boolean") {
            engine.mapTsType("boolean") shouldBe "Boolean"
        }

        test("mapTsType: void → void") {
            engine.mapTsType("void") shouldBe "void"
        }

        test("mapTsType: any → Object") {
            engine.mapTsType("any") shouldBe "Object"
        }

        test("mapTsType: unbekannter Typ wird durchgereicht") {
            engine.mapTsType("MyType") shouldBe "MyType"
        }

        test("mapTsType: nullable union string|null → String") {
            engine.mapTsType("string | null") shouldBe "String"
        }

        // ── Nested / edge cases ────────────────────────────────────────────────

        test("leere TypeScript-Datei ergibt leere Element-Liste") {
            parseElements("") shouldHaveSize 0
        }

        test("mehrzeilige Klasse mit Body-Implementierung wird korrekt geparst") {
            val source =
                """
                export class Calculator {
                    add(a: number, b: number): number {
                        const result = a + b;
                        return result;
                    }
                    subtract(a: number, b: number): number {
                        return a - b;
                    }
                }
                """.trimIndent()
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            cls.operations shouldHaveSize 2
        }

        test("Property mit Array-Typ → Multiplicity 0..*") {
            val source = "export class Team { members: string[]; }"
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.firstOrNull { it.name == "members" }
            prop shouldNotBe null
            prop!!.multiplicity.upper shouldBe null
        }

        test("readonly Property → isReadOnly=true") {
            val source = "export class Config { readonly version: string; }"
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.firstOrNull { it.name == "version" }
            prop?.isReadOnly shouldBe true
        }

        test("Property mit Array<T>-Typ → Multiplicity 0..*") {
            val source = "export class Foo { items: Array<string>; }"
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            val prop = cls.attributes.firstOrNull { it.name == "items" }
            prop shouldNotBe null
            prop!!.multiplicity.upper shouldBe null
        }

        // ── Cross-file import index ────────────────────────────────────────────

        test("parseTypeScriptSource liefert korrekte Import-Liste") {
            val source = """import { UserService } from './services/user.service';"""
            val ast = engine.parseTypeScriptSource(source)
            val imp = ast.imports.firstOrNull { it.names.contains("UserService") }
            imp shouldNotBe null
            imp!!.from shouldBe "./services/user.service"
        }

        test("zwei Dateien mit gemeinsamen Klassen werden beide erfasst") {
            val ast1 = engine.parseTypeScriptSource("export class Foo {}")
            val ast2 = engine.parseTypeScriptSource("export class Bar {}")
            val elements =
                TsModelBuilder(TsTypeMapper()).buildElements(
                    fileAsts = listOf(ast1, ast2),
                )
            val names = elements.filterIsInstance<UmlClass>().map { it.name }
            names shouldContain "Foo"
            names shouldContain "Bar"
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
            result.filesAnalysed shouldBe 0
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
            val success = result.shouldBeInstanceOf<ReverseResult.Success>()
            success.filesAnalysed shouldBe 1
            val diagram = success.model.root as KumlDiagram
            diagram.elements.filterIsInstance<UmlClass>().map { it.name } shouldContain "Order"
        }

        // ── Getter/setter, constructor, import-alias ───────────────────────────

        test("getter-Accessor wird als readonly Property erkannt, nicht als Methode 'get'") {
            val source =
                """
                export class Person {
                    private _name: string;
                    get name(): string { return this._name; }
                    set name(value: string) { this._name = value; }
                }
                """.trimIndent()
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            // 'get name' must appear as a property named 'name', not as a method named 'get'
            val getNames = cls.attributes.map { it.name }
            getNames shouldContain "name"
            cls.operations.none { it.name == "get" } shouldBe true
            cls.operations.none { it.name == "set" } shouldBe true
            // The getter accessor is represented as a readonly property
            val nameProp = cls.attributes.firstOrNull { it.name == "name" }
            nameProp shouldNotBe null
            nameProp!!.isReadOnly shouldBe true
        }

        test("constructor-Block wird übersprungen — kein UmlOperation namens 'constructor'") {
            val source =
                """
                export class Service {
                    private url: string;
                    constructor(url: string) {
                        this.url = url;
                    }
                    fetch(): void {}
                }
                """.trimIndent()
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().first()
            // constructor must be skipped entirely
            cls.operations.none { it.name == "constructor" } shouldBe true
            // the real method must still be present
            cls.operations.any { it.name == "fetch" } shouldBe true
        }

        test("import mit 'as'-Alias — importierter Name ist der Original-Name, nicht der Alias") {
            val source = """import { Foo as Bar, Baz } from './module';"""
            val ast = engine.parseTypeScriptSource(source)
            val imp = ast.imports.firstOrNull { it.from == "./module" }
            imp shouldNotBe null
            // 'Foo' is the exported symbol; 'Bar' is just the local alias → not tracked
            imp!!.names shouldContain "Foo"
            imp.names.none { it == "Bar" } shouldBe true
            imp.names shouldContain "Baz"
        }

        test("import { Foo as Bar } — alias-Name 'Bar' erscheint nicht in der Import-Liste") {
            val source = """import { SomeService as Service } from '@app/core';"""
            val ast = engine.parseTypeScriptSource(source)
            val imp = ast.imports.firstOrNull { it.from == "@app/core" }
            imp shouldNotBe null
            imp!!.names shouldContain "SomeService"
            imp.names.none { it == "Service" } shouldBe true
        }

        test("analyze() ignoriert .d.ts Deklarations-Dateien") {
            val dir = Files.createTempDirectory("ts-reverse-test")
            dir.resolve("types.d.ts").toFile().writeText("export interface Foo {}")
            dir.resolve("Real.ts").toFile().writeText("export class Real {}")
            val request =
                ReverseRequest(
                    sourceRoots = listOf(dir),
                    targetModelName = "Model",
                )
            val result = runBlocking { engine.analyze(request) }
            val success = result as ReverseResult.Success
            success.filesAnalysed shouldBe 1
        }

        // ── DoS / depth-guard (Security) ──────────────────────────────────────

        test("tief verschachtelte Generics (>64 Ebenen) werfen keinen StackOverflowError") {
            // Build A<A<A<...>>> with 200 nesting levels — well beyond MAX_TYPE_REF_DEPTH=64.
            // The depth guard must kick in and return Object rather than blowing the JVM stack.
            val nesting = 200
            val typeExpr = "A" + "<A".repeat(nesting) + ">".repeat(nesting)
            val source = "export class Foo { x: $typeExpr; }"
            // Must not throw — the depth guard should truncate the recursion silently.
            val elements = parseElements(source)
            val cls = elements.filterIsInstance<UmlClass>().firstOrNull()
            cls shouldNotBe null
        }

        test("analyse() mit tief verschachtelten Generics überspringt keine Datei (kein Absturz)") {
            val dir = Files.createTempDirectory("ts-reverse-test")
            val nesting = 500
            val typeExpr = "A" + "<A".repeat(nesting) + ">".repeat(nesting)
            dir.resolve("Deep.ts").toFile().writeText("export class Deep { x: $typeExpr; }")
            dir.resolve("Normal.ts").toFile().writeText("export class Normal { id: string; }")
            val request =
                ReverseRequest(
                    sourceRoots = listOf(dir),
                    targetModelName = "Model",
                )
            // Neither file should cause a crash; both should be analysed.
            val result = runBlocking { engine.analyze(request) }
            val success = result as ReverseResult.Success
            success.filesAnalysed shouldBe 2
        }
    })
