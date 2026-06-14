plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

kotlin {
    jvmToolchain(21)
    // Kein explicitApi() — IntelliJ-Extension-Klassen werden über plugin.xml
    // reflektiv geladen, public ist hier eh die Norm.
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // kUML-Module, die wir in den Plugin-Classpath bundeln:
    // Die `KumlScript`-Template-Klasse (inkl. ihrer @KotlinScript-Annotation und
    // `KumlScriptCompilationConfiguration`) ist das Herzstück — IntelliJs Kotlin-
    // Plugin liest die Annotation und stellt darauf basierend Syntax-Highlighting
    // und Default-Imports bereit.
    implementation(project(":kuml-core:kuml-core-script"))
    // Defaultimports zeigen auf diese Module — wir müssen sie mitbundeln, damit
    // IntelliJ die Symbole in `*.kuml.kts` auflösen kann.
    implementation(project(":kuml-core:kuml-core-model"))
    implementation(project(":kuml-core:kuml-core-dsl"))
    implementation(project(":kuml-metamodel:kuml-metamodel-uml"))
    implementation(project(":kuml-metamodel:kuml-metamodel-c4"))

    // Kotlin Scripting Common / Annotations — wird transitive zwar mitkommen,
    // hier explizit, damit der Plugin-Classpath stabil bleibt.
    implementation(libs.kotlin.scripting.common)
    // Apache Batik Swing — JSVGCanvas for the live SVG preview panel (V2.0.30).
    // batik-transcoder/codec are already on the classpath via kuml-io-png;
    // batik-swing adds the Swing widget on top.
    implementation(libs.batik.swing)

    intellijPlatform {
        intellijIdea(
            libs.versions.intellij.idea
                .get(),
        )
        // Wir nutzen den `org.jetbrains.kotlin.scriptDefinitionsProvider`-EP des
        // gebündelten Kotlin-Plugins.
        bundledPlugin("org.jetbrains.kotlin")

        // Bewusst kein `testFramework(...)` — unsere Tests prüfen den
        // ScriptDefinitionsProvider standalone (ohne IDE-Sandbox). Der
        // IntelliJ-`TestFrameworkType.Platform` registriert einen
        // `LauncherSessionListener`, der unter Kotest nicht initialisiert
        // werden kann (Gradle bricht den Test-Executor ab). Der IDE-Sandbox-
        // Pfad ist über `runIde` / Plugin-Verifier abgedeckt.
    }

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // IntelliJ-Platform fügt JUnit4-Bridges in den Test-Classloader ein
    // (TestRule etc.). Ohne explizite JUnit4-Runtime-Dep scheitert der
    // Test-Executor-Start mit `NoClassDefFoundError: org/junit/rules/TestRule`.
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        // ID + Name kommen aus plugin.xml; hier nur die Build-Targets + Notes.
        // Version erbt von project.version (allprojects { version = "0.9.0" }),
        // explizit gespiegelt damit es beim Lesen dieser Datei sofort klar ist.
        version = project.version.toString()
        ideaVersion {
            // 2024.3 = build 243. `untilBuild` weglassen → kompatibel mit allen
            // späteren Versionen, bis Plugin-Verifier was anderes sagt.
            sinceBuild = "243"
        }
        changeNotes =
            """
            <h4>0.9.0</h4>
            <ul>
              <li>kUML-Monorepo Versions-Alignment: kein plugin-spezifischer Inhalt
                  in dieser Release — neue Reverse-Engineering-Pipeline lebt im CLI,
                  nicht im IntelliJ-Plugin.</li>
            </ul>
            <h4>0.8.0</h4>
            <ul>
              <li>K2-Kompatibilität: <code>supportsKotlinPluginMode supportsK2="true"</code>
                  deklariert — Plugin läuft wieder in IntelliJ 2024.x+ mit aktivem K2-Frontend.
                  (Ohne diese Deklaration zeigte der Marketplace: "Plugin is incompatible with
                  the Kotlin plugin in 'K2' mode".)</li>
              <li>Versions-Alignment: Plugin-Version folgt jetzt dem kUML-Monorepo
                  (<code>project.version</code>).</li>
            </ul>
            <h4>0.4.1</h4>
            <ul>
              <li>Code completion für kUML-DSL-Top-Level-Funktionen in <code>*.kuml.kts</code> (42 Items).</li>
              <li>Rename Refactoring (Shift+F6) für DSL-Element-Namen.</li>
              <li>Live Templates (12 Templates: uml, c4, sysml, classdiag, clazz, iface, enumc, sm, part, c4ctx, attr, port).</li>
            </ul>
            <h4>0.3.0</h4>
            <ul>
              <li>Code Folding für DSL-Lambda-Blöcke in <code>*.kuml.kts</code>.</li>
            </ul>
            <h4>0.2.0</h4>
            <ul>
              <li>Live-SVG Split-View (Editor links, Diagramm rechts) mit Zoom, Pan, Theme-Auswahl.</li>
              <li>Structure View: DSL-Elemente (States, Parts, Klassen …) im Structure-Panel.</li>
              <li>External Annotator + 4 Quick Fixes für Diagnostics aus dem kUML Script-Host.</li>
            </ul>
            <h4>0.1.0</h4>
            <ul>
              <li>Initiale Version: Script-Definition Provider, Dateityp-Registrierung, Datei-Icon.</li>
            </ul>
            """.trimIndent()
    }

    // IntelliJ Plugin Verifier: lokal nur die aktuelle IDE-Version prüfen.
    // Marketplace-Verifier-Lauf macht JetBrains beim Upload sowieso.
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("-Xmx1024m")
    }
}
