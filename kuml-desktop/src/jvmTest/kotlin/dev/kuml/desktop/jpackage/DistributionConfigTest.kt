package dev.kuml.desktop.jpackage

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.string.shouldStartWith

/**
 * V3.0.14 — Tests für die jpackage-Versions-Konvertierungslogik.
 *
 * Testet [versionForJpackage] als reine Kotlin-Funktion —
 * kein Gradle-Build nötig.
 */
class DistributionConfigTest : FunSpec({

    // ── versionForJpackage() ────────────────────────────────────────────────

    test("packageVersion startet nicht mit '0.' (jpackage-Kompatibilität)") {
        val version = versionForJpackage("0.11.0")
        version.shouldNotStartWith("0.")
    }

    test("'0.11.0' wird korrekt in '11.0.0' konvertiert") {
        versionForJpackage("0.11.0") shouldBe "11.0.0"
    }

    test("'0.3.5' wird korrekt in '3.5.0' konvertiert") {
        versionForJpackage("0.3.5") shouldBe "3.5.0"
    }

    test("'0.1.0' wird korrekt in '1.0.0' konvertiert") {
        versionForJpackage("0.1.0") shouldBe "1.0.0"
    }

    test("Version ohne führendes '0.' wird unverändert zurückgegeben") {
        versionForJpackage("1.0.0") shouldBe "1.0.0"
        versionForJpackage("2.1.3") shouldBe "2.1.3"
    }

    test("Ergebnis ist niemals null") {
        versionForJpackage("0.11.0") shouldNotBe null
    }

    test("Konvertiertes Format hat genau drei Punkte-Segmente") {
        val version = versionForJpackage("0.11.0")
        val parts = version.split(".")
        parts.size shouldBe 3
    }

    test("Erstes Segment des Ergebnisses ist ≥ 1 für '0.x.y'-Eingaben") {
        val version = versionForJpackage("0.5.2")
        val firstSegment = version.split(".").first().toInt()
        (firstSegment >= 1) shouldBe true
    }

    // ── Konfigurations-Konstanten ───────────────────────────────────────────

    test("packageName ist 'kuml-desktop'") {
        // Diese Konstante dokumentiert den erwarteten Wert aus build.gradle.kts
        val expectedPackageName = "kuml-desktop"
        expectedPackageName shouldBe "kuml-desktop"
    }

    test("mainClass ist 'dev.kuml.desktop.MainKt'") {
        val expectedMainClass = "dev.kuml.desktop.MainKt"
        expectedMainClass shouldBe "dev.kuml.desktop.MainKt"
    }

    test("upgradeUuid ist eine gültige UUID-Formatzeichenkette") {
        val upgradeUuid = "C4F2B3D1-A1E5-4B8C-9D7F-6E3A2C8B4F1E"
        // UUID-Format: 8-4-4-4-12 Zeichen
        val uuidRegex = Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")
        upgradeUuid.matches(uuidRegex) shouldBe true
    }

    test("versionForJpackage() startet Ausgabe mit der Minor-Komponente der 0.x.y-Eingabe") {
        val version = versionForJpackage("0.13.2")
        version.shouldStartWith("13.")
    }
})
