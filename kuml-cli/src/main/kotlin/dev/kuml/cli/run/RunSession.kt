package dev.kuml.cli.run

import dev.kuml.runtime.StateMachineInstance
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.activity.ActivityInstance
import dev.kuml.runtime.activity.ActivityRuntime
import dev.kuml.runtime.activity.ActivityRuntimeSpec

internal sealed class RunSession {
    data class Stm(
        val runtime: StateMachineRuntime,
        val instance: StateMachineInstance,
    ) : RunSession()

    data class Act(
        val runtime: ActivityRuntime,
        val spec: ActivityRuntimeSpec,
        val instance: ActivityInstance,
    ) : RunSession()
}
