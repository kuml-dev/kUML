package dev.kuml.runtime.chain.wasm

import dev.kuml.runtime.chain.wasm.ink.InkAbiException
import dev.kuml.runtime.chain.wasm.ink.InkAbiMetadata
import dev.kuml.runtime.chain.wasm.ink.InkTypeDef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** Minimales ink! v5 Metadata-Fixture mit signature_topic. */
private val V5_FIXTURE =
    """
{
  "version": "5",
  "types": [
    {"id": 1, "type": {"def": {"primitive": "u32"}}},
    {"id": 2, "type": {"def": {"primitive": "bool"}}},
    {"id": 3, "type": {"def": {"primitive": "u8"}}},
    {"id": 4, "type": {"def": {"array": {"len": 32, "type": 3}}}},
    {"id": 5, "type": {"def": {"sequence": {"type": 3}}}},
    {"id": 6, "type": {"def": {"composite": {"fields": [{"name": "x", "type": 1}, {"name": "y", "type": 2}]}}}}
  ],
  "spec": {
    "events": [
      {
        "label": "Transfer",
        "signature_topic": "0xABCD1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234",
        "args": [
          {"label": "from", "type": {"type": 4}, "indexed": true},
          {"label": "to", "type": {"type": 4}, "indexed": true},
          {"label": "value", "type": {"type": 1}, "indexed": false}
        ]
      },
      {
        "label": "Approval",
        "signature_topic": "0xDEADBEEFdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
        "args": [
          {"label": "amount", "type": {"type": 1}, "indexed": false}
        ]
      }
    ],
    "messages": [
      {"label": "transfer", "selector": "0x633aa551", "mutates": true},
      {"label": "balance_of", "selector": "0x0f755a56", "mutates": false}
    ]
  }
}
    """.trimIndent()

/** Minimales ink! v4 Fixture ohne signature_topic. */
private val V4_FIXTURE =
    """
{
  "version": "4",
  "types": [
    {"id": 1, "type": {"def": {"primitive": "u64"}}}
  ],
  "spec": {
    "events": [
      {
        "label": "Deposited",
        "args": [
          {"label": "amount", "type": {"type": 1}, "indexed": false}
        ]
      },
      {
        "label": "Withdrawn",
        "args": []
      }
    ],
    "messages": []
  }
}
    """.trimIndent()

