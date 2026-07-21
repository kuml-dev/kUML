---
title: UML Komponentendiagramm – kUML Plugin-API
date: 2026-07-12
tags:
  - kUML
  - beispiel
  - uml
  - komponentendiagramm
  - plugin-api
status: aktiv
---

# UML Komponentendiagramm — kUML Plugin-API

← [[00 Übersicht]] · Bereich [[03 Bereiche/kUML/Übersicht|kUML]]

> [!info] Worum es geht
> **Dogfooding**: die kUML-Plugin-API selbst, modelliert als kUML-Komponentendiagramm. `kUML Core` benötigt (`requires`) drei Schnittstellen über drei separate Ports (`theme`, `renderer`, `codegen`) — jeweils bereitgestellt (`provides`) von einer eigenständigen Plugin-Komponente über deren `spi`-Port. Genau dieses Modell wird in der Präsentation [[02 Projekte/kUML Präsentation Deutsch|kUML Präsentation Deutsch]] gezeigt (Folien „Plugin-API: Erweiterungspunkte als Kotlin-DSL" und „Plugin-API — gerendertes Komponentendiagramm").
>
> [!success]- Renderer-Bug gefixt (entdeckt 2026-07-12, gefixt 2026-07-12)
> Bei drei nebeneinanderliegenden Plugin-Komponenten liefen zwei der Port-zu-Port-Kanten (`kUML Core.theme` → `PdV Theme Plugin.spi` und `kUML Core.renderer` → `PDF Renderer Plugin.spi`) geradewegs durch die dazwischenliegende `TypeScript Codegen Plugin`-Box, statt um sie herum geroutet zu werden. Root Cause: `ComponentPortEdgeClipper` (`kuml-io/kuml-io-svg`) prüfte weder im U-Form- noch im Z-Form-Routing-Zweig Geschwister-Bounding-Boxen. Fix: neuer `avoidObstacles()`-Nachbearbeitungsschritt, der die Waypoint-Kette gegen alle sichtbaren Komponenten-Boxen prüft und blockierte Segmente über die nächste freie Boxecke umleitet — analog zu den Präzedenzfixes für Package- und Choreography-Kanten. Regressionstest `ComponentPortEdgeClipperTest` ergänzt (Topologie exakt aus diesem Beispiel), `./gradlew :kuml-io:kuml-io-svg:jvmTest` grün, visuell bestätigt. Nach `master` gesquasht (`d3afe8e7`), noch nicht getaggt/released. Das oben eingebettete Diagramm ist bereits mit dem gefixten Renderer erzeugt.

## Diagramm

```kuml
componentDiagram(name = "kUML Plugin-API") {
    val themeApi = interfaceOf(name = "KumlThemePlugin") {
        operation(name = "themes") { returns(typeName = "List<KumlTheme>") }
    }
    val rendererApi = interfaceOf(name = "KumlRendererPlugin") {
        operation(name = "render") { returns(typeName = "ByteArray") }
    }
    val codegenApi = interfaceOf(name = "KumlCodegenPlugin") {
        operation(name = "generate") { returns(typeName = "String") }
    }

    val core = component(name = "kUML Core") {
        port(name = "theme")
        port(name = "renderer")
        port(name = "codegen")
        requires(iface = themeApi)
        requires(iface = rendererApi)
        requires(iface = codegenApi)
    }
    val pdvTheme = component(name = "PdV Theme Plugin") {
        port(name = "spi")
        provides(iface = themeApi)
    }
    val pdfRenderer = component(name = "PDF Renderer Plugin") {
        port(name = "spi")
        provides(iface = rendererApi)
    }
    val tsCodegen = component(name = "TypeScript Codegen Plugin") {
        port(name = "spi")
        provides(iface = codegenApi)
    }

    connect(end1 = core, port1 = "theme",    end2 = pdvTheme,   port2 = "spi")
    connect(end1 = core, port1 = "renderer",  end2 = pdfRenderer, port2 = "spi")
    connect(end1 = core, port1 = "codegen",   end2 = tsCodegen,   port2 = "spi")
}
```

## DSL-Anatomie

| Element | Bedeutung |
|---|---|
| `componentDiagram(name = …) { … }` | Top-Level: erzeugt ein Komponentendiagramm. |
| `interfaceOf(name = …) { … }` | Sichtbarer Interface-Knoten — hier drei SPI-Schnittstellen, je eine pro Plugin-Kategorie. |
| `component(name = …) { … }` | Komponente als Knoten — hier `kUML Core` als zentraler Konsument, drei Plugins als Anbieter. |
| `port(name = …)` | Ein Port pro Erweiterungspunkt am Core bzw. pro Plugin. |
| `requires(iface)` / `provides(iface)` | `kUML Core` benötigt alle drei Schnittstellen, jedes Plugin stellt genau eine bereit — Lollipop-Notation im Rendering (Kreis = provides, Halbschale = requires). |
| `connect(end1, port1, end2, port2)` | Verbindet Core-Port mit Plugin-Port — genau diese drei Kanten waren vom Edge-Routing-Bug betroffen. |

## Verwandte Beispiele

- [[12 UML Component – Order Architecture]] — einfacheres Komponentendiagramm ohne mehrere nebeneinanderliegende Provider
- Companion-Diagramm (Klassendiagramm-Sicht auf dieselbe API, `KumlPlugin`-Interface-Hierarchie): DSL-Quelle in `presentation-de/sources/36_UML_Klasse_Plugin_API.kuml.kts` — noch nicht als eigene Vault-Notiz angelegt
- [[03 Bereiche/kUML/Übersicht]] — Plugin-API-Architektur im Kontext
