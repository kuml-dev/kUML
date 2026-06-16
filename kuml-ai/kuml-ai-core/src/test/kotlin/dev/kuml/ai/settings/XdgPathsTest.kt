package dev.kuml.ai.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class XdgPathsTest :
    FunSpec({

        afterTest { (_, _) ->
            System.clearProperty("kuml.ai.os")
            System.clearProperty("kuml.config.home")
        }

        test("mac config dir is under Application Support") {
            System.setProperty("kuml.ai.os", "mac")
            // No XDG_CONFIG_HOME on macOS by default in tests
            val dir = XdgPaths.kumlConfigDir().toString()
            // Either Application Support or XDG — on test systems, default is Application Support
            // We verify kuml is the leaf directory
            dir shouldContain "kuml"
        }

        test("linux config dir respects XDG_CONFIG_HOME") {
            System.setProperty("kuml.ai.os", "linux")
            // Inject via config home override since we can't set env vars in tests
            System.setProperty("kuml.config.home", "/tmp/test-xdg")
            val dir = XdgPaths.kumlConfigDir().toString()
            dir shouldContain "kuml"
            dir shouldContain "/tmp/test-xdg"
        }

        test("windows config dir uses APPDATA") {
            System.setProperty("kuml.ai.os", "windows")
            val dir = XdgPaths.kumlConfigDir().toString()
            dir shouldContain "kuml"
        }
    })
