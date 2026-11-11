package dev.kuml.layout.bridge

import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/** Nacharbeit 66: head text rules shared by the width providers and the renderers. */
class SeqHeadTextTest :
    StringSpec({
        "short names stay one line" {
            seqHeadLines(name = "Client") shouldContainExactly listOf("Client")
            seqHeadLines(name = "Alpha Beta") shouldContainExactly listOf("Alpha Beta")
        }

        "the first newline is a hard break, short halves do not wrap further" {
            seqHeadLines(name = "First\nSecond") shouldContainExactly listOf("First", "Second")
            seqHeadLines(name = "First\nSecond\nThird") shouldContainExactly listOf("First", "Second Third")
        }

        "long names wrap at a space, not inside a word" {
            seqHeadLines(name = "Alpha Beta Gamma Delta Epsilon Zeta") shouldContainExactly listOf("Alpha Beta Gamma Delta", "Epsilon Zeta")
        }

        "wrapping also happens after a hyphen or slash" {
            seqHeadLines(name = "Alpha-Beta-Gamma-Delta-Epsilon-Zeta") shouldContainExactly
                listOf("Alpha-Beta-Gamma-Delta-", "Epsilon-Zeta")
            seqHeadLines(name = "Alpha/Beta/Gamma/Delta/Epsilon").size shouldBe 2
        }

        "a camel-case name is not split" {
            seqHeadLines(name = "AlphaBetaGammaDeltaEpsilonZetaEta") shouldContainExactly listOf("AlphaBetaGammaDeltaEpsilonZetaEta")
        }

        "more than two lines are cut with an ellipsis" {
            val lines = seqHeadLines(name = "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda")
            lines.size shouldBe 2
            lines[1].endsWith("…") shouldBe true
            (lines[1].length <= 24) shouldBe true
        }

        "whitespace is normalized" {
            seqHeadLines(name = "  Alpha \t Beta  ") shouldContainExactly listOf("Alpha Beta")
            seqHeadFullName(name = "Alpha\nBeta") shouldBe "Alpha Beta"
        }

        "width stays within 240 and short names keep 140" {
            seqHeadWidth(name = "Client", showKeyword = true) shouldBe Sysml2LayoutBridge.SEQ_LIFELINE_WIDTH
            seqHeadWidth(name = "Alpha Beta Gamma Delta Epsilon Zeta", showKeyword = false) shouldBeLessThanOrEqual SEQ_HEAD_MAX_WIDTH
            seqHeadWidth(name = "AlphaBetaGammaDeltaEpsilonZetaEtaThetaIota", showKeyword = true) shouldBe SEQ_HEAD_MAX_WIDTH
        }

        "the SysML provider uses the shared rule" {
            val model =
                sysml2Model(name = "Heads") {
                    lifelineDef(name = "Alpha Beta Gamma Delta Epsilon Zeta")
                    lifelineDef(name = "Client")
                }
            val provider = Sysml2LayoutBridge.seqContentAwareSizeProvider(model)
            val widths =
                model.definitions.associate { it.name to provider.sizeOf(elementId = it.id, elementKind = "LifelineDefinition").width }
            widths["Client"] shouldBe Sysml2LayoutBridge.SEQ_LIFELINE_WIDTH
            widths["Alpha Beta Gamma Delta Epsilon Zeta"] shouldBe
                seqHeadWidth(name = "Alpha Beta Gamma Delta Epsilon Zeta", showKeyword = false)
        }
    })
