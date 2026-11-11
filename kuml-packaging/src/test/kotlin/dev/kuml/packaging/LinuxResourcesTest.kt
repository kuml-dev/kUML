package dev.kuml.packaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File

/**
 * Static checks of the jpackage resource-dir overrides that put `kuml` on PATH
 * for the CLI .deb. jpackage itself only runs on Linux in CI; the CI smoke step
 * in release-installers.yml proves the installed result.
 */
class LinuxResourcesTest :
    StringSpec({

        val dir = File("src/main/linux-resources/deb")

        "postinst symlinks the CLI launcher into /usr/bin on configure" {
            val file = File(dir, "postinst")
            file.exists().shouldBeTrue()
            file.canExecute().shouldBeTrue()
            val text = file.readText()
            text shouldStartWith "#!/bin/sh"
            text shouldContain "configure)"
            text shouldContain "ln -sf /opt/kuml/bin/kuml /usr/bin/kuml"
        }

        "prerm removes the symlink on remove only, not on upgrade" {
            val file = File(dir, "prerm")
            file.exists().shouldBeTrue()
            file.canExecute().shouldBeTrue()
            val text = file.readText()
            text shouldStartWith "#!/bin/sh"
            text shouldContain "remove|deconfigure)"
            text shouldContain "rm -f /usr/bin/kuml"
            (text.contains("upgrade")).not().shouldBeTrue()
        }

        "packageDeb passes the resource dir to jpackage" {
            val content = File("build.gradle.kts").readText()
            content shouldContain "--resource-dir"
            content shouldContain "src/main/linux-resources/deb"
        }
    })
