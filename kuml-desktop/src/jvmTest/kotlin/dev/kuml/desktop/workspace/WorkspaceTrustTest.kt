package dev.kuml.desktop.workspace

import dev.kuml.desktop.io.AppSettings
import dev.kuml.desktop.io.AppSettingsStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class WorkspaceTrustTest :
    FunSpec({

        test("isTrusted is false for a fresh root") {
            val root = Files.createTempDirectory("kuml-trust-test").toFile()
            try {
                WorkspaceTrust.isTrusted(emptyList(), root) shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("withTrust adds the canonical path") {
            val root = Files.createTempDirectory("kuml-trust-test").toFile()
            try {
                val updated = WorkspaceTrust.withTrust(emptyList(), root)
                updated shouldBe listOf(WorkspaceTrust.canonicalPath(root))
                WorkspaceTrust.isTrusted(updated, root) shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("withTrust does not duplicate an already-trusted path") {
            val root = Files.createTempDirectory("kuml-trust-test").toFile()
            try {
                val once = WorkspaceTrust.withTrust(emptyList(), root)
                val twice = WorkspaceTrust.withTrust(once, root)
                twice shouldBe once
            } finally {
                root.deleteRecursively()
            }
        }

        test("decline path (never calling withTrust) leaves the trusted set unchanged") {
            val root = Files.createTempDirectory("kuml-trust-test").toFile()
            try {
                val trusted = listOf("/some/other/path")
                WorkspaceTrust.isTrusted(trusted, root) shouldBe false
                trusted shouldBe listOf("/some/other/path")
            } finally {
                root.deleteRecursively()
            }
        }

        test("accept persists through AppSettingsStore (round-trip via a temp settings file)") {
            val root = Files.createTempDirectory("kuml-trust-test").toFile()
            val settingsDir = Files.createTempDirectory("kuml-trust-store-test")
            try {
                val store = AppSettingsStore(settingsDir.resolve("settings.json"))
                val trusted = WorkspaceTrust.withTrust(AppSettings.DEFAULT.trustedWorkspaces, root)
                store.save(AppSettings.DEFAULT.copy(trustedWorkspaces = trusted))

                val loaded = store.load()
                WorkspaceTrust.isTrusted(loaded.trustedWorkspaces, root) shouldBe true
            } finally {
                root.deleteRecursively()
                settingsDir.toFile().deleteRecursively()
            }
        }
    })
