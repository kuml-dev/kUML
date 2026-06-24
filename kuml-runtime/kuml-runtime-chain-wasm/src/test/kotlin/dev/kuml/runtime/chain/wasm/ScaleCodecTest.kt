package dev.kuml.runtime.chain.wasm

import dev.kuml.runtime.chain.wasm.scale.ScaleCodec
import dev.kuml.runtime.chain.wasm.scale.ScaleException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ScaleCodecTest :
    FunSpec({

        // -------------------------------------------------------------------------
        // Compact Roundtrip — alle vier Modi
        // -------------------------------------------------------------------------

        test("encodeCompact/readCompact single-byte mode: 0") {
            val enc = ScaleCodec.encodeCompact(0)
            enc shouldBe byteArrayOf(0x00)
            ScaleCodec.reader(enc).readCompact() shouldBe 0L
        }

        test("encodeCompact/readCompact single-byte mode: 1") {
            val enc = ScaleCodec.encodeCompact(1)
            ScaleCodec.reader(enc).readCompact() shouldBe 1L
        }

        test("encodeCompact/readCompact single-byte mode max: 63") {
            val enc = ScaleCodec.encodeCompact(63)
            enc.size shouldBe 1
            ScaleCodec.reader(enc).readCompact() shouldBe 63L
        }

        test("encodeCompact/readCompact two-byte mode: 64") {
            val enc = ScaleCodec.encodeCompact(64)
            enc.size shouldBe 2
            ScaleCodec.reader(enc).readCompact() shouldBe 64L
        }

        test("encodeCompact/readCompact two-byte mode max: 16383") {
            val enc = ScaleCodec.encodeCompact(16383)
            enc.size shouldBe 2
            ScaleCodec.reader(enc).readCompact() shouldBe 16383L
        }

        test("encodeCompact/readCompact four-byte mode: 16384") {
            val enc = ScaleCodec.encodeCompact(16384)
            enc.size shouldBe 4
            ScaleCodec.reader(enc).readCompact() shouldBe 16384L
        }

        test("encodeCompact/readCompact four-byte mode max: 1073741823") {
            val enc = ScaleCodec.encodeCompact(1_073_741_823L)
            enc.size shouldBe 4
            ScaleCodec.reader(enc).readCompact() shouldBe 1_073_741_823L
        }

        test("encodeCompact/readCompact big-integer mode: 1073741824") {
            val enc = ScaleCodec.encodeCompact(1_073_741_824L)
            ScaleCodec.reader(enc).readCompact() shouldBe 1_073_741_824L
        }

        test("encodeCompact/readCompact big-integer mode: u32-max") {
            val v = 0xFFFF_FFFFL
            val enc = ScaleCodec.encodeCompact(v)
            ScaleCodec.reader(enc).readCompact() shouldBe v
        }

        test("encodeCompact/readCompact big-integer mode: 5-byte value (2^32 + 1)") {
            val v = 0x1_0000_0001L
            val enc = ScaleCodec.encodeCompact(v)
            ScaleCodec.reader(enc).readCompact() shouldBe v
        }

        test("encodeCompact/readCompact big-integer mode: Long.MAX_VALUE (8-byte payload)") {
            val v = Long.MAX_VALUE
            val enc = ScaleCodec.encodeCompact(v)
            // header = ((8-4) shl 2) or 0b11 = (4 shl 2) or 3 = 19; payload = 8 bytes
            enc.size shouldBe 9
            enc[0] shouldBe 19.toByte()
            ScaleCodec.reader(enc).readCompact() shouldBe v
        }

        test("readCompact two-byte mode Long type guarantee: max two-byte value 16383") {
            // Verify that the two-byte path returns Long, not Int-truncated value.
            val v = 16383L
            val enc = ScaleCodec.encodeCompact(v)
            val decoded: Long = ScaleCodec.reader(enc).readCompact()
            decoded shouldBe v
        }

        // -------------------------------------------------------------------------
        // Fixed-width encoding
        // -------------------------------------------------------------------------

        test("encodeU32 little-endian: 42 → 0x2a000000") {
            ScaleCodec.encodeU32(42L) shouldBe byteArrayOf(0x2a, 0x00, 0x00, 0x00)
        }

        test("encodeU16 little-endian: 256 → 0x0001") {
            ScaleCodec.encodeU16(256) shouldBe byteArrayOf(0x00, 0x01)
        }

        test("encodeU8: 255 → 0xFF") {
            ScaleCodec.encodeU8(255) shouldBe byteArrayOf(0xFF.toByte())
        }

        test("encodeU64 / readU64 roundtrip: Long.MAX_VALUE") {
            val v = Long.MAX_VALUE
            val enc = ScaleCodec.encodeU64(v)
            enc.size shouldBe 8
            ScaleCodec.reader(enc).readU64() shouldBe v
        }

        test("readU32 roundtrip: 0xDEADBEEF") {
            val v = 0xDEADBEEFL
            val enc = ScaleCodec.encodeU32(v)
            ScaleCodec.reader(enc).readU32() shouldBe v
        }

        // -------------------------------------------------------------------------
        // u128 hex
        // -------------------------------------------------------------------------

        test("readU128Hex: 16 null bytes → 0x + 32 zeros (canonical fixed-width)") {
            val data = ByteArray(16)
            ScaleCodec.reader(data).readU128Hex() shouldBe "0x" + "0".repeat(32)
        }

        test("readU128Hex: 0x01 followed by 15 zeros → fixed 32-char with leading zeros") {
            val data = ByteArray(16)
            data[0] = 0x01
            ScaleCodec.reader(data).readU128Hex() shouldBe "0x" + "0".repeat(31) + "1"
        }

        test("readU128Hex: all 0xFF → 32 f-chars") {
            val data = ByteArray(16) { 0xFF.toByte() }
            val hex = ScaleCodec.reader(data).readU128Hex()
            hex shouldBe "0x" + "f".repeat(32)
        }

        test("readU128Hex: always returns exactly 34 chars (0x + 32 hex digits)") {
            val data = ByteArray(16)
            data[0] = 0x42
            val hex = ScaleCodec.reader(data).readU128Hex()
            hex.length shouldBe 34
            hex.startsWith("0x") shouldBe true
        }

        // -------------------------------------------------------------------------
        // Vec / String / Option / Result
        // -------------------------------------------------------------------------

        test("readByteVec: 'hello' → SCALE Vec<u8>") {
            // SCALE: compact(5) = 0x14, then 'hello'
            val data = byteArrayOf(0x14, 0x68, 0x65, 0x6c, 0x6c, 0x6f)
            ScaleCodec.reader(data).readByteVec() shouldBe "hello".toByteArray(Charsets.UTF_8)
        }

        test("readString: 'hello'") {
            val data = byteArrayOf(0x14, 0x68, 0x65, 0x6c, 0x6c, 0x6f)
            ScaleCodec.reader(data).readString() shouldBe "hello"
        }

        test("readVec: list of u8 values") {
            val enc = ScaleCodec.encodeCompact(3L) + byteArrayOf(10, 20, 30)
            val result = ScaleCodec.reader(enc).readVec { it.readU8() }
            result shouldBe listOf(10, 20, 30)
        }

        test("readOption None (0x00) → null") {
            ScaleCodec.reader(byteArrayOf(0x00)).readOption { it.readU8() } shouldBe null
        }

        test("readOption Some (0x01) → value") {
            val data = byteArrayOf(0x01, 42)
            ScaleCodec.reader(data).readOption { it.readU8() } shouldBe 42
        }

        test("readResult Ok (0x00) → ScaleResult.Ok") {
            val data = byteArrayOf(0x00, 99)
            val r = ScaleCodec.reader(data).readResult({ it.readU8() }, { it.readU8() })
            (r as dev.kuml.runtime.chain.wasm.scale.ScaleResult.Ok).value shouldBe 99
        }

        test("readResult Err (0x01) → ScaleResult.Err") {
            val data = byteArrayOf(0x01, 55)
            val r = ScaleCodec.reader(data).readResult({ it.readU8() }, { it.readU8() })
            (r as dev.kuml.runtime.chain.wasm.scale.ScaleResult.Err).error shouldBe 55
        }

        // -------------------------------------------------------------------------
        // Error cases
        // -------------------------------------------------------------------------

        test("readCollectionLen: DoS guard — length > maxCollectionLen throws ScaleException") {
            // compact(5) but maxCollectionLen=4
            val data = ScaleCodec.encodeCompact(5L)
            val ex =
                shouldThrow<ScaleException> {
                    ScaleCodec.reader(data, maxCollectionLen = 4).readCollectionLen()
                }
            ex.message shouldContain "exceeds max"
        }

        test("readU64 on 3-byte array → ScaleException with 'underflow'") {
            val ex =
                shouldThrow<ScaleException> {
                    ScaleCodec.reader(byteArrayOf(1, 2, 3)).readU64()
                }
            ex.message shouldContain "underflow"
        }

        test("readBool discriminant 2 → ScaleException") {
            val ex =
                shouldThrow<ScaleException> {
                    ScaleCodec.reader(byteArrayOf(2)).readBool()
                }
            ex.message shouldContain "discriminant"
        }

        test("readOption discriminant 3 → ScaleException") {
            val ex =
                shouldThrow<ScaleException> {
                    ScaleCodec.reader(byteArrayOf(3)).readOption { it.readU8() }
                }
            ex.message shouldContain "discriminant"
        }

        test("readResult discriminant 2 → ScaleException") {
            val ex =
                shouldThrow<ScaleException> {
                    ScaleCodec.reader(byteArrayOf(2)).readResult({ it.readU8() }, { it.readU8() })
                }
            ex.message shouldContain "discriminant"
        }
    })
