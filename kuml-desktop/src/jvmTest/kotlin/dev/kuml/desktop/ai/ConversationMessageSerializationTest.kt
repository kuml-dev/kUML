package dev.kuml.desktop.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

class ConversationMessageSerializationTest :
    FunSpec({
        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        test("User message roundtrip") {
            val msg = ConversationMessage.User(id = "u1", timestamp = 1000L, text = "Hello AI")
            val encoded = json.encodeToString(ConversationMessage.serializer(), msg)
            val decoded = json.decodeFromString<ConversationMessage>(encoded)
            decoded.shouldBeInstanceOf<ConversationMessage.User>()
            decoded.text shouldBe "Hello AI"
            decoded.id shouldBe "u1"
        }

        test("Assistant message roundtrip with defaults") {
            val msg =
                ConversationMessage.Assistant(
                    id = "a1",
                    timestamp = 2000L,
                    text = "I can help!",
                    isStreaming = false,
                    providerId = "ollama",
                    modelId = "llama3.2",
                )
            val encoded = json.encodeToString(ConversationMessage.serializer(), msg)
            val decoded = json.decodeFromString<ConversationMessage>(encoded)
            decoded.shouldBeInstanceOf<ConversationMessage.Assistant>()
            decoded.text shouldBe "I can help!"
            decoded.providerId shouldBe "ollama"
        }

        test("ToolCall message roundtrip") {
            val msg =
                ConversationMessage.ToolCall(
                    id = "tc1",
                    timestamp = 3000L,
                    toolName = "add_class",
                    argsJson = """{"name":"Foo"}""",
                    state = ToolCallState.RUNNING,
                )
            val encoded = json.encodeToString(ConversationMessage.serializer(), msg)
            val decoded = json.decodeFromString<ConversationMessage>(encoded)
            decoded.shouldBeInstanceOf<ConversationMessage.ToolCall>()
            decoded.toolName shouldBe "add_class"
            decoded.state shouldBe ToolCallState.RUNNING
        }

        test("ToolResult message roundtrip") {
            val msg =
                ConversationMessage.ToolResult(
                    id = "tr1",
                    timestamp = 4000L,
                    toolCallId = "tc1",
                    resultJson = """{"ok":true}""",
                    isError = false,
                )
            val encoded = json.encodeToString(ConversationMessage.serializer(), msg)
            val decoded = json.decodeFromString<ConversationMessage>(encoded)
            decoded.shouldBeInstanceOf<ConversationMessage.ToolResult>()
            decoded.toolCallId shouldBe "tc1"
        }

        test("ErrorMessage roundtrip") {
            val msg =
                ConversationMessage.ErrorMessage(
                    id = "e1",
                    timestamp = 5000L,
                    message = "Something went wrong",
                    cause = "NetworkError",
                )
            val encoded = json.encodeToString(ConversationMessage.serializer(), msg)
            val decoded = json.decodeFromString<ConversationMessage>(encoded)
            decoded.shouldBeInstanceOf<ConversationMessage.ErrorMessage>()
            decoded.cause shouldBe "NetworkError"
        }

        test("Extra unknown field is ignored via ignoreUnknownKeys") {
            val jsonWithExtra =
                """
                {"type":"user","id":"u2","timestamp":1234,"text":"Hello","unknownField":"ignored"}
                """.trimIndent()
            val decoded = json.decodeFromString<ConversationMessage>(jsonWithExtra)
            decoded.shouldBeInstanceOf<ConversationMessage.User>()
            decoded.text shouldBe "Hello"
        }

        test("Sealed class polymorphism via @SerialName discriminator") {
            val assistant = """{"type":"assistant","id":"a2","timestamp":5678,"text":"Hi"}"""
            val decoded = json.decodeFromString<ConversationMessage>(assistant)
            decoded.shouldBeInstanceOf<ConversationMessage.Assistant>()
        }
    })
