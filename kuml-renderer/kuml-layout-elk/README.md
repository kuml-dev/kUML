# kuml-layout-elk

ELK-Adapter für kUML — implementiert `KumlLayoutEngine` aus `kuml-layout-api` auf Basis von
[Eclipse Layout Kernel](https://www.eclipse.org/elk/) (`elk.layered`, Sugiyama-Algorithmus).
Das Modul übersetzt einen `LayoutGraph` in einen ELK-Graphen, führt das Layout aus und liefert
ein serialisierbares `LayoutResult` zurück. Es ist die V1-Standard-Engine für alle
Box-und-Kante-Diagrammtypen (UML-Klassen, -Komponenten, -UseCase, -Zustand sowie alle C4-Varianten
und Generic). ELK-Typen verlassen das Modul nicht — alle öffentlichen Signaturen sind ELK-frei.

**Designentwurf:** `03 Bereiche/kUML/Plan/Phase 1 — ELK-Adapter (Designentwurf).md`

> **Native-Image-Reflection-Config folgt in `kuml-packaging`.**
> ELK benötigt Reflection für seinen Algorithmus-Service-Mechanismus. Die entsprechende
> `reflect-config.json` und Initialisierungsstrategie werden im Modul `kuml-packaging/kuml-native`
> ergänzt — dieses Modul ist für JVM-Laufzeit konfiguriert und getestet.
