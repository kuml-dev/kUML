package dev.kuml.runtime.chain.multi

import dev.kuml.runtime.chain.ChainEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ConflictResolverTest :
    StringSpec({

        // ── EarliestBlock ─────────────────────────────────────────────────────────

        "EarliestBlock: kleinerer blockNumber gewinnt" {
            val a = event("evm", globalSeq = 0, block = 100L, tx = "0xa")
            val b = event("sui", globalSeq = 1, block = 102L, tx = "0xb")
            ConflictResolver.EarliestBlock.resolve(a, b) shouldBe a
            ConflictResolver.EarliestBlock.resolve(b, a) shouldBe a // symmetrie-konsistent
        }

        "EarliestBlock: gleicher blockNumber → lexikografisch kleinere chainId gewinnt" {
            val a = event("evm", globalSeq = 0, block = 100L, tx = "0xa")
            val b = event("aptos", globalSeq = 1, block = 100L, tx = "0xb")
            // "aptos" < "evm" → b gewinnt
            ConflictResolver.EarliestBlock.resolve(a, b) shouldBe b
            ConflictResolver.EarliestBlock.resolve(b, a) shouldBe b // symmetrie-konsistent
        }

        // ── PriorityChain ─────────────────────────────────────────────────────────

        "PriorityChain: priorisierte Chain gewinnt trotz höherem Block" {
            val resolver = ConflictResolver.PriorityChain(listOf("sui", "evm"))
            val sui = event("sui", globalSeq = 1, block = 105L, tx = "0xsui")
            val evm = event("evm", globalSeq = 0, block = 100L, tx = "0xevm")
            // sui hat Rang 0, evm hat Rang 1 → sui gewinnt trotz höherem Block
            resolver.resolve(sui, evm) shouldBe sui
            resolver.resolve(evm, sui) shouldBe sui // symmetrie-konsistent
        }

        "PriorityChain: beide Chains nicht in priority-Liste → EarliestBlock-Fallback" {
            val resolver = ConflictResolver.PriorityChain(listOf("solana"))
            val a = event("evm", globalSeq = 0, block = 100L, tx = "0xa")
            val b = event("aptos", globalSeq = 1, block = 102L, tx = "0xb")
            // beide Rang MAX_VALUE → EarliestBlock: kleinerer Block gewinnt → a
            resolver.resolve(a, b) shouldBe a
            resolver.resolve(b, a) shouldBe a // symmetrie-konsistent
        }

        // ── FirstObserved ─────────────────────────────────────────────────────────

        "FirstObserved: kleinerer globalSequence gewinnt" {
            val a = event("evm", globalSeq = 0, block = 105L, tx = "0xa")
            val b = event("sui", globalSeq = 3, block = 100L, tx = "0xb")
            // a hat seq=0 → gewinnt, trotz höherem Block
            ConflictResolver.FirstObserved.resolve(a, b) shouldBe a
            ConflictResolver.FirstObserved.resolve(b, a) shouldBe a // symmetrie-konsistent
        }
    })

// ── Hilfsfunktion ──────────────────────────────────────────────────────────────

private fun event(
    chainId: String,
    globalSeq: Long,
    block: Long,
    tx: String,
): MergedChainEvent =
    MergedChainEvent(
        chainId = chainId,
        globalSequence = globalSeq,
        originalEvent = ChainEvent("Transfer", byteArrayOf(0x01), block, tx),
    )
