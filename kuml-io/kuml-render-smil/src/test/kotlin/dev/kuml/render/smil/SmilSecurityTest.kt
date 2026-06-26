package dev.kuml.render.smil

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Security tests for the SMIL module.
 *
 * Covers:
 * - XML attribute-name injection via [SmilAnimation.Animate.attribute] and
 *   [SmilAnimation.Set.attribute] (allowlist enforcement).
 * - DoS via unbounded animation list ([BuildOptions.maxAnimations] cap).
 */
class SmilSecurityTest :
    StringSpec({

        // ── Attribute-name injection ──────────────────────────────────────────

        "Animate rejects attribute not in ALLOWED_ATTRIBUTE_NAMES" {
            shouldThrow<IllegalArgumentException> {
                SmilAnimation.Animate(
                    elementId = "el1",
                    attribute = "opacity\" onload=\"evil",
                    from = "1",
                    to = "0",
                    beginMs = 0L,
                    durationMs = 600L,
                )
            }
        }

        "Set rejects attribute not in ALLOWED_ATTRIBUTE_NAMES" {
            shouldThrow<IllegalArgumentException> {
                SmilAnimation.Set(
                    elementId = "el2",
                    attribute = "visibility\" onload=\"evil",
                    to = "hidden",
                    beginMs = 0L,
                    durationMs = 600L,
                )
            }
        }

        "Animate rejects unknown attribute name" {
            shouldThrow<IllegalArgumentException> {
                SmilAnimation.Animate(
                    elementId = "el3",
                    attribute = "data-custom",
                    from = "0",
                    to = "1",
                    beginMs = 0L,
                    durationMs = 600L,
                )
            }
        }

        "Set rejects unknown attribute name" {
            shouldThrow<IllegalArgumentException> {
                SmilAnimation.Set(
                    elementId = "el4",
                    attribute = "onload",
                    to = "evil()",
                    beginMs = 0L,
                    durationMs = 600L,
                )
            }
        }

        "Animate accepts all attributes in ALLOWED_ATTRIBUTE_NAMES" {
            for (attr in SmilAnimation.ALLOWED_ATTRIBUTE_NAMES) {
                SmilAnimation.Animate(
                    elementId = "el",
                    attribute = attr,
                    from = "0",
                    to = "1",
                    beginMs = 0L,
                    durationMs = 600L,
                )
            }
            // if no exception was thrown all allowed names are accepted
        }

        "Set accepts all attributes in ALLOWED_ATTRIBUTE_NAMES" {
            for (attr in SmilAnimation.ALLOWED_ATTRIBUTE_NAMES) {
                SmilAnimation.Set(
                    elementId = "el",
                    attribute = attr,
                    to = "value",
                    beginMs = 0L,
                    durationMs = 600L,
                )
            }
        }

        "SmilEmitter does not embed raw injection payloads even via value fields" {
            // attribute value (not name) injection is handled by SmilXml.attr() escaping
            val emitter = SmilEmitter()
            val timeline =
                SmilTimeline(
                    listOf(
                        SmilAnimation.Animate(
                            elementId = "el",
                            attribute = "opacity",
                            from = "\" onload=\"evil",
                            to = "1",
                            beginMs = 0L,
                            durationMs = 600L,
                        ),
                    ),
                )
            val fragment = emitter.renderElements(timeline)
            fragment shouldContain "&quot;"
            fragment shouldNotContain "onload=\"evil\""
        }

        // ── Unbounded animation list cap ──────────────────────────────────────

        "SmilTimelineBuilder throws when animation count exceeds maxAnimations" {
            val builder = SmilTimelineBuilder()
            val ts = "1970-01-01T00:00:00Z"
            // Each StateEntered produces 1 animation; generate maxAnimations+1 entries
            val limit = 5
            val entries =
                (0..limit).map { i ->
                    TraceEntry.StateEntered(seqNo = i.toLong(), timestamp = ts, vertexId = "s$i")
                }
            val trace = TraceFile(entries = entries)
            val options = BuildOptions(maxAnimations = limit)
            shouldThrow<IllegalArgumentException> {
                builder.build(trace, options)
            }
        }

        "SmilTimelineBuilder succeeds exactly at maxAnimations" {
            val builder = SmilTimelineBuilder()
            val ts = "1970-01-01T00:00:00Z"
            val limit = 3
            val entries =
                (0 until limit).map { i ->
                    TraceEntry.StateEntered(seqNo = i.toLong(), timestamp = ts, vertexId = "s$i")
                }
            val trace = TraceFile(entries = entries)
            val options = BuildOptions(maxAnimations = limit)
            val timeline = builder.build(trace, options)
            timeline.animations.size shouldBe limit
        }

        "BuildOptions.DEFAULT_MAX_ANIMATIONS is 10000" {
            BuildOptions.DEFAULT_MAX_ANIMATIONS shouldBe 10_000
        }

        "BuildOptions default maxAnimations matches DEFAULT_MAX_ANIMATIONS" {
            BuildOptions().maxAnimations shouldBe BuildOptions.DEFAULT_MAX_ANIMATIONS
        }
    })
