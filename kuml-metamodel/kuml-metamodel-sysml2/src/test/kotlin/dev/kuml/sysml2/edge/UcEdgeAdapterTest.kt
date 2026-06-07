package dev.kuml.sysml2.edge

import dev.kuml.sysml2.UcAssociation
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.UcInclude
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * V2.0.13 — verifies that [UcEdgeAdapter] reports the right
 * stereotype / dash / arrow head per UC edge kind.
 */
class UcEdgeAdapterTest :
    StringSpec({

        val diagram =
            UcDiagram(
                name = "Library",
                elementIds = listOf("Reader", "BorrowBook", "Authenticate", "PayLateFee", "ReturnBook"),
                associations =
                    listOf(
                        UcAssociation(id = "assoc:Reader::BorrowBook", actorId = "Reader", useCaseId = "BorrowBook"),
                    ),
                includes =
                    listOf(
                        UcInclude(
                            id = "include:BorrowBook::Authenticate",
                            sourceUseCaseId = "BorrowBook",
                            targetUseCaseId = "Authenticate",
                        ),
                    ),
                extends =
                    listOf(
                        UcExtend(
                            id = "extend:PayLateFee::ReturnBook",
                            sourceUseCaseId = "PayLateFee",
                            targetUseCaseId = "ReturnBook",
                        ),
                    ),
            )
        val adapter = UcEdgeAdapter(diagram)

        "association edge has no stereotype, no dash, no arrow head" {
            val meta = adapter.metadataFor("assoc:Reader::BorrowBook")!!
            meta.stereotype.shouldBeNull()
            meta.label.shouldBeNull()
            meta.dashArray.shouldBeNull()
            meta.arrowHead shouldBe Sysml2ArrowHead.None
        }

        "include edge has «include» stereotype, dashed line, open angle arrow" {
            val meta = adapter.metadataFor("include:BorrowBook::Authenticate")!!
            meta.stereotype shouldBe "«include»"
            meta.dashArray shouldBe "5 4"
            meta.arrowHead shouldBe Sysml2ArrowHead.OpenAngle
        }

        "extend edge has «extend» stereotype, dashed line, open angle arrow" {
            val meta = adapter.metadataFor("extend:PayLateFee::ReturnBook")!!
            meta.stereotype shouldBe "«extend»"
            meta.dashArray shouldBe "5 4"
            meta.arrowHead shouldBe Sysml2ArrowHead.OpenAngle
        }

        "unknown edge id returns null so the legacy fallback can take over" {
            adapter.metadataFor("unknown:edge").shouldBeNull()
        }
    })
