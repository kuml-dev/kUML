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

// V0.18 — Maven-Kotlin-Scripting weder bündeln NOCH dagegen kompilieren.
//
// Zwei Probleme entstehen sonst:
//  1) BUNDLING: gebündelte Kopien von ScriptDefinitionsSource / @KotlinScript
//     kollidieren mit den Kopien des Kotlin-Plugins (zwei Class-Objekte gleichen
//     Namens aus verschiedenen Classloadern) → "does not implement interface".
//  2) COMPILE: das maven-Artefakt kotlin-scripting-compiler-impl-embeddable:2.4.0
//     hat eine ANDERE ScriptDefinition.FromTemplate-Signatur (…, KClass, KClass,
//     Iterable, …) als die im Kotlin-Plugin (Build 261) tatsächlich geladene
//     (…, KClass, KClass) → zur Laufzeit NoSuchMethodError.
//
// Lösung: alle kotlin-scripting-*-Artefakte aus compileClasspath UND
// runtimeClasspath ausschließen. Sämtliche kotlin.script.experimental.* /
// org.jetbrains.kotlin.scripting.definitions.* Symbole kommen damit – beim
// Kompilieren wie zur Laufzeit – aus dem gebündelten Kotlin-Plugin
// (via <depends>org.jetbrains.kotlin</depends> bzw. bundledPlugin(...)).
// So sind Compile- und Runtime-Signaturen garantiert identisch.
listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { cfg ->
    configurations.named(cfg) {
        listOf(
            "kotlin-scripting-common",
            "kotlin-scripting-jvm",
            "kotlin-scripting-jvm-host",
            "kotlin-scripting-compiler-embeddable",
            "kotlin-scripting-compiler-impl-embeddable",
        ).forEach { exclude(group = "org.jetbrains.kotlin", module = it) }

        // Batik pulls in xml-apis (javax.xml.parsers.*, org.xml.sax.*, org.w3c.dom.*),
        // which SHADOWS the JDK's JAXP classes inside the plugin classloader. When Batik
        // parses SVG in the running IDE, SAXParserFactory.newInstance() returns IntelliJ's
        // Xerces impl (extending the JDK's SAXParserFactory) which then can't be cast to the
        // plugin-bundled SAXParserFactory → ClassCastException in SAXDocumentFactory.<clinit>.
        // Excluding ONLY xml-apis lets javax.xml/org.xml.sax/org.w3c.dom resolve to the JDK
        // (single class, no conflict). xml-apis-ext is KEPT — it provides org.w3c.dom.svg.*,
        // which the JDK does not ship and Batik requires.
        exclude(group = "xml-apis", module = "xml-apis")
    }
}

