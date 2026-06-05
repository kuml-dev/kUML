package dev.kuml.layout.bridge

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.layout.NodeHints
import dev.kuml.layout.NodeId
import dev.kuml.layout.RelativeConstraint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class HintsReaderTest :
    FunSpec({

        test("HintsReader returns defaults for empty metadata") {
            val hints = HintsReader.read(emptyMap())
            hints shouldBe NodeHints.NONE
            hints.gridCol shouldBe null
            hints.gridRow shouldBe null
            hints.gridColSpan shouldBe 1
            hints.gridRowSpan shouldBe 1
            hints.pinned shouldBe false
            hints.relative shouldBe emptyList()
        }

        test("HintsReader reads full hints") {
            val metadata: Map<String, KumlMetaValue> =
                mapOf(
                    BridgeLayoutKeys.GRID_COL to KumlMetaValue.Integer(2),
                    BridgeLayoutKeys.GRID_ROW to KumlMetaValue.Integer(3),
                    BridgeLayoutKeys.GRID_COL_SPAN to KumlMetaValue.Integer(2),
                    BridgeLayoutKeys.GRID_ROW_SPAN to KumlMetaValue.Integer(2),
                    BridgeLayoutKeys.PINNED to KumlMetaValue.Flag(true),
                    BridgeLayoutKeys.RELATIVE to
                        KumlMetaValue.Items(
                            listOf(
                                KumlMetaValue.Entries(
                                    mapOf(
                                        BridgeLayoutKeys.REL_KIND to KumlMetaValue.Text("above"),
                                        BridgeLayoutKeys.REL_OTHER to KumlMetaValue.Text("node-42"),
                                    ),
                                ),
                            ),
                        ),
                )

            val hints = HintsReader.read(metadata)

            hints.gridCol shouldBe 2
            hints.gridRow shouldBe 3
            hints.gridColSpan shouldBe 2
            hints.gridRowSpan shouldBe 2
            hints.pinned shouldBe true
            hints.relative shouldHaveSize 1
            hints.relative[0] shouldBe RelativeConstraint.Above(NodeId("node-42"))
        }

        test("HintsReader tolerates malformed values") {
            val metadata: Map<String, KumlMetaValue> =
                mapOf(
                    // gridCol is Text instead of Integer — should be ignored
                    BridgeLayoutKeys.GRID_COL to KumlMetaValue.Text("not-a-number"),
                    // RELATIVE contains non-Entries items — should be ignored
                    BridgeLayoutKeys.RELATIVE to
                        KumlMetaValue.Items(
                            listOf(
                                KumlMetaValue.Text("this-is-not-an-entries-map"),
                            ),
                        ),
                )

            val hints = HintsReader.read(metadata)

            hints.gridCol shouldBe null
            hints.gridColSpan shouldBe 1
            hints.relative shouldBe emptyList()
        }

        test("HintsReader maps all 6 relative constraint kinds") {
            fun entry(
                kind: String,
                other: String,
            ): KumlMetaValue.Entries =
                KumlMetaValue.Entries(
                    mapOf(
                        BridgeLayoutKeys.REL_KIND to KumlMetaValue.Text(kind),
                        BridgeLayoutKeys.REL_OTHER to KumlMetaValue.Text(other),
                    ),
                )

            val metadata: Map<String, KumlMetaValue> =
                mapOf(
                    BridgeLayoutKeys.RELATIVE to
                        KumlMetaValue.Items(
                            listOf(
                                entry("above", "a"),
                                entry("below", "b"),
                                entry("leftOf", "c"),
                                entry("rightOf", "d"),
                                entry("sameRowAs", "e"),
                                entry("sameColAs", "f"),
                            ),
                        ),
                )

            val hints = HintsReader.read(metadata)

            hints.relative shouldHaveSize 6
            hints.relative[0] shouldBe RelativeConstraint.Above(NodeId("a"))
            hints.relative[1] shouldBe RelativeConstraint.Below(NodeId("b"))
            hints.relative[2] shouldBe RelativeConstraint.LeftOf(NodeId("c"))
            hints.relative[3] shouldBe RelativeConstraint.RightOf(NodeId("d"))
            hints.relative[4] shouldBe RelativeConstraint.SameRowAs(NodeId("e"))
            hints.relative[5] shouldBe RelativeConstraint.SameColAs(NodeId("f"))
        }
    })
