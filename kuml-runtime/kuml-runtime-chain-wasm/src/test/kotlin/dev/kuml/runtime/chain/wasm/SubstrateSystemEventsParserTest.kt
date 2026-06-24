package dev.kuml.runtime.chain.wasm

import dev.kuml.runtime.chain.wasm.rpc.SubstrateRpcClient
import dev.kuml.runtime.chain.wasm.rpc.SubstrateSystemEventsParser
import dev.kuml.runtime.chain.wasm.scale.ScaleCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

// Alice's well-known SS58 address on Substrate generic network (prefix 42)
private const val ALICE_SS58 = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"

// Alice's AccountId32 (32 bytes, extracted from SS58)
private val ALICE_ACCOUNT_ID: ByteArray by lazy {
    val decoded = SubstrateRpcClient.base58Decode(ALICE_SS58)!!
    decoded.copyOfRange(1, 33) // prefixLen=1 for network 42
}

/**
 * Baut einen Blob der genau ein ContractEmitted-Event enthaelt.
 * Struktur: [0x00][palletIndex][eventIndex][contractId:32][topicsCompact][topics][dataCompact][data]
 *
 * Der fuehrende 0x00-Byte ist noetig weil der Pattern-Scan bei i=1 startet.
 */
private fun buildContractEmittedBlob(
    contractId: ByteArray,
    topics: List<ByteArray>,
    data: ByteArray,
    palletIndex: Int = SubstrateSystemEventsParser.DEFAULT_CONTRACTS_PALLET_INDEX,
): ByteArray {
    val header = byteArrayOf(0x00)
    val eventByte = SubstrateSystemEventsParser.CONTRACT_EMITTED_EVENT_INDEX.toByte()
    val palletAndEvent = byteArrayOf(palletIndex.toByte(), eventByte)
    val topicsLen = ScaleCodec.encodeCompact(topics.size.toLong())
    val topicsBytes = topics.fold(byteArrayOf()) { acc, t -> acc + t }
    val dataLen = ScaleCodec.encodeCompact(data.size.toLong())
    return header + palletAndEvent + contractId + topicsLen + topicsBytes + dataLen + data
}

/**
 * Unit-Tests fuer [SubstrateSystemEventsParser].
 *
 * Der Parser verwendet einen Pattern-Scan-Ansatz: er sucht Byte-Paare
 * [contractsPalletIndex, CONTRACT_EMITTED_EVENT_INDEX] im SCALE-Blob und versucht
 * ab der Folge-Position einen ContractEmitted-Event zu dekodieren.
 *
 * Diese Tests verifizieren:
 * - Leere / zu kurze Blobs liefern emptyList
 * - Korrekt konstruierter Blob liefert das erwartete Event
 * - Das extrinsicHash-Feld enthaelt NICHT die Contract-Adresse (Critical fix)
 * - Filter nach contractAddress funktioniert korrekt
 * - DoS-Grenzen (MAX_TOPICS, MAX_DATA_BYTES) werden eingehalten
 */
