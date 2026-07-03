package dev.kuml.cli.run

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf

class EvmUrlValidatorTest :
    FunSpec({
        // ── validateRpcUrl ────────────────────────────────────────────────────────

        test("validateRpcUrl accepts https") {
            EvmUrlValidator
                .validateRpcUrl("https://mainnet.example.com/v3/key")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Valid>()
        }

        test("validateRpcUrl accepts http") {
            EvmUrlValidator
                .validateRpcUrl("http://node.example.com:8545")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Valid>()
        }

        test("validateRpcUrl rejects file scheme") {
            val result = EvmUrlValidator.validateRpcUrl("file:///etc/passwd")
            result.shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
            result.message shouldContainIgnoringCase "http"
        }

        test("validateRpcUrl rejects private 10.x") {
            EvmUrlValidator
                .validateRpcUrl("http://10.0.0.5:8545")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }

        test("validateRpcUrl rejects private 192.168.x") {
            EvmUrlValidator
                .validateRpcUrl("http://192.168.1.1")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }

        test("validateRpcUrl rejects private 172.16.x") {
            EvmUrlValidator
                .validateRpcUrl("http://172.16.0.1")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }

        test("validateRpcUrl rejects loopback 127.0.0.1") {
            EvmUrlValidator
                .validateRpcUrl("http://127.0.0.1:8545")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }

        test("validateRpcUrl rejects IPv6 loopback ::1") {
            EvmUrlValidator
                .validateRpcUrl("http://[::1]:8545")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }

        // ── validateContractAddress ───────────────────────────────────────────────

        test("validateContractAddress accepts 40 hex with 0x") {
            EvmUrlValidator
                .validateContractAddress("0xdAC17F958D2ee523a2206206994597C13D831ec7")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Valid>()
        }

        test("validateContractAddress accepts 40 hex without 0x") {
            EvmUrlValidator
                .validateContractAddress("dAC17F958D2ee523a2206206994597C13D831ec7")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Valid>()
        }

        test("validateContractAddress rejects 39 hex") {
            EvmUrlValidator
                .validateContractAddress("0xdAC17F958D2ee523a2206206994597C13D831e")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }

        test("validateContractAddress rejects non-hex") {
            EvmUrlValidator
                .validateContractAddress("0xZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ")
                .shouldBeInstanceOf<EvmUrlValidator.Result.Invalid>()
        }

        // ── normalizeContract ─────────────────────────────────────────────────────

        test("normalizeContract prepends 0x when missing") {
            val addr = "dAC17F958D2ee523a2206206994597C13D831ec7"
            EvmUrlValidator.normalizeContract(addr) shouldBe "0x$addr"
        }

        test("normalizeContract keeps existing 0x prefix") {
            val addr = "0xdAC17F958D2ee523a2206206994597C13D831ec7"
            EvmUrlValidator.normalizeContract(addr) shouldBe addr
        }
    })
