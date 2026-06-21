package dev.kuml.runtime.chain

import java.security.MessageDigest

/**
 * V3.0.1 — Deterministischer, plattformunabhängiger Hash über kUML-Modell-Quelltext.
 *
 * Pipeline: roher Skript-Text → [canonicalize] (deterministische Normalform)
 * → [hashCanonical] (SHA-256 über die kanonische Form).
 *
 * Konsistent mit der Hash-Konvention aus `dev.kuml.runtime.snapshot` (sortierter,
 * separator-getrennter Digest via [MessageDigest]). GraalVM-Native-Image-kompatibel:
 * nur `java.security.MessageDigest` (SHA-256) und String-Ops, keine Reflection,
 * keine JVM-only-Libraries.
 *
 * ### Normalform-Regeln (in Reihenfolge)
 * 1. CRLF/CR → LF (Zeilenenden vereinheitlichen).
 * 2. Trailing-Whitespace pro Zeile entfernen.
 * 3. Führende Tabs → 4 Spaces (identische Regel wie [dev.kuml.cli.KumlFormatter.format]).
 * 4. Vollständig leere Zeilen entfernen — Whitespace-only-Zeilen dürfen den Hash nicht
 *    beeinflussen.
 * 5. Genau ein abschließendes LF.
 *
 * **Idempotenz**: `canonicalize(canonicalize(s)) == canonicalize(s)` für alle s.
 */
public object ModelHasher {
    /**
     * Bringt einen kUML-Skript-Quelltext in eine deterministische Normalform.
     *
     * Idempotent: `canonicalize(canonicalize(s)) == canonicalize(s)` für alle s.
     * Reine Leerzeilen-/Tab-/Trailing-Space-Varianten kollidieren bewusst auf
     * dieselbe Normalform (Permutations-Stabilität gegen Whitespace).
     *
     * @param script Roher kUML-Skript-Quelltext (beliebige Zeilenenden erlaubt).
     * @return Kanonische Form mit ausschließlich LF-Zeilenenden und einem Trailing-LF.
     */
    public fun canonicalize(script: String): String {
        val normalisedEol = script.replace("\r\n", "\n").replace("\r", "\n")
        val canonicalLines =
            normalisedEol
                .split("\n")
                .asSequence()
                .map { line -> expandLeadingTabs(line.trimEnd()) }
                .filter { it.isNotEmpty() } // leere Zeilen vollständig entfernen → stabiler Hash
                .toList()
        return if (canonicalLines.isEmpty()) "\n" else canonicalLines.joinToString("\n") + "\n"
    }

    /**
     * SHA-256 über die **bereits kanonisierte** Form.
     *
     * Die Eingabe wird als-ist UTF-8-kodiert. Aufrufer sind verantwortlich,
     * vorher [canonicalize] aufzurufen — so bleibt der Hash über die exakte
     * Normalform prüfbar und reproduzierbar.
     *
     * GraalVM-Native-Image-kompatibel (SHA-256 ist in der Reflect-Config enthalten,
     * analog zu `ModelFingerprint` in `kuml-runtime-core`).
     *
     * @param canonical Bereits kanonisierter Quelltext (Ausgabe von [canonicalize]).
     * @return 32-Byte SHA-256-Hash.
     */
    public fun hashCanonical(canonical: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(canonical.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    /**
     * Transitiver Hash: kanonisiert das Wurzel-Skript und alle (rekursiv) über
     * [importResolver] auflösbaren Imports und hasht sie in **deterministischer
     * Reihenfolge** (sortiert nach Import-URI).
     *
     * **Zyklus-sicher**: bereits besuchte URIs werden übersprungen (kein StackOverflow
     * bei A→B→A oder beliebig tiefen Zyklen).
     *
     * **Reihenfolge-stabil**: `sortedMapOf` garantiert URI-alphabetische Reihenfolge
     * unabhängig davon, in welcher Reihenfolge der Resolver die Imports auflöst.
     * Das ist dieselbe Technik wie `.sorted()` in `ModelFingerprint`.
     *
     * @param script Wurzel-Skript-Quelltext.
     * @param importResolver Bildet eine Import-URI auf den Quelltext des importierten
     *   Skripts ab. Muss für jede URI denselben Text liefern (Determinismus-Pflicht).
     * @return 32-Byte SHA-256-Hash über Wurzel + alle transitiven Imports.
     */
    public fun hashTransitive(
        script: String,
        importResolver: (String) -> String,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val visited = LinkedHashSet<String>()

        // Gesamtheit aller kanonisierten Quellen, sortiert nach URI.
        // Wurzel unter dem Sentinel "" → kommt alphabetisch zuerst, kollisionsfrei.
        val collected = sortedMapOf<String, String>()
        collected[""] = canonicalize(script)
        collectImports(script, importResolver, visited, collected)

        for ((uri, canonical) in collected) {
            digest.update(uri.toByteArray(Charsets.UTF_8))
            digest.update(0) // URI/Content-Separator (konsistent mit ModelFingerprint)
            digest.update(canonical.toByteArray(Charsets.UTF_8))
            digest.update(1) // Eintrags-Separator
        }
        return digest.digest()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Rekursives Sammeln aller Import-URIs und ihrer kanonischen Quellen.
     *
     * [visited] verhindert Endlosrekursion bei zyklischen Imports (A→B→A).
     * [collected] ist ein `sortedMapOf` → garantiert reihenfolge-stabilen Hash.
     */
    private fun collectImports(
        script: String,
        importResolver: (String) -> String,
        visited: MutableSet<String>,
        collected: MutableMap<String, String>,
    ) {
        for (uri in extractImportUris(script)) {
            if (!visited.add(uri)) continue // Zyklus oder Doppel-Import → überspringen
            val importedSource = importResolver(uri)
            collected[uri] = canonicalize(importedSource)
            collectImports(importedSource, importResolver, visited, collected)
        }
    }

    /**
     * Extrahiert kUML-Modell-Import-URIs aus dem Quelltext.
     *
     * Erkennt Zeilen der Form `import("…")` bzw. `@file:Import("…")` — die kUML-Konvention
     * für Modell-Includes. Reine Kotlin-`import a.b.c`-Statements werden ignoriert,
     * da sie keinen externen Quelltext referenzieren.
     *
     * MVP-Heuristik: Bei Änderung der kUML-Import-Syntax muss nur diese Methode
     * angepasst werden — Zyklus-Detektion und Sortier-Logik bleiben davon unberührt.
     */
    private fun extractImportUris(script: String): List<String> {
        val regex = Regex("""(?:@file:Import|import)\s*\(\s*"([^"]+)"\s*\)""")
        return regex.findAll(script).map { it.groupValues[1] }.toList()
    }

    /** Führende Tabs → 4 Spaces (identisch zur Regel in [dev.kuml.cli.KumlFormatter]). */
    private fun expandLeadingTabs(line: String): String {
        if (!line.startsWith('\t')) return line
        val indent = line.takeWhile { it == '\t' }
        return "    ".repeat(indent.length) + line.removePrefix(indent)
    }
}