class SubstrateSystemEventsParserTest :
    FunSpec({

        // -------------------------------------------------------------------------
        // Leere / kurze Inputs
        // -------------------------------------------------------------------------

        test("parseContractEmitted: empty string '0x' → emptyList") {
            SubstrateSystemEventsParser.parseContractEmitted("0x", 1L, ALICE_SS58).shouldBeEmpty()
        }

        test("parseContractEmitted: blob < 4 bytes → emptyList") {
            SubstrateSystemEventsParser.parseContractEmitted("0x010203", 1L, ALICE_SS58).shouldBeEmpty()
        }

        test("parseContractEmitted: blob without pallet+event pattern → emptyList") {
            // Kein 0x46 0x00 Muster in diesem Blob
            val blob = ByteArray(50) { 0x01 }
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }
            SubstrateSystemEventsParser.parseContractEmitted(hex, 5L, ALICE_SS58).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Korrekter Blob — Event wird extrahiert (Alice als Contract-Adresse)
        // -------------------------------------------------------------------------

        test("parseContractEmitted: well-formed blob with Alice accountId → 1 event") {
            val topic = ByteArray(32) { 0xAA.toByte() }
            val data = byteArrayOf(0x01, 0x02, 0x03)
            val blob = buildContractEmittedBlob(ALICE_ACCOUNT_ID, listOf(topic), data)
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }

            val results = SubstrateSystemEventsParser.parseContractEmitted(hex, 42L, ALICE_SS58)
            results shouldHaveSize 1
            results[0].blockNumber shouldBe 42L
            results[0].topicsHex[0] shouldBe "0x" + "aa".repeat(32)
            results[0].dataScale shouldBe data
        }

        test("parseContractEmitted: different contractId is filtered out when address given") {
            // Blob mit anderem contractId → wird durch Adressfilter herausgefiltert
            val otherContractId = ByteArray(32) { 0x42.toByte() }
            val blob = buildContractEmittedBlob(otherContractId, emptyList(), byteArrayOf())
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }
            // Alice's SS58 address matcht nicht otherContractId → kein Event
            val results = SubstrateSystemEventsParser.parseContractEmitted(hex, 1L, ALICE_SS58)
            results.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Critical fix: extrinsicHash enthaelt NICHT mehr die Contract-Adresse
        // -------------------------------------------------------------------------

        test("parseContractEmitted: extrinsicHash is NOT the contract accountId hex (Critical fix)") {
            val aliceIdHex = ALICE_ACCOUNT_ID.joinToString("") { "%02x".format(it) }
            val blob = buildContractEmittedBlob(ALICE_ACCOUNT_ID, emptyList(), byteArrayOf())
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }
            val results = SubstrateSystemEventsParser.parseContractEmitted(hex, 1L, ALICE_SS58)
            results shouldHaveSize 1
            // Vor dem Fix war extrinsicHash = "0x" + aliceIdHex (Contract-Adresse!).
            // Nach dem Fix: extrinsicHash ist leer (echter Hash nicht aus Pattern-Scan rekonstruierbar).
            results[0].extrinsicHash shouldNotContain aliceIdHex
            results[0].extrinsicHash shouldBe ""
        }

        test("RawContractEvent.extrinsicHash is empty string after fix") {
            val blob = buildContractEmittedBlob(ALICE_ACCOUNT_ID, emptyList(), byteArrayOf())
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }
            val results = SubstrateSystemEventsParser.parseContractEmitted(hex, 1L, ALICE_SS58)
            results shouldHaveSize 1
            results[0].extrinsicHash shouldBe ""
        }

        // -------------------------------------------------------------------------
        // DoS-Schutz: MAX_TOPICS
        // -------------------------------------------------------------------------

        test("parseContractEmitted: topics count > MAX_TOPICS → event skipped (no crash)") {
            // Konstruiere Blob mit 17 Topics (MAX_TOPICS=16 → wird abgelehnt)
            val topicsLen = ScaleCodec.encodeCompact(17L)
            val fakeTopics = ByteArray(17 * 32) { 0x00.toByte() }
            val dataCompact = ScaleCodec.encodeCompact(0L)
            val header = byteArrayOf(0x00)
            val palletEvent = byteArrayOf(SubstrateSystemEventsParser.DEFAULT_CONTRACTS_PALLET_INDEX.toByte(), 0x00)
            val blob = header + palletEvent + ALICE_ACCOUNT_ID + topicsLen + fakeTopics + dataCompact
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }
            // Soll kein Crash verursachen; Event wird uebersprungen weil topicsLen > MAX_TOPICS
            val results = SubstrateSystemEventsParser.parseContractEmitted(hex, 1L, ALICE_SS58)
            results.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // blockNumber wird korrekt propagiert
        // -------------------------------------------------------------------------

        test("parseContractEmitted: blockNumber is correctly set on extracted event") {
            val blob = buildContractEmittedBlob(ALICE_ACCOUNT_ID, emptyList(), byteArrayOf(0xFF.toByte()))
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }
            val results = SubstrateSystemEventsParser.parseContractEmitted(hex, 999L, ALICE_SS58)
            results shouldHaveSize 1
            results[0].blockNumber shouldBe 999L
        }

        // -------------------------------------------------------------------------
        // topicsHex Format
        // -------------------------------------------------------------------------

        test("parseContractEmitted: topicsHex entries are '0x' + 64 hex chars (66 chars total)") {
            val topic = ByteArray(32) { 0xFF.toByte() }
            val blob = buildContractEmittedBlob(ALICE_ACCOUNT_ID, listOf(topic), byteArrayOf())
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }
            val results = SubstrateSystemEventsParser.parseContractEmitted(hex, 1L, ALICE_SS58)
            results shouldHaveSize 1
            results[0].topicsHex shouldHaveSize 1
            results[0].topicsHex[0].length shouldBe 66
            results[0].topicsHex[0] shouldStartWith "0x"
        }

        // -------------------------------------------------------------------------
        // dataScale korrekt extrahiert
        // -------------------------------------------------------------------------

        test("parseContractEmitted: dataScale matches original data bytes") {
            val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
            val blob = buildContractEmittedBlob(ALICE_ACCOUNT_ID, emptyList(), data)
            val hex = "0x" + blob.joinToString("") { "%02x".format(it) }
            val results = SubstrateSystemEventsParser.parseContractEmitted(hex, 5L, ALICE_SS58)
            results shouldHaveSize 1
            results[0].dataScale.toList() shouldBe data.toList()
        }
    })
