package dev.kuml.jetbrains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Bewusst kein IntelliJ-Plugin-TestBench-Test — der Provider hat keine
 * IntelliJ-Abhängigkeit über die `ScriptDefinitionsProvider`-Schnittstelle
 * hinaus, und der spannende Vertrag (welche FQN, welcher Classpath) lässt sich
 * standalone prüfen. Der Plugin-Verifier-Lauf (Gradle-Task
 * `verifyPluginProjectConfiguration`) deckt das IDE-Loading separat ab.
 */
class KumlScriptDefinitionsProviderTest :
    FunSpec({

        val provider = KumlScriptDefinitionsProvider()

        test("provider exposes the kUML script template FQN") {
            provider.getDefinitionClasses().toList() shouldBe
                listOf("dev.kuml.core.script.KumlScript")
        }

        test("provider has a stable, human-readable id") {
            provider.id shouldBe "kUML Script Definitions"
        }

        test("provider opts out of generic script discovery") {
            // Wenn ein anderes Plugin per discovery noch was beisteuern wollte,
            // soll das unsere Definition nicht überschreiben.
            provider.useDiscovery() shouldBe false
        }

        test("classpath collection is robust against missing protection domains") {
            // In der IntelliJ-Plugin-Test-Sandbox wird ein
            // `com.intellij.util.lang.PathClassLoader` injiziert, der für
            // unsere Marker-Klassen `protectionDomain.codeSource.location` als
            // `null` zurückliefert. Im produktiven IDE-Run dagegen kommen die
            // Klassen aus echten Plugin-JARs und haben eine gesetzte Location.
            //
            // Wir prüfen hier nur, dass die Methode **nicht crasht** — der
            // konkrete Inhalt ist environment-abhängig. Die echte
            // Funktionsprüfung passiert im IDE-Sandbox-Run via `runIde` /
            // Plugin-Verifier.
            val cp = collectPluginClasspath()
            // Liste darf leer sein (Test-Env) — wichtig ist nur, dass keine
            // Exception geflogen ist und das Ergebnis nicht doppelt enthält.
            cp.size shouldBe cp.distinct().size
        }

        test("FQN constant matches the public companion value") {
            KumlScriptDefinitionsProvider.KUML_SCRIPT_TEMPLATE_FQN shouldBe
                "dev.kuml.core.script.KumlScript"
            provider.getDefinitionClasses() shouldContain
                KumlScriptDefinitionsProvider.KUML_SCRIPT_TEMPLATE_FQN
        }
    })
