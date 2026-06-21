package dev.kuml.examples.dap

import dev.kuml.runtime.chain.ModelHasher
import dev.kuml.runtime.chain.evm.EvmChainAdapter
import dev.kuml.runtime.chain.evm.EvmJsonRpcClient
import dev.kuml.runtime.chain.evm.ModelSigner
import dev.kuml.runtime.chain.evm.RpcUrlValidator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

// ── ABI encoding helpers (copied from EvmChainAdapterTest — file-private there) ──────────

/** Encodes a fixed 32-byte slot (bytes32 / uint256) — returns hex without 0x. */
private fun abiBytes32(hex: String): String = hex.padStart(64, '0')

/** ABI-encodes a uint256 value. */
private fun abiUint(value: Int): String = value.toString(16).padStart(64, '0')

/** ABI-encodes a dynamic string (offset=0x20, length, data padded to 32-byte boundary). */
private fun abiString(s: String): String {
    val bytes = s.toByteArray(Charsets.UTF_8)
    val offset = "0000000000000000000000000000000000000000000000000000000000000020"
    val length = bytes.size.toString(16).padStart(64, '0')
    val hexData = bytes.joinToString("") { "%02x".format(it) }
    val padded = hexData.padEnd(((hexData.length + 63) / 64) * 64, '0')
    return offset + length + (if (padded.isEmpty()) "0".repeat(64) else padded)
}

/** Returns an ABI-encoded eth_call result prefixed with "0x". */
private fun abiCallResult(hex: String): String = rpcSuccess(result = "\"0x$hex\"")

// ── Constants ─────────────────────────────────────────────────────────────────────────────

/**
 * HARDHAT ACCOUNT 0 — OEFFENTLICH BEKANNTER TEST-KEY.
 * NIEMALS fuer echte Deployments, Mainnets oder Staging-Umgebungen verwenden!
 * Dieser Key ist Teil des oeffentlichen Hardhat-/Anvil-Mnemonics und gilt als kompromittiert.
 * Quelle: https://hardhat.org/hardhat-network/docs/reference#accounts
 */
private const val PRIV = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
private const val EXPECTED_ADDR = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
private const val TEST_CONTRACT = "0x1234567890abcdef1234567890abcdef12345678"

// ── Resource loader helper ─────────────────────────────────────────────────────────────────

private val loader = object {}::class.java

private fun loadResource(name: String): String =
    loader
        .getResourceAsStream("/dap/$name")
        ?.bufferedReader()
        ?.readText()
        ?: error("Test resource not found: /dap/$name")

/**
 * V3.0.6 — DAP Verfassungs-Showcase Tests.
 *
 * Tests für die drei DAP-Modell-Dateien (Struktur-Klassendiagramm, Artikel-STM,
 * Amendment-STM) mit Modell-Hash, EIP-712-Signatur und Offline-MockChain-Integration.
 *
 * Achtung: Der verwendete Signing-Key ([PRIV]) ist der oeffentlich bekannte
 * Hardhat-Account-0-Key. Er darf ausschliesslich in Offline-Tests gegen MockRpcServer
 * oder lokale Hardhat/Anvil-Nodes eingesetzt werden.
 */
