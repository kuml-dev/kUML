package dev.kuml.core.config

import java.io.File
import kotlin.script.experimental.api.ResultValue

/**
 * Extrahiert ein [KumlConfig] aus dem Auswertungs-Resultat eines
 * `*.kuml.config.kts`-Scripts.
 *
 * Strategie: nimmt den Rückgabewert der letzten Top-Level-Expression. Das
 * Script muss mit `kumlConfig { … }` enden.
 */
public object ConfigExtractor {
    public fun extract(
        returnValue: ResultValue,
        file: File,
    ): KumlConfig {
        if (returnValue !is ResultValue.Value) {
            error(
                "Config script '${file.name}' did not return a value. " +
                    "The script must end with a `kumlConfig { … }` expression.",
            )
        }
        val value =
            returnValue.value
                ?: error(
                    "Config script '${file.name}' returned null. " +
                        "The script must end with a `kumlConfig { … }` expression.",
                )
        return value as? KumlConfig
            ?: error(
                "Config script '${file.name}' returned ${value::class.simpleName}, " +
                    "expected dev.kuml.core.config.KumlConfig. " +
                    "Did you forget to wrap the final block in `kumlConfig { … }`?",
            )
    }
}
