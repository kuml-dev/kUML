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
        ideaVersion {
            // 2024.3 = build 243. `untilBuild` weglassen → kompatibel mit allen
            // späteren Versionen, bis Plugin-Verifier was anderes sagt.
            sinceBuild = "243"
        }
        changeNotes =
            """
            Initial release of the kUML IntelliJ Platform plugin.
            <ul>
              <li>Recognises <code>*.kuml.kts</code> scripts via Kotlin scripting.</li>
              <li>Syntax highlighting inherited from the Kotlin language.</li>
              <li>File icon for kUML diagram scripts.</li>
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
