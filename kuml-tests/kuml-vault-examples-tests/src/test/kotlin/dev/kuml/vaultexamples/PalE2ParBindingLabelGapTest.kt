@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * PAL wave E2 (18): the labels of parallel bindings of the PAL parametric diagram keep at least 8 px between their
 * rectangles. Measured on the SVG (no font dependency). Regression guard: the wave could not reproduce a violation.
 */
class PalE2ParBindingLabelGapTest :
    FunSpec({
        test("no two edge labels of the parametric diagram are closer than 8 px") {
            val r = VaultExampleRenderer.render(script = SCRIPT)
            r.error shouldBe null
            val svg = r.svg!!
            SampleOutput.write(filename = "pal-e2/par-binding-labels.svg", content = svg)
            val labels = SvgGeometry.labelRects(svg, "kuml-edge-label")
            (labels.size >= 10) shouldBe true
            for (i in labels.indices) {
                for (j in i + 1 until labels.size) {
                    val gap = labels[i].second.gapTo(labels[j].second)
                    (gap >= 8.0) shouldBe true
                }
            }
        }
    }) {
    companion object {
        private val SCRIPT =
            """
            import dev.kuml.sysml2.ConstraintParameter
            import dev.kuml.sysml2.ConstraintParameterDirection
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model(name = "Parametrik Druckkaskade Verzögerungszeit und E-Erhitzer") {
                val druck = attributeDef(name = "Druck", unitSymbol = "Pa")
                val zeit = attributeDef(name = "Zeit", unitSymbol = "s")
                val temperatur = attributeDef(name = "Temperatur", unitSymbol = "°C")
                val spannung = attributeDef(name = "Spannung", unitSymbol = "V")
                val wahrheitswert = attributeDef(name = "Wahrheitswert")

                val kaskade = constraintDef(
                    name = "Druckkaskade Betriebswerte",
                    expression = "pMixNenn == -5 && pPalNenn in 0..5",
                    parameters = listOf(
                        ConstraintParameter(name = "pMixNenn", typeId = druck.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "pPalNenn", typeId = druck.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "pMix", typeId = druck.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "pPal", typeId = druck.id, direction = ConstraintParameterDirection.In),
                    ),
                )
                val vergleich = constraintDef(
                    name = "Vergleich Mixing kleiner PAL",
                    expression = "pMix < pPal",
                    parameters = listOf(
                        ConstraintParameter(name = "pMix", typeId = druck.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "pPal", typeId = druck.id, direction = ConstraintParameterDirection.In),
                    ),
                    parent = kaskade,
                )
                val verzoegerung = constraintDef(
                    name = "Verzögerungszeit",
                    expression = "aktiv = zu && tZu >= tVerz",
                    parameters = listOf(
                        ConstraintParameter(name = "aktiv", typeId = wahrheitswert.id, direction = ConstraintParameterDirection.Out),
                        ConstraintParameter(name = "zu", typeId = wahrheitswert.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "tZu", typeId = zeit.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "tVerz", typeId = zeit.id, direction = ConstraintParameterDirection.In),
                    ),
                )
                val zeitvergleich = constraintDef(
                    name = "Zeitvergleich mindestens 10 s",
                    expression = "tVerz >= 10",
                    parameters = listOf(
                        ConstraintParameter(name = "tVerz", typeId = zeit.id, direction = ConstraintParameterDirection.In),
                    ),
                    parent = verzoegerung,
                )
                val rlkStellsignal = constraintDef(
                    name = "Stellsignal Rückluftklappe",
                    expression = "0 <= uRlk && uRlk <= 10",
                    parameters = listOf(
                        ConstraintParameter(name = "uRlk", typeId = spannung.id, direction = ConstraintParameterDirection.In),
                    ),
                )
                val sollwert = constraintDef(
                    name = "Sollwert Raumtemperatur",
                    expression = "tSoll == 20",
                    parameters = listOf(
                        ConstraintParameter(name = "tSoll", typeId = temperatur.id, direction = ConstraintParameterDirection.In),
                    ),
                )
                val regelung = constraintDef(
                    name = "PI-Regelung E-Erhitzer",
                    expression = "uStell = PI(tSoll - tRaum)",
                    parameters = listOf(
                        ConstraintParameter(name = "uStell", typeId = spannung.id, direction = ConstraintParameterDirection.Out),
                        ConstraintParameter(name = "tSoll", typeId = temperatur.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "tRaum", typeId = temperatur.id, direction = ConstraintParameterDirection.In),
                    ),
                )
                val stellsignal = constraintDef(
                    name = "Stellsignalbegrenzung",
                    expression = "0 <= uStell && uStell <= 10",
                    parameters = listOf(
                        ConstraintParameter(name = "uStell", typeId = spannung.id, direction = ConstraintParameterDirection.In),
                    ),
                    parent = regelung,
                )
                val erhitzerFreigabe = constraintDef(
                    name = "Freigabe E-Erhitzer",
                    expression = "frei = fluss && !stb",
                    parameters = listOf(
                        ConstraintParameter(name = "frei", typeId = wahrheitswert.id, direction = ConstraintParameterDirection.Out),
                        ConstraintParameter(name = "fluss", typeId = wahrheitswert.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "stb", typeId = wahrheitswert.id, direction = ConstraintParameterDirection.In),
                    ),
                )
                val stb = constraintDef(
                    name = "STB-Abschaltung",
                    expression = "!(tKanal > 70) || stb",
                    parameters = listOf(
                        ConstraintParameter(name = "tKanal", typeId = temperatur.id, direction = ConstraintParameterDirection.In),
                        ConstraintParameter(name = "stb", typeId = wahrheitswert.id, direction = ConstraintParameterDirection.Out),
                    ),
                )

                val druckwerte = partDef(name = "Druckwerte") {
                    attribute(name = "raumdruck mixing", typeId = druck.id)
                    attribute(name = "raumdruck pal", typeId = druck.id)
                }
                val zeitwerte = partDef(name = "Zeitwerte") {
                    attribute(name = "verzögerungszeit", typeId = zeit.id)
                    attribute(name = "zeit seit türschließen", typeId = zeit.id)
                    attribute(name = "beide türen geschlossen", typeId = wahrheitswert.id)
                    attribute(name = "rlk regelung aktiv", typeId = wahrheitswert.id)
                }
                val klappenansteuerung = partDef(name = "Klappenansteuerung") {
                    attribute(name = "stellsignal rückluftklappe", typeId = spannung.id)
                }
                val erhitzerregelung = partDef(name = "Erhitzerregelung") {
                    attribute(name = "sollwert raumtemperatur", typeId = temperatur.id)
                    attribute(name = "raumtemperatur", typeId = temperatur.id)
                    attribute(name = "stellsignal erhitzer", typeId = spannung.id)
                }
                val erhitzerschutz = partDef(name = "Erhitzerschutz") {
                    attribute(name = "kanaltemperatur", typeId = temperatur.id)
                    attribute(name = "strömung ausreichend", typeId = wahrheitswert.id)
                    attribute(name = "stb ausgelöst", typeId = wahrheitswert.id)
                    attribute(name = "erhitzer freigegeben", typeId = wahrheitswert.id)
                }

                bind(name = "b01", source = "Druckwerte::raumdruck mixing", target = "Druckkaskade Betriebswerte::pMixNenn")
                bind(name = "b02", source = "Druckwerte::raumdruck pal", target = "Druckkaskade Betriebswerte::pPalNenn")
                bind(name = "b03", source = "Druckkaskade Betriebswerte::pMix", target = "Vergleich Mixing kleiner PAL::pMix")
                bind(name = "b04", source = "Druckkaskade Betriebswerte::pPal", target = "Vergleich Mixing kleiner PAL::pPal")
                bind(name = "b05", source = "Zeitwerte::rlk regelung aktiv", target = "Verzögerungszeit::aktiv")
                bind(name = "b06", source = "Zeitwerte::beide türen geschlossen", target = "Verzögerungszeit::zu")
                bind(name = "b07", source = "Zeitwerte::zeit seit türschließen", target = "Verzögerungszeit::tZu")
                bind(name = "b08", source = "Zeitwerte::verzögerungszeit", target = "Verzögerungszeit::tVerz")
                bind(name = "b09", source = "Verzögerungszeit::tVerz", target = "Zeitvergleich mindestens 10 s::tVerz")
                bind(name = "b10", source = "Erhitzerregelung::sollwert raumtemperatur", target = "Sollwert Raumtemperatur::tSoll")
                bind(name = "b11", source = "Erhitzerregelung::stellsignal erhitzer", target = "PI-Regelung E-Erhitzer::uStell")
                bind(name = "b12", source = "Erhitzerregelung::sollwert raumtemperatur", target = "PI-Regelung E-Erhitzer::tSoll")
                bind(name = "b13", source = "Erhitzerregelung::raumtemperatur", target = "PI-Regelung E-Erhitzer::tRaum")
                bind(name = "b14", source = "PI-Regelung E-Erhitzer::uStell", target = "Stellsignalbegrenzung::uStell")
                bind(name = "b15", source = "Erhitzerschutz::erhitzer freigegeben", target = "Freigabe E-Erhitzer::frei")
                bind(name = "b16", source = "Erhitzerschutz::strömung ausreichend", target = "Freigabe E-Erhitzer::fluss")
                bind(name = "b17", source = "Erhitzerschutz::stb ausgelöst", target = "Freigabe E-Erhitzer::stb")
                bind(name = "b18", source = "Erhitzerschutz::kanaltemperatur", target = "STB-Abschaltung::tKanal")
                bind(name = "b19", source = "Erhitzerschutz::stb ausgelöst", target = "STB-Abschaltung::stb")
                bind(name = "b20", source = "Klappenansteuerung::stellsignal rückluftklappe", target = "Stellsignal Rückluftklappe::uRlk")

                comment(
                    text = "ANNAHME **A4-E** / OFFEN **A4**: 10 s für beide Türen, Timer startet bei Wiederöffnen neu; mindestens 10 s gegen fest 10 s offen (FUBE 3.2.2, 3.6)",
                    verzoegerung,
                    id = "a4e-80",
                    kind = NoteKind.ASSUMPTION,
                )
                comment(
                    text = "ANNAHME **A6-E** / OFFEN **A6**: Sollwert-, Toleranzbereich und Hauptalarm-Schwelle als eine Schwelle gleichgesetzt, nicht beziffert (FUBE 3.4, 3.5.1)",
                    vergleich,
                    id = "a6e-80",
                    kind = NoteKind.ASSUMPTION,
                )
                comment(
                    text = "OFFEN **A9**: Auslöseschwelle des Hauptalarms und Alarmklasse der Störungen nicht spezifiziert, daher kein Constraint (FUBE 3.4, 3.5.3)",
                    kaskade,
                    id = "a9-80",
                    kind = NoteKind.QUESTION,
                )
                comment(
                    text = "OFFEN **A13**: Auswerter des Kanaltemperatursensors und Wirkpfade von Strömungswächter und STB (Hardware oder FMCS) nicht beschrieben (FUBE 3.7)",
                    regelung,
                    erhitzerFreigabe,
                    id = "a13-80",
                    kind = NoteKind.QUESTION,
                )
                comment(
                    text = "OFFEN **A23**: Stellsignal 0-10 V (AUF-ZU): Endwerte AUF und ZU nicht zugeordnet, keine Halteposition bei Verlust von Spannung oder Signal (FUBE 2.2)",
                    rlkStellsignal,
                    id = "a23-80",
                    kind = NoteKind.QUESTION,
                )
                comment(
                    text = "MODELLHILFE **M2**: Wertträger-Blöcke und Hilfstypen Druck, Zeit, Temperatur, Spannung, Wahrheitswert ohne Quellbeleg",
                    zeitwerte,
                    id = "m2-80",
                    kind = NoteKind.HINT,
                )
                comment(
                    text = "MODELLHILFE **M4**: Nur belegte Zahlen als Konstanten (-5 Pa, 0 bis +5 Pa, 10 s, 20 °C, 70 °C, 0-10 V); nicht bezifferte Werte nie erfunden",
                    sollwert,
                    id = "m4-80",
                    kind = NoteKind.HINT,
                )

                parDiagram(name = "Parametrik Druckkaskade Verzögerungszeit und E-Erhitzer") {
                    include(definition = kaskade)
                    include(definition = vergleich)
                    include(definition = verzoegerung)
                    include(definition = zeitvergleich)
                    include(definition = rlkStellsignal)
                    include(definition = sollwert)
                    include(definition = regelung)
                    include(definition = stellsignal)
                    include(definition = erhitzerFreigabe)
                    include(definition = stb)
                    include(definition = druckwerte)
                    include(definition = zeitwerte)
                    include(definition = klappenansteuerung)
                    include(definition = erhitzerregelung)
                    include(definition = erhitzerschutz)
                }
            }
            """.trimIndent()
    }
}
