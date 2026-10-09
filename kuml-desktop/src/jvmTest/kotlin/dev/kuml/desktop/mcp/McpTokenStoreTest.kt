package dev.kuml.desktop.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

class McpTokenStoreTest :
    FunSpec({
        fun tempStore(): Pair<McpTokenStore, java.nio.file.Path> {
            val dir = Files.createTempDirectory("kuml-mcp-token")
            val file = dir.resolve("mcp-token")
            return McpTokenStore(file = file) to file
        }

        test("token has 43 base64url chars encoding 32 bytes") {
            val (store, _) = tempStore()
            val token = store.loadOrCreate()
            token.length shouldBe 43
            McpTokenStore.TOKEN_PATTERN.matches(token) shouldBe true
            Base64.getUrlDecoder().decode(token).size shouldBe 32
        }

        test("loadOrCreate is stable across calls and instances") {
            val (store, file) = tempStore()
            val first = store.loadOrCreate()
            store.loadOrCreate() shouldBe first
            McpTokenStore(file = file).loadOrCreate() shouldBe first
        }

        test("regenerate yields a different token and persists it") {
            val (store, file) = tempStore()
            val first = store.loadOrCreate()
            val second = store.regenerate()
            second shouldNotBe first
            McpTokenStore(file = file).loadOrCreate() shouldBe second
        }

        test("a corrupt or foreign file is replaced by a fresh token") {
            val (store, file) = tempStore()
            Files.writeString(file, "not a token")
            val token = store.loadOrCreate()
            McpTokenStore.TOKEN_PATTERN.matches(token) shouldBe true
            Files.readString(file).trim() shouldBe token
        }

        test("token file is owner-only (POSIX 0600)") {
            if (!java.nio.file.FileSystems
                    .getDefault()
                    .supportedFileAttributeViews()
                    .contains("posix")
            ) {
                return@test
            }
            val (store, file) = tempStore()
            store.loadOrCreate()
            Files.getPosixFilePermissions(file) shouldBe setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            store.regenerate()
            Files.getPosixFilePermissions(file) shouldBe setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }

        test("loose permissions on an existing token file are tightened on load") {
            if (!java.nio.file.FileSystems
                    .getDefault()
                    .supportedFileAttributeViews()
                    .contains("posix")
            ) {
                return@test
            }
            val (store, file) = tempStore()
            store.loadOrCreate()
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ),
            )
            store.loadOrCreate()
            Files.getPosixFilePermissions(file) shouldBe setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }

        test("toString does not reveal the token") {
            val (store, _) = tempStore()
            val token = store.loadOrCreate()
            store.toString().contains(token) shouldBe false
        }
    })
