package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.StmSeverity
import dev.kuml.sysml2.constraint.Sysml2StmChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.StmEdgeAdapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

class Sysml2StmStructureTest :
    FunSpec({
        fun codes(m: Sysml2Model) = Sysml2StmChecker.check(model = m).map { it.code }

        fun machine() =
            sysml2Model(name = "M") {
                val start = stateDef(name = "Start", isInitial = true)
                val on = stateDef(name = "On")
                val idle = stateDef(name = "Idle", parent = on)
                val run = stateDef(name = "Running", parent = on)
                history(name = "H", deep = true, parent = on)
                transition(name = "t0", source = start, target = on)
                transition(name = "t1", source = idle, target = run)
                stmDiagram(name = "d") {
                    include(start)
                    include(on)
                }
            }

        test("DSL: kinds, parent ids and region replacement") {
            val m =
                sysml2Model(name = "M") {
                    val par = stateDef(name = "Par")
                    val r = region(owner = par, name = "A")
                    stateDef(name = "X", parent = par, region = r)
                    choice(name = "C")
                    junction(name = "J")
                    fork(name = "F")
                    join(name = "Jn")
                }
            val states = m.definitions.filterIsInstance<StateDefinition>().associateBy { it.id }
            states["Par"]!!.regions shouldContainExactly listOf(StateRegion(id = "Par.A", name = "A"))
            states["X"]!!.parentStateId shouldBe "Par"
            states["X"]!!.regionId shouldBe "Par.A"
            states["C"]!!.effectiveKind shouldBe StateKind.Choice
            states["Jn"]!!.effectiveKind shouldBe StateKind.Join
            m.definitions.size shouldBe 6
        }

        test("DSL: history needs a parent, duplicate regions are rejected") {
            shouldThrow<IllegalArgumentException> { sysml2Model(name = "M") { history(name = "H") } }.message.shouldNotBeNull()
            shouldThrow<IllegalArgumentException> {
                sysml2Model(name = "M") {
                    val p = stateDef(name = "P")
                    region(owner = p, name = "A")
                    region(owner = p, name = "A")
                }
            }
        }

        test("legacy flags win in effectiveKind") {
            StateDefinition(id = "a", name = "a", isInitial = true).effectiveKind shouldBe StateKind.Initial
            StateDefinition(id = "a", name = "a", isFinal = true, kind = StateKind.Choice).effectiveKind shouldBe StateKind.Final
        }

        test("JSON: flat state omits new fields, structure round-trips") {
            val json = Json { encodeDefaults = false }
            val flat = json.encodeToString(StateDefinition.serializer(), StateDefinition(id = "a", name = "a"))
            flat shouldNotContain "parentStateId"
            flat shouldNotContain "regions"
            flat shouldNotContain "kind"
            flat shouldNotContain "effectiveKind"
            val s =
                StateDefinition(
                    id = "x",
                    name = "x",
                    kind = StateKind.Fork,
                    parentStateId = "p",
                    regionId = "p.r",
                    regions = listOf(StateRegion(id = "p.r", name = "r")),
                )
            json.decodeFromString(StateDefinition.serializer(), json.encodeToString(StateDefinition.serializer(), s)) shouldBe s
            shouldThrow<Exception> { json.decodeFromString(StateDefinition.serializer(), """{"id":"a","name":"a","kind":"Nope"}""") }
        }

        test("hierarchy helpers are cycle-safe and bounded") {
            val m =
                Sysml2Model(
                    name = "C",
                    definitions =
                        listOf(
                            StateDefinition(id = "a", name = "a", parentStateId = "b"),
                            StateDefinition(id = "b", name = "b", parentStateId = "a"),
                        ),
                )
            m.ancestorsOf(id = "a") shouldContainExactly listOf("b")
            m.descendantsOf(id = "a") shouldContainExactly listOf("b")
            codes(m) shouldContain 120
        }

        test("visibleStateIds expands descendants and ancestors; edge adapter follows") {
            val m = machine()
            val d = m.diagrams.filterIsInstance<StmDiagram>().single()
            d.visibleStateIds(m).toList() shouldContainExactly listOf("Start", "On", "Idle", "Running", "H")
            val onlyChild = StmDiagram(name = "c", elementIds = listOf("Idle", "Running"))
            onlyChild.visibleStateIds(m) shouldContain "On"
            StmEdgeAdapter(model = m, diagram = d).metadataFor("transition:Idle::Running").shouldNotBeNull()
        }

        test("checker: well-formed machine has no errors") {
            Sysml2StmChecker.check(model = machine()).filter { it.severity == StmSeverity.Error } shouldBe emptyList()
        }

        test("checker: fork/join/choice/junction/history rules") {
            val m =
                sysml2Model(name = "M") {
                    val i = stateDef(name = "I", isInitial = true)
                    val f = fork(name = "F")
                    val c = choice(name = "C")
                    val j = junction(name = "J")
                    val fin = stateDef(name = "Fin", isFinal = true)
                    transition(name = "a", source = i, target = f)
                    transition(name = "b", source = fin, target = i)
                    transition(name = "c", source = c, target = j, guard = "x")
                    transition(name = "d", source = c, target = j, guard = "y")
                    history(name = "H", parent = stateDef(name = "P"))
                }
            val got = codes(m)
            listOf(101, 102, 105, 108).forEach { got shouldContain it }
        }

        test("checker: regions - fork into same region, cross-region transition, two initials") {
            val m =
                sysml2Model(name = "M") {
                    val p = stateDef(name = "P")
                    val r1 = region(owner = p, name = "A")
                    val r2 = region(owner = p, name = "B")
                    val a = stateDef(name = "a", parent = p, region = r1)
                    val b = stateDef(name = "b", parent = p, region = r2)
                    val a2 = stateDef(name = "a2", parent = p, region = r1)
                    stateDef(name = "i1", isInitial = true, parent = p, region = r1)
                    stateDef(name = "i2", isInitial = true, parent = p, region = r1)
                    val f = fork(name = "F")
                    transition(name = "x", source = a, target = b)
                    transition(name = "y", source = f, target = a)
                    transition(name = "z", source = f, target = a2)
                    transition(name = "w", source = p, target = a)
                }
            val got = codes(m)
            listOf(103, 113, 114, 115).forEach { got shouldContain it }
        }

        test("checker: dangling parent, pseudo parent, bad region, history without parent") {
            val m =
                Sysml2Model(
                    name = "B",
                    definitions =
                        listOf(
                            StateDefinition(id = "a", name = "a", parentStateId = "ghost"),
                            StateDefinition(id = "c", name = "c", kind = StateKind.Choice),
                            StateDefinition(id = "k", name = "k", parentStateId = "c", regionId = "nope"),
                            StateDefinition(id = "h", name = "h", kind = StateKind.ShallowHistory),
                        ),
                )
            val got = codes(m)
            listOf(110, 116, 117, 118).forEach { got shouldContain it }
        }

        test("checker: limits short-circuit") {
            val deep = (0..17).map { StateDefinition(id = "s$it", name = "s$it", parentStateId = if (it == 0) null else "s${it - 1}") }
            codes(Sysml2Model(name = "D", definitions = deep)) shouldContainExactly listOf(120)
            val many = (0..1000).map { StateDefinition(id = "s$it", name = "s$it") }
            codes(Sysml2Model(name = "M", definitions = many)) shouldContainExactly listOf(122)
            codes(Sysml2Model(name = "N", definitions = listOf(StateDefinition(id = "a", name = "x".repeat(257))))) shouldContainExactly
                listOf(123)
            val regs = StateDefinition(id = "a", name = "a", regions = (0..16).map { StateRegion(id = "a.$it", name = "r$it") })
            codes(Sysml2Model(name = "R", definitions = listOf(regs))) shouldContainExactly listOf(121)
        }

        test("printer emits structure by id, parents first") {
            val dsl = Sysml2DslPrinter.print(machine())
            dsl shouldContain "history(name = \"H\", id = \"H\", deep = true, parentId = \"On\")"
            dsl shouldContain "stateDef(name = \"Idle\", id = \"Idle\", parentId = \"On\")"
            (dsl.indexOf("\"On\"") < dsl.indexOf("\"Idle\"")) shouldBe true
            val withRegion =
                sysml2Model(name = "R") {
                    val p = stateDef(name = "P")
                    region(owner = p, name = "A")
                    fork(name = "F")
                }
            val out = Sysml2DslPrinter.print(withRegion)
            out shouldContain "regionById(ownerId = \"P\", name = \"A\", id = \"P.A\")"
            out shouldContain "fork(name = \"F\", id = \"F\")"
            // never hangs on a parent cycle
            Sysml2DslPrinter.print(
                Sysml2Model(
                    name = "C",
                    definitions =
                        listOf(
                            StateDefinition(id = "a", name = "a", parentStateId = "b"),
                            StateDefinition(id = "b", name = "b", parentStateId = "a"),
                        ),
                ),
            ) shouldContain "stateDef"
        }

        test("model equality: DSL-built machine survives regionById replay") {
            val a = sysml2Model(name = "R") { regionById(ownerId = stateDef(name = "P").id, name = "A") }
            a.definitions
                .filterIsInstance<StateDefinition>()
                .single()
                .regions
                .map { it.id } shouldContainExactly listOf("P.A")
            a.definitions.map { it.id } shouldNotContain "ghost"
        }
    })
