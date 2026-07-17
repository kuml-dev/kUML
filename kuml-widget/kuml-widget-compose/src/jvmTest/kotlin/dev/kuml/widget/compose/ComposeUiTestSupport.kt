package dev.kuml.widget.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.ocl.OclScope
import dev.kuml.core.ocl.OclType
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.TransitionMetadataKeys
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition

/*
 * Shared fixtures/helpers for Compose UI robot tests of [OclGuardEditor] and
 * [ControlPanel] (Wave 6). Kept in one place so the two UI-test specs and
 * [ChangeGuardBridgeTest] can converge on the same traffic-light model and
 * guard scope, rather than three drifting copies.
 */

/** Mirrors `defaultGuardScope()` in `dev.kuml.runtime` so isolated editor tests use the real scope shape. */
internal val guardScope: OclScope = OclScope(mapOf("event" to OclType.OBJECT, "vars" to OclType.OBJECT))

/**
 * Wraps [content] in a [MaterialTheme] before calling `setContent` — required
 * because `AlertDialog`/`OutlinedTextField` resolve colors from the theme and
 * throw without it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.setThemedContent(content: @Composable () -> Unit) {
    setContent { MaterialTheme { content() } }
}

/**
 * Traffic-light fixture: `init -> Red --next--> Green --next--> Yellow --next--> Red`.
 * `t-red-green` is a plain (non-protected) guarded transition, `t-yellow-red` is protected.
 * Identical to the fixture in [ChangeGuardBridgeTest].
 */
internal fun buildTrafficLight(): UmlStateMachine {
    val initial = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
    val red = UmlState(id = "Red", name = "Red")
    val green = UmlState(id = "Green", name = "Green")
    val yellow = UmlState(id = "Yellow", name = "Yellow")
    return UmlStateMachine(
        id = "traffic-light",
        name = "Traffic Light",
        vertices = listOf(initial, red, green, yellow),
        transitions =
            listOf(
                UmlTransition(id = "t-init-red", sourceId = "init", targetId = "Red"),
                UmlTransition(id = "t-red-green", sourceId = "Red", targetId = "Green", trigger = "next"),
                UmlTransition(id = "t-green-yellow", sourceId = "Green", targetId = "Yellow", trigger = "next"),
                UmlTransition(
                    id = "t-yellow-red",
                    sourceId = "Yellow",
                    targetId = "Red",
                    trigger = "next",
                    metadata = mapOf(TransitionMetadataKeys.PROTECTED to KumlMetaValue.Flag(true)),
                ),
            ),
    )
}

/** Builds a fresh [BehaviourWidgetState] over [buildTrafficLight] with the given [editPolicy]. */
internal fun buildState(editPolicy: EditPolicy = EditPolicy.GuardsOnly): BehaviourWidgetState {
    val model = buildTrafficLight()
    val runtime = StateMachineRuntime()
    return BehaviourWidgetState(initialModel = model, runtime = runtime, editPolicy = editPolicy)
}

/** Looks up a transition by id — convenience for post-condition assertions. */
internal fun UmlStateMachine.transition(id: String): UmlTransition = transitions.first { it.id == id }

/**
 * Flushes [OclGuardEditor]'s debounced `LaunchedEffect(value.text)` re-check
 * (see `OCL_TYPECHECK_DEBOUNCE_MS`) deterministically by advancing the test
 * clock past the debounce window, then waits for the resulting recomposition.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitTypeCheck() {
    mainClock.advanceTimeBy(OCL_TYPECHECK_DEBOUNCE_MS + 50)
    waitForIdle()
}
