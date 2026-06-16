package dev.kuml.ai.vault

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OsDetectionTest :
    FunSpec({

        afterTest { (_, _) ->
            System.clearProperty("kuml.ai.os")
        }

        test("os detection respects -Dkuml.ai.os override") {
            System.setProperty("kuml.ai.os", "mac")
            OsDetection.current() shouldBe OsDetection.Os.MAC

            System.setProperty("kuml.ai.os", "linux")
            OsDetection.current() shouldBe OsDetection.Os.LINUX

            System.setProperty("kuml.ai.os", "windows")
            OsDetection.current() shouldBe OsDetection.Os.WINDOWS

            System.setProperty("kuml.ai.os", "other")
            OsDetection.current() shouldBe OsDetection.Os.OTHER
        }
    })