dependencies {
    // Shared editor-agnostic "brain" (completion catalogue, rename extractor,
    // diagnostics TSV parser, CLI locator) — pure Kotlin, no IntelliJ dep.
    implementation(project(":kuml-lang-support"))
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

    // Kotlin-Scripting-API (kotlin.script.experimental.* /
    // org.jetbrains.kotlin.scripting.definitions.*) kommt ausschließlich aus dem
    // gebündelten Kotlin-Plugin — siehe der configurations-Ausschluss oben.
    // Daher hier KEINE expliziten kotlin-scripting-*-Deps: sie würden nur die
    // versions-fremden maven-Artefakte zurück auf den Compile-Classpath holen.
    // Apache Batik Swing — JSVGCanvas for the live SVG preview panel (V2.0.30).
    // batik-transcoder/codec are already on the classpath via kuml-io-png;
    // batik-swing adds the Swing widget on top.
    implementation(libs.batik.swing)
    // V0.18 — Live Preview: Layout + SVG renderer classes (previously missing from plugin classpath,
    // causing renderToSvgReflective() to always return null via silent ClassNotFoundException).
    implementation(project(":kuml-renderer:kuml-layout-api"))
    implementation(project(":kuml-renderer:kuml-layout-bridge"))
    implementation(project(":kuml-renderer:kuml-layout-elk"))
    implementation(project(":kuml-renderer:kuml-layout-grid"))
    implementation(project(":kuml-renderer:kuml-themes-core"))
    // Bewusst NICHT kuml-themes (Compose-Adapter) — zieht androidx.compose.* aus dem
    // google()-Repo, das im IntelliJ-Plugin-Modul nicht verfügbar ist. Der Reflection-
    // Renderer braucht nur ThemeRegistry/PlainThemeProvider aus kuml-themes-core.
    implementation(project(":kuml-io:kuml-io-svg"))
    // V0.18 — Blueprint metamodel needed for dev.kuml.blueprint.dsl.* + dev.kuml.blueprint.model.*
    // default imports in KumlScriptCompilationConfiguration (absent classpath caused resolution gaps).
    implementation(project(":kuml-metamodel:kuml-metamodel-blueprint"))

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
            <h4>0.19.2</h4>
            <ul>
              <li>Internal API removed: the plugin's bundled DSL classpath is now resolved purely via the JDK
                  (<code>Class.getResource</code> → <code>lib/</code> jar), no longer through the internal
                  <code>PluginManager</code>/<code>PluginManagerCore</code> APIs — clears the Marketplace
                  verifier "internal API usage" problem.</li>
              <li>Replaced deprecated <code>FileChooserDescriptorFactory.createSingleFileDescriptor()</code>
                  with the documented <code>FileChooserDescriptor(...)</code> constructor.</li>
            </ul>
            <h4>0.19.1</h4>
            <ul>
              <li>Replaced deprecated-for-removal <code>TextFieldWithBrowseButton.addBrowseFolderListener(String, String, Project, descriptor)</code>
                  with the 2-arg <code>(Project?, FileChooserDescriptor)</code> overload.</li>
              <li>Fixed deprecated <code>Document.addDocumentListener(listener)</code> call by passing the
                  editor wrapper as a <code>Disposable</code> — listener auto-removed on editor close.</li>
            </ul>
            <h4>0.19.0</h4>
            <ul>
              <li>Published to JetBrains Marketplace — install via <em>Settings → Plugins → Marketplace → search "kUML"</em>.</li>
              <li>Scroll Pane for SVG preview: horizontal and vertical scrollbars appear automatically for large diagrams.</li>
              <li>Hand-drag interactor: left-click + drag pans the view; mouse wheel scrolls; Ctrl+wheel zooms.</li>
              <li>Zoom model revised: fit/zoom actions control canvas preferred size (scroll-compatible).</li>
              <li>Toolbar redesign: icon-only buttons + new Fit Width / Fit Height / Zoom Out actions.</li>
              <li>Plugin icon updated: navy background with golden {k} monogram and "kUML" lettering.</li>
              <li>Live preview now renders via external <code>kuml</code> CLI — no bundled renderer required.</li>
              <li>New <code>kuml diagnostics</code> command: emits compile/eval errors as TSV for IDE integration.</li>
            </ul>
            <h4>0.18.0</h4>
            <ul>
              <li>Live Preview repariert: Renderer- und Layout-Module
                  (<code>kuml-layout-api/bridge/elk/grid</code>, <code>kuml-themes-core/themes</code>,
                  <code>kuml-io-svg</code>) werden jetzt in das Plugin gebündelt — vorher fehlten sie
                  im Plugin-Classpath, sodass <code>renderToSvgReflective()</code> immer
                  <code>null</code> zurückgab und nur „No diagram" erschien.</li>
              <li>Blueprint-Metamodell gebündelt: <code>dev.kuml.blueprint.dsl.*</code> und
                  <code>dev.kuml.blueprint.model.*</code> sind jetzt im Script-Definitionen-Classpath
                  verfügbar (fehlten bisher als <code>ScriptDefinitionsProvider</code>-Marker).</li>
              <li>Plugin-Icon auf kUML-Brand aktualisiert: navy Hintergrund (#1d2b4f) mit goldenem
                  <code>{k}</code>-Monogramm (#c49a2e) — pfadbasiert, keine Font-Abhängigkeit.</li>
              <li>Datei-Icon (<code>*.kuml.kts</code>): goldes „k"-Monogramm pfadbasiert;
                  <code>fileIconProvider</code> mit <code>order="first"</code> stellt sicher, dass das
                  kUML-Icon Vorrang vor dem generischen Kotlin-Script-Icon hat.</li>
            </ul>
            <h4>0.11.0</h4>
            <ul>
              <li>kUML-Monorepo Versions-Alignment: kein plugin-spezifischer Inhalt
                  in dieser Release — Renderer- und Layout-Verbesserungen
                  (SEQ Create-Arrow + Guard-Repositioning, asymmetrische Fragment-Frames,
                  STM Connection-aware Sizing, Activity/Interaction-Overview pseudo-node
                  Edge-Clipping, UML Component Port-Clipping + Contracts, Package-Edge
                  Endpoint-Snapping, C4 DeploymentNode/Interaction/DescriptionWrap)
                  leben im SVG-Renderer und in der Layout-Bridge, nicht im IntelliJ-Plugin.</li>
            </ul>
            <h4>0.10.0</h4>
            <ul>
              <li>kUML-Monorepo Versions-Alignment: kein plugin-spezifischer Inhalt
                  in dieser Release — kuml-desktop Editor + Renderer-Verbesserungen
                  (Connection-aware Sizing, SelfLoopRouter, Sequence z-order fix)
                  leben im CLI/Desktop-Modul, nicht im IntelliJ-Plugin.</li>
            </ul>
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

    // Plugin Signing — Pflicht für den Marketplace-Upload.
    // Credentials kommen aus Umgebungsvariablen / CI-Secrets, nie hardcoded.
    // Zertifikat generieren: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    // Lokal ohne Signing bauen: einfach diese Variablen nicht setzen → signPlugin wird übersprungen.
    signing {
        certificateChain.set(providers.environmentVariable("KUML_PLUGIN_CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("KUML_PLUGIN_PRIVATE_KEY"))
        password.set(providers.environmentVariable("KUML_PLUGIN_PRIVATE_KEY_PASSWORD"))
    }

    // Marketplace-Upload via `./gradlew publishPlugin`.
    // Token aus: https://plugins.jetbrains.com/author/me → "Tokens"
    publishing {
        token.set(providers.environmentVariable("KUML_PLUGIN_PUBLISH_TOKEN"))
        // Ohne `channels` → landet im stabilen "stable"-Channel.
        // Für EAP: channels.set(listOf("eap"))
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
