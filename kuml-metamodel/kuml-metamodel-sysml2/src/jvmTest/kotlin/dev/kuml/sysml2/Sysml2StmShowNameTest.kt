package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.StmSeverity
import dev.kuml.sysml2.constraint.Sysml2StmChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.EdgeLabelKind
import dev.kuml.sysml2.edge.StmEdgeAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

class Sysml2StmShowNameTest :
    FunSpec({
        fun model(
            name: String = "T37",
            showName: Boolean = true,
        ) = sysml2Model(name = "M") {
            val a = stateDef(name = "A")
            val b = stateDef(name = "B")
            transition(
                name = name,
                source = a,
                target = b,
                trigger = "tick",
                guard = "g",
                effect = "e",
                timer = after(10.seconds),
                id = "t37",
                showName = showName,
            )
        }

        fun parts(m: Sysml2Model) = StmEdgeAdapter.transitionLabelParts(m.usages.filterIsInstance<TransitionUsage>().single())

        test("label parts: name, combined trigger and timer, guard, effect") {
            parts(model()).map { it.text } shouldBe listOf("T37:", "tick, after(10s)", "[g]", "/ e")
            parts(model()).first().kind shouldBe EdgeLabelKind.Name
        }

        test("without showName no name part") {
            parts(model(showName = false)).map { it.text } shouldBe listOf("tick, after(10s)", "[g]", "/ e")
        }

        test("printer emits showName only when true and round-trips through the model") {
            Sysml2DslPrinter.print(model()) shouldContain "showName = true"
            Sysml2DslPrinter.print(model(showName = false)) shouldNotContain "showName"
        }

        test("JSON omits showName=false (old form) and keeps true") {
            val json = Json { encodeDefaults = false }
            json.encodeToString(model(showName = false)) shouldNotContain "showName"
            json.encodeToString(model()) shouldContain "\"showName\":true"
            json
                .decodeFromString<Sysml2Model>(json.encodeToString(model()))
                .usages
                .filterIsInstance<TransitionUsage>()
                .single()
                .showName shouldBe true
        }

        test("checker: no 124 for trigger plus timer, 130 for blank name with showName") {
            val ok = Sysml2StmChecker.check(model = model()).map { it.code }
            ok shouldNotContain 124
            ok shouldNotContain 130
            val bad = Sysml2StmChecker.check(model = model(name = "  "))
            bad.map { it.code } shouldContain 130
            bad.first { it.code == 130 }.severity shouldBe StmSeverity.Warning
        }
    })