class InkAbiMetadataTest :
    FunSpec({

        test("parse v5 fixture: version, event count, message count") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            abi.version shouldBe "5"
            abi.events.size shouldBe 2
            abi.messages.size shouldBe 2
        }

        test("parse v5 fixture: event labels and indices") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            abi.events[0].label shouldBe "Transfer"
            abi.events[0].index shouldBe 0
            abi.events[1].label shouldBe "Approval"
            abi.events[1].index shouldBe 1
        }

        test("parse v5 fixture: message selector and mutates flag") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            val transfer = abi.messages.first { it.label == "transfer" }
            transfer.selector shouldBe "0x633aa551"
            transfer.mutates shouldBe true
            val balanceOf = abi.messages.first { it.label == "balance_of" }
            balanceOf.mutates shouldBe false
        }

        test("eventBySignatureTopic: case-insensitive + 0x-prefix-tolerant") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            // exact match
            abi
                .eventBySignatureTopic("0xABCD1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234")
                .shouldNotBeNull()
                .label shouldBe "Transfer"
            // lowercase without 0x
            abi
                .eventBySignatureTopic("abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234")
                .shouldNotBeNull()
                .label shouldBe "Transfer"
            // uppercase
            abi
                .eventBySignatureTopic("0xABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234")
                .shouldNotBeNull()
                .label shouldBe "Transfer"
        }

        test("eventBySignatureTopic: unknown topic → null") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            abi
                .eventBySignatureTopic("0x0000000000000000000000000000000000000000000000000000000000000000")
                .shouldBeNull()
        }

        test("eventByIndex: v4 fixture (0-based)") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V4_FIXTURE))
            abi.eventByIndex(0).shouldNotBeNull().label shouldBe "Deposited"
            abi.eventByIndex(1).shouldNotBeNull().label shouldBe "Withdrawn"
            abi.eventByIndex(2).shouldBeNull()
        }

        test("typeOf: u32 primitive") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            val t = abi.typeOf(1)
            (t as InkTypeDef.Primitive).name shouldBe "u32"
        }

        test("typeOf: bool primitive") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            val t = abi.typeOf(2)
            (t as InkTypeDef.Primitive).name shouldBe "bool"
        }

        test("typeOf: fixed array [u8; 32] → AccountId32") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            val t = abi.typeOf(4) as InkTypeDef.FixedArray
            t.len shouldBe 32
            t.elementTypeId shouldBe 3
        }

        test("typeOf: sequence<u8>") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            val t = abi.typeOf(5) as InkTypeDef.Sequence
            t.elementTypeId shouldBe 3
        }

        test("typeOf: composite with named fields") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            val t = abi.typeOf(6) as InkTypeDef.Composite
            t.fields.size shouldBe 2
            t.fields[0].name shouldBe "x"
            t.fields[0].typeId shouldBe 1
        }

        test("typeOf: unknown id → InkTypeDef.Unknown") {
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(V5_FIXTURE))
            (abi.typeOf(9999) is InkTypeDef.Unknown) shouldBe true
        }

        test("parse: missing 'spec' → InkAbiException") {
            val noSpec = """{"version":"5","types":[],"spec_missing":{}}"""
            val ex =
                shouldThrow<InkAbiException> {
                    InkAbiMetadata.parse(json.parseToJsonElement(noSpec))
                }
            ex.message shouldContain "spec"
        }

        test("parse: event missing label → InkAbiException") {
            val noLabel =
                """
                {
                  "version":"5","types":[],
                  "spec":{"events":[{"args":[]}],"messages":[]}
                }
                """.trimIndent()
            val ex =
                shouldThrow<InkAbiException> {
                    InkAbiMetadata.parse(json.parseToJsonElement(noLabel))
                }
            ex.message shouldContain "label"
        }

        // -------------------------------------------------------------------------
        // ink! v4 "fields" backward-compatibility (Major fix)
        // -------------------------------------------------------------------------

        test("parse: ink! v4 event using 'fields' instead of 'args' is parsed correctly") {
            // Aeltere ink! v4 Contracts verwendeten 'fields' statt 'args' fuer Event-Argumente.
            // Ohne den Fix werden diese Events als 'keine Felder' geparst → stille Decode-Fehler.
            val v4WithFields =
                """
                {
                  "version":"4",
                  "types":[{"id":1,"type":{"def":{"primitive":"u64"}}}],
                  "spec":{
                    "events":[{
                      "label":"LegacyEvent",
                      "fields":[
                        {"label":"amount","type":{"type":1},"indexed":false}
                      ]
                    }],
                    "messages":[]
                  }
                }
                """.trimIndent()
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(v4WithFields))
            abi.events[0].label shouldBe "LegacyEvent"
            // Ohne den Fix wuerde fields.size = 0 zurueckgegeben
            abi.events[0].fields.size shouldBe 1
            abi.events[0].fields[0].name shouldBe "amount"
            abi.events[0].fields[0].typeId shouldBe 1
            abi.events[0].fields[0].indexed shouldBe false
        }

        test("parse: 'args' takes precedence over 'fields' if both present") {
            val both =
                """
                {
                  "version":"5",
                  "types":[{"id":1,"type":{"def":{"primitive":"u32"}}},{"id":2,"type":{"def":{"primitive":"u64"}}}],
                  "spec":{
                    "events":[{
                      "label":"DualEvent",
                      "args":[{"label":"v1","type":{"type":1},"indexed":false}],
                      "fields":[{"label":"v2","type":{"type":2},"indexed":false}]
                    }],
                    "messages":[]
                  }
                }
                """.trimIndent()
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(both))
            // 'args' hat Vorrang
            abi.events[0].fields.size shouldBe 1
            abi.events[0].fields[0].name shouldBe "v1"
        }

        test("parse: missing optional fields (mutates, indexed) default without exception") {
            val minimalEvent =
                """
                {
                  "version":"5","types":[{"id":1,"type":{"def":{"primitive":"u32"}}}],
                  "spec":{
                    "events":[{"label":"Evt","args":[{"label":"v","type":{"type":1}}]}],
                    "messages":[{"label":"foo","selector":"0x12345678"}]
                  }
                }
                """.trimIndent()
            val abi = InkAbiMetadata.parse(json.parseToJsonElement(minimalEvent))
            abi.events[0].fields[0].indexed shouldBe false
            abi.messages[0].mutates shouldBe false
        }
    })
