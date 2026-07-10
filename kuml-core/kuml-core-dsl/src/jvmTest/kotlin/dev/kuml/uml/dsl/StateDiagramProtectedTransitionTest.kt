package dev.kuml.uml.dsl

import dev.kuml.core.dsl.stateDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.TransitionMetadataKeys
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.isProtected
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe

class StateDiagramProtectedTransitionTest :
    FunSpec(body = {

        test(name = "transition { protected = true } sets metadata flag and isProtected") {
            val d =
                stateDiagram("X") {
                    val a = state("A")
                    val b = state("B")
                    transition(a, b) { protected = true }
                }
            val t = (d.elements.single() as UmlStateMachine).transitions.single()
            t.metadata[TransitionMetadataKeys.PROTECTED] shouldBe KumlMetaValue.Flag(true)
            t.isProtected.shouldBeTrue()
        }

        test(name = "default transition (no protected set) has empty metadata and isProtected == false") {
            val d =
                stateDiagram("X") {
                    val a = state("A")
                    val b = state("B")
                    transition(a, b) {}
                }
            val t = (d.elements.single() as UmlStateMachine).transitions.single()
            t.metadata.shouldNotContainKey(TransitionMetadataKeys.PROTECTED)
            t.isProtected.shouldBeFalse()
        }

        test(name = "protected = true composes with guard/trigger/effect without clobbering them") {
            val d =
                stateDiagram("X") {
                    val a = state("A")
                    val b = state("B")
                    transition(a, b) {
                        trigger = "confirm()"
                        guard = "[valid]"
                        effect = "log()"
                        protected = true
                    }
                }
            val t = (d.elements.single() as UmlStateMachine).transitions.single()
            t.trigger shouldBe "confirm()"
            t.guard shouldBe "[valid]"
            t.effect shouldBe "log()"
            t.isProtected.shouldBeTrue()
        }

        test(name = "protected = false explicitly set omits the metadata key") {
            val d =
                stateDiagram("X") {
                    val a = state("A")
                    val b = state("B")
                    transition(a, b) { protected = false }
                }
            val t = (d.elements.single() as UmlStateMachine).transitions.single()
            t.metadata.shouldNotContainKey(TransitionMetadataKeys.PROTECTED)
        }
    })
