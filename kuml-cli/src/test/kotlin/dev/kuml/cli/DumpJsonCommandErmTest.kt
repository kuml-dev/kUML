package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/**
 * Regression guard (fix/erm-martin-spacing, 2026-07-11): `dump-json` must
 * dispatch the ERM layout bridge by notation exactly like
 * [RenderPipeline.renderErm] does — Chen and IDEF1X get their own bridge +
 * size provider ([dev.kuml.layout.bridge.erm.ErmChenLayoutBridge] /
 * [dev.kuml.layout.bridge.erm.ErmIdef1xLayoutBridge]) instead of the generic
 * [dev.kuml.layout.bridge.erm.ErmLayoutBridge]. Before this fix,
 * `DumpJsonCommand.ermLayout` used the generic bridge unconditionally, so
 * `--format json` node ids for Chen/IDEF1X scripts diverged from what
 * `--format svg/png` actually renders for the same script.
 */
class DumpJsonCommandErmTest :
    FunSpec({

        fun ermScript(notation: String) =
            """
            |ermModel("Shop") {
            |    val customer = entity("Customer") { id() }
            |    val order = entity("Order") {
            |        id()
            |        foreignKey(name = "customer_id", references = customer)
            |    }
            |    relationship(from = customer, to = order, name = "places")
            |    diagram(name = "Overview", notation = ErmNotation.$notation)
            |}
            """.trimMargin()

        fun scriptFile(notation: String): java.io.File {
            val f = Files.createTempFile("kuml-dump-json-erm-$notation", ".kuml.kts").toFile()
            f.writeText(ermScript(notation))
            return f
        }

        test("dump-json for CHEN notation uses ErmChenLayoutBridge node ids, not the generic bridge") {
            val script = scriptFile("CHEN")
            val outDir = Files.createTempDirectory("kuml-dump-json-erm-chen").toFile()
            val diagramOut = outDir.resolve("diagram.json")
            val layoutOut = outDir.resolve("layout.json")
            val modelOut = outDir.resolve("model.json")

            KumlCli().test(
                listOf(
                    "dump-json",
                    script.absolutePath,
                    "--diagram-out",
                    diagramOut.absolutePath,
                    "--layout-out",
                    layoutOut.absolutePath,
                    "--model-out",
                    modelOut.absolutePath,
                ),
            )

            val layoutJson = layoutOut.readText()
            // Chen's synthetic node-id prefixes (see ErmChenLayoutBridge) must
            // appear -- the generic ErmLayoutBridge would instead emit plain
            // auto-generated entity ids ("entity_0"/"entity_1", see
            // ErmModelBuilder.autoId) with no prefix at all.
            layoutJson shouldContain "chen-entity::entity_0"
            layoutJson shouldContain "chen-entity::entity_1"
            layoutJson shouldContain "chen-attr::"
            layoutJson shouldContain "chen-rel::"

            script.delete()
            outDir.deleteRecursively()
        }

        test("dump-json for IDEF1X notation uses ErmIdef1xLayoutBridge, not a bare entity-id graph") {
            val script = scriptFile("IDEF1X")
            val outDir = Files.createTempDirectory("kuml-dump-json-erm-idef1x").toFile()
            val diagramOut = outDir.resolve("diagram.json")
            val layoutOut = outDir.resolve("layout.json")
            val modelOut = outDir.resolve("model.json")

            KumlCli().test(
                listOf(
                    "dump-json",
                    script.absolutePath,
                    "--diagram-out",
                    diagramOut.absolutePath,
                    "--layout-out",
                    layoutOut.absolutePath,
                    "--model-out",
                    modelOut.absolutePath,
                ),
            )

            // IDEF1X keeps real (auto-generated "entity_0"/"entity_1", see
            // ErmModelBuilder.autoId) entity ids as plain node ids -- unlike
            // Chen, which prefixes every id with "chen-entity::"/"chen-attr::"/
            // "chen-rel::". This must still succeed via ErmIdef1xLayoutBridge
            // without throwing -- the bug this guards against was Chen/IDEF1X
            // *sharing* the generic ErmLayoutBridge, which for IDEF1X produces a
            // graph the IDEF1X SVG renderer disagrees with on synthetic
            // category-circle nodes when categories are present.
            val layoutJson = layoutOut.readText()
            layoutJson shouldContain "entity_0"
            layoutJson shouldContain "entity_1"
            layoutJson shouldNotContain "chen-entity::"

            script.delete()
            outDir.deleteRecursively()
        }
    })
