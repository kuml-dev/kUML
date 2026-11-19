package dev.kuml.desktop.mcp

import dev.kuml.mcp.tools.McpToolException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

class McpUiBridgeCancelTest :
    FunSpec({
        test("cancelOnTimeout cancels the orphaned job on OPERATION_TIMEOUT; the default keeps it running") {
            val bridge = McpUiBridge(uiDispatcher = Dispatchers.Default, uiTimeoutMs = 2_000)
            for (cancel in listOf(true, false)) {
                val done = AtomicBoolean(false)
                val e =
                    shouldThrow<McpToolException> {
                        bridge.onUiSuspend(totalTimeoutMs = 100, cancelOnTimeout = cancel) {
                            delay(600)
                            done.set(true)
                        }
                    }
                e.message!!.contains("OPERATION_TIMEOUT") shouldBe true
                Thread.sleep(900)
                done.get() shouldBe !cancel
            }
        }
    })