class DapConstitutionShowcaseTest :
    StringSpec({

        // ── A) Modell-Hash-Tests ──────────────────────────────────────────────

        "dap-constitution-structure.kuml.kts kann geladen und kanonisiert werden" {
            val src = loadResource("dap-constitution-structure.kuml.kts")
            val canonical = ModelHasher.canonicalize(src)
            canonical.isNotEmpty() shouldBe true
            val hash = ModelHasher.hashCanonical(canonical)
            hash.size shouldBe 32
        }

        "dap-constitution-article-stm.kuml.kts erzeugt deterministischen Hash" {
            val src = loadResource("dap-constitution-article-stm.kuml.kts")
            val hash1 = ModelHasher.hashCanonical(ModelHasher.canonicalize(src))
            val hash2 = ModelHasher.hashCanonical(ModelHasher.canonicalize(src))
            hash1.contentEquals(hash2) shouldBe true
        }

        "alle drei Modell-Dateien haben unterschiedliche Hashes" {
            val structSrc = loadResource("dap-constitution-structure.kuml.kts")
            val articleSrc = loadResource("dap-constitution-article-stm.kuml.kts")
            val amendSrc = loadResource("dap-constitution-amendment-stm.kuml.kts")
            val h1 = ModelHasher.hashCanonical(ModelHasher.canonicalize(structSrc))
            val h2 = ModelHasher.hashCanonical(ModelHasher.canonicalize(articleSrc))
            val h3 = ModelHasher.hashCanonical(ModelHasher.canonicalize(amendSrc))
            h1.contentEquals(h2) shouldBe false
            h1.contentEquals(h3) shouldBe false
            h2.contentEquals(h3) shouldBe false
        }

        // ── B) Signatur-Tests ─────────────────────────────────────────────────

        "Artikel-STM kann mit bekanntem Key signiert werden" {
            val src = loadResource("dap-constitution-article-stm.kuml.kts")
            val sig = ModelSigner().sign(src, PRIV)
            sig.signer.equals(EXPECTED_ADDR, ignoreCase = true) shouldBe true
            sig.signature.size shouldBe 65
        }

        "Signatur ist verifizierbar (ModelSigner.recover)" {
            val src = loadResource("dap-constitution-article-stm.kuml.kts")
            val sig = ModelSigner().sign(src, PRIV)
            val recovered = ModelSigner().recover(src, sig)
            recovered.equals(EXPECTED_ADDR, ignoreCase = true) shouldBe true
        }

        "manipuliertes Modell ergibt andere Signatur-Verifikation" {
            val srcA = loadResource("dap-constitution-article-stm.kuml.kts")
            val srcB = loadResource("dap-constitution-amendment-stm.kuml.kts")
            val sigA = ModelSigner().sign(srcA, PRIV)
            shouldThrow<IllegalArgumentException> {
                ModelSigner().recover(srcB, sigA)
            }
        }

        // ── C) MockRpcServer-Integration ──────────────────────────────────────

        "MockChain gibt korrekte ContractIdentity zurück" {
            val structSrc = loadResource("dap-constitution-structure.kuml.kts")
            val expectedHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(structSrc))
            val hashHex = expectedHash.joinToString("") { "%02x".format(it) }

            val server = MockRpcServer()
            server.onMethod("eth_call") { body ->
                when {
                    body.contains(EvmChainAdapter.SELECTOR_MODEL_HASH.removePrefix("0x")) ->
                        abiCallResult(abiBytes32(hashHex))
                    body.contains(EvmChainAdapter.SELECTOR_MODEL_URI.removePrefix("0x")) ->
                        abiCallResult(abiString("ipfs://QmDapVerfassung"))
                    body.contains(EvmChainAdapter.SELECTOR_SCHEMA_VERSION.removePrefix("0x")) ->
                        abiCallResult(abiUint(1))
                    else -> rpcError(code = -32000, message = "unknown selector")
                }
            }
            server.start()
            try {
                val adapter =
                    EvmChainAdapter(
                        clientFactory = { url -> EvmJsonRpcClient(url) },
                        urlValidator = RpcUrlValidator.NoOp,
                    )
                val identity = runBlocking { adapter.connect(server.baseUrl(), TEST_CONTRACT) }
                identity.modelHash.contentEquals(expectedHash) shouldBe true
                identity.modelUri shouldBe "ipfs://QmDapVerfassung"
                identity.schemaVersion shouldBe 1
            } finally {
                server.stop()
            }
        }

        "kuml chain verify bestätigt Hash-Übereinstimmung (Offline)" {
            val structSrc = loadResource("dap-constitution-structure.kuml.kts")
            val expectedHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(structSrc))
            val hashHex = expectedHash.joinToString("") { "%02x".format(it) }

            val server = MockRpcServer()
            server.onMethod("eth_call") { body ->
                when {
                    body.contains(EvmChainAdapter.SELECTOR_MODEL_HASH.removePrefix("0x")) ->
                        abiCallResult(abiBytes32(hashHex))
                    body.contains(EvmChainAdapter.SELECTOR_MODEL_URI.removePrefix("0x")) ->
                        abiCallResult(abiString("ipfs://QmDapVerfassung"))
                    body.contains(EvmChainAdapter.SELECTOR_SCHEMA_VERSION.removePrefix("0x")) ->
                        abiCallResult(abiUint(1))
                    else -> rpcError(code = -32000, message = "unknown selector")
                }
            }
            server.start()
            try {
                val adapter =
                    EvmChainAdapter(
                        clientFactory = { url -> EvmJsonRpcClient(url) },
                        urlValidator = RpcUrlValidator.NoOp,
                    )
                val identity = runBlocking { adapter.connect(server.baseUrl(), TEST_CONTRACT) }
                val localHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(structSrc))
                localHash.contentEquals(identity.modelHash) shouldBe true
            } finally {
                server.stop()
            }
        }

        "manipuliertes Modell schlägt Chain-Verifikation fehl" {
            val structSrc = loadResource("dap-constitution-structure.kuml.kts")
            val expectedHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(structSrc))
            val hashHex = expectedHash.joinToString("") { "%02x".format(it) }

            val server = MockRpcServer()
            server.onMethod("eth_call") { body ->
                when {
                    body.contains(EvmChainAdapter.SELECTOR_MODEL_HASH.removePrefix("0x")) ->
                        abiCallResult(abiBytes32(hashHex))
                    body.contains(EvmChainAdapter.SELECTOR_MODEL_URI.removePrefix("0x")) ->
                        abiCallResult(abiString("ipfs://QmDapVerfassung"))
                    body.contains(EvmChainAdapter.SELECTOR_SCHEMA_VERSION.removePrefix("0x")) ->
                        abiCallResult(abiUint(1))
                    else -> rpcError(code = -32000, message = "unknown selector")
                }
            }
            server.start()
            try {
                val adapter =
                    EvmChainAdapter(
                        clientFactory = { url -> EvmJsonRpcClient(url) },
                        urlValidator = RpcUrlValidator.NoOp,
                    )
                val identity = runBlocking { adapter.connect(server.baseUrl(), TEST_CONTRACT) }
                val tamperedHash =
                    ModelHasher.hashCanonical(
                        ModelHasher.canonicalize(structSrc + "\n// tampered"),
                    )
                tamperedHash.contentEquals(identity.modelHash) shouldBe false
            } finally {
                server.stop()
            }
        }

        // ── D) Event-Trace-JSON-Tests ─────────────────────────────────────────

        "dap-chain-events-happy.json ist gültiges JSON-Objekt mit 6 Events" {
            val text = loadResource("dap-chain-events-happy.json")
            val events = Json.parseToJsonElement(text).jsonObject["events"]!!.jsonArray
            events.size shouldBe 6
        }

        "dap-chain-events-quorum-fail.json ist gültiges JSON-Objekt mit 5 Events" {
            val text = loadResource("dap-chain-events-quorum-fail.json")
            val events = Json.parseToJsonElement(text).jsonObject["events"]!!.jsonArray
            events.size shouldBe 5
        }

        // ── E) Idempotenz und weitere Hash-Tests ──────────────────────────────

        "Strukturmodell-Hash ist stabil über mehrere Aufrufe (Idempotenz canonicalize)" {
            val src = loadResource("dap-constitution-structure.kuml.kts")
            val canon1 = ModelHasher.canonicalize(src)
            val canon2 = ModelHasher.canonicalize(canon1)
            canon1 shouldBe canon2
        }

        "Amendment-STM hat anderen Hash als Artikel-STM" {
            val articleSrc = loadResource("dap-constitution-article-stm.kuml.kts")
            val amendSrc = loadResource("dap-constitution-amendment-stm.kuml.kts")
            val h1 = ModelHasher.hashCanonical(ModelHasher.canonicalize(articleSrc))
            val h2 = ModelHasher.hashCanonical(ModelHasher.canonicalize(amendSrc))
            h1.contentEquals(h2) shouldBe false
        }

        "connect ist deterministisch — zwei connects liefern gleichen modelHash" {
            val structSrc = loadResource("dap-constitution-structure.kuml.kts")
            val expectedHash = ModelHasher.hashCanonical(ModelHasher.canonicalize(structSrc))
            val hashHex = expectedHash.joinToString("") { "%02x".format(it) }

            val server = MockRpcServer()
            server.onMethod("eth_call") { body ->
                when {
                    body.contains(EvmChainAdapter.SELECTOR_MODEL_HASH.removePrefix("0x")) ->
                        abiCallResult(abiBytes32(hashHex))
                    body.contains(EvmChainAdapter.SELECTOR_MODEL_URI.removePrefix("0x")) ->
                        abiCallResult(abiString("ipfs://QmDapVerfassung"))
                    body.contains(EvmChainAdapter.SELECTOR_SCHEMA_VERSION.removePrefix("0x")) ->
                        abiCallResult(abiUint(1))
                    else -> rpcError(code = -32000, message = "unknown selector")
                }
            }
            server.start()
            try {
                val identity1 =
                    runBlocking {
                        EvmChainAdapter(
                            clientFactory = { url -> EvmJsonRpcClient(url) },
                            urlValidator = RpcUrlValidator.NoOp,
                        ).connect(server.baseUrl(), TEST_CONTRACT)
                    }
                val identity2 =
                    runBlocking {
                        EvmChainAdapter(
                            clientFactory = { url -> EvmJsonRpcClient(url) },
                            urlValidator = RpcUrlValidator.NoOp,
                        ).connect(server.baseUrl(), TEST_CONTRACT)
                    }
                identity1.modelHash.contentEquals(identity2.modelHash) shouldBe true
            } finally {
                server.stop()
            }
        }
    })
