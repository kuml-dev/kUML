package dev.kuml.mcp.tools

import dev.kuml.mcp.McpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

class KumlAuthoringToolsTest :
    FunSpec({
        test("exposes exactly the four stateless authoring tools") {
            KumlAuthoringTools.all.map { it.descriptor.name } shouldBe
                listOf("kuml.describe", "kuml.examples", "kuml.generate", "kuml.list_elements")
        }

        test("stdio tools/list output is unchanged: 11 tools, no annotations key") {
            val response = McpServer.handleLine("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!
            val raw = McpServer.json.encodeToString(response)
            raw shouldNotContain "annotations"
            ToolRegistry.descriptors.map { it.name } shouldBe
                listOf(
                    "kuml.render",
                    "kuml.validate",
                    "kuml.list_elements",
                    "kuml.describe",
                    "kuml.generate",
                    "kuml.examples",
                    "kuml.run.start",
                    "kuml.run.event",
                    "kuml.run.snapshot",
                    "kuml.run.patch",
                    "kuml.run.stop",
                )
            response.result!!.jsonObject.containsKey("tools") shouldBe true
        }

        test("stdio initialize still reports 2024-11-05 and kuml-mcp") {
            val raw =
                McpServer.json.encodeToString(
                    McpServer.handleLine("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")!!,
                )
            raw.contains("\"protocolVersion\":\"2024-11-05\"") shouldBe true
            raw.contains("\"name\":\"kuml-mcp\"") shouldBe true
            raw.contains("\"resources\"") shouldBe true
        }
    })
