package dev.kuml.vaultexamples

import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.label.LabelPlacer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL 2B-c: the planned ERM name and role labels keep clear of entities, other labels and every foreign line, and
 * stay at their own line. Geometry only (layout rectangles and polylines), so it holds on every machine.
 */
class ErmLabelGeometryInvariantTest :
    FunSpec({
        val examples = VaultExampleLoader.loadFromClasspath().filter { it.baseName.contains(" ERM") || it.fileName.startsWith("39 ") }

        fun Rect.hits(o: Rect) =
            origin.x < o.origin.x + o.size.width &&
                o.origin.x < origin.x + size.width &&
                origin.y < o.origin.y + o.size.height &&
                o.origin.y < origin.y + size.height

        fun violations(script: String): List<String>? {
            val (model, layout) = VaultExampleRenderer.ermLayoutOf(script = script, notation = ErmNotation.MARTIN) ?: return null
            val g = KumlSvgRenderer.ermLabelGeometry(model = model, layoutResult = layout)
            val out = ArrayList<String>()
            val all = g.labels.flatMap { (edge, ls) -> ls.map { (k, l) -> Triple(edge, k, l) } }.sortedBy { it.first + it.second }
            for ((edge, key, label) in all) {
                val r = label.rect
                for ((id, n) in g.nodes) if (r.hits(n)) out += "$edge#$key covers node $id"
                for ((e2, k2, l2) in all) if ((e2 + k2) > (edge + key) && r.hits(l2.rect)) out += "$edge#$key overlaps $e2#$k2"
                for ((id, pts) in g.routes) {
                    val d = LabelPlacer.rectDistanceToPolyline(r = r, pts = pts)
                    if (id == edge) {
                        if (!label.onLine && d < 0.5f) out += "$edge#$key touches its own line without halo"
                    } else if (d < 0.5f) {
                        out += "$edge#$key crosses foreign line $id"
                    }
                }
            }
            return out
        }

        test("the ERM examples are present") { (examples.size >= 2) shouldBe true }

        test("no ERM name or role label covers an entity or label, crosses a line, or sits on its own line unhaloed") {
            var checked = 0
            for (example in examples) {
                val v = violations(example.kumlScript) ?: continue
                checked++
                v.joinToString("\n") { "${example.sanitizedName}: $it" } shouldBe ""
            }
            (checked >= 2) shouldBe true
        }

        test("example 39 (Martin): 'contains' and 'reviewed via' are planned, the self reference included") {
            val martin = examples.first { it.fileName.startsWith("39 ") }
            val (model, layout) = VaultExampleRenderer.ermLayoutOf(script = martin.kumlScript)!!
            val g = KumlSvgRenderer.ermLabelGeometry(model = model, layoutResult = layout)
            val named = model.relationships.filter { !it.name.isNullOrBlank() }.map { it.id }
            (named.isNotEmpty()) shouldBe true
            named.forEach { (g.labels[it]?.containsKey("center") == true) shouldBe true }
            model.relationships.any { it.sourceEntityId == it.targetEntityId } shouldBe true
        }
    })
