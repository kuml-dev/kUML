# kUML MCP-Skript-Sandbox — Security Coverage Matrix

Welle 8 of the MCP-Sandbox architecture (see the vault architecture note
`03 Bereiche/kUML/MCP-Skript-Sandbox — Architektur-Entwurf.md`, §2
"Bedrohungsmodell"). This is the single honesty-clamp document across Wellen
1-7: every attack vector from the threat model, mapped against the four
defence layers, with an honest per-cell verdict. It does not re-derive
anything — it consolidates the honesty notes already written into the Wellen
4-7 KDoc (`OsSandbox`, `WindowsJobObjectSandbox`, `AllowlistClassLoader`,
`SandboxClasspath`) and the Umsetzungsstand section of the architecture note.

Automated acceptance-level proof for every row lives in
[`SandboxSecurityAcceptanceTest`](src/test/kotlin/dev/kuml/core/script/SandboxSecurityAcceptanceTest.kt).
Per-layer/per-mechanism tests (not duplicated by that suite) are named in the
"Verified by" column.

## Legend

- ✅ blocked — behaviourally verified to stop the attack on this platform.
- ⚠️ partial — stops *part* of the vector, or is verified by construction only
  (not real OS behaviour on this development machine), or is a policy that
  narrows but does not eliminate the risk.
- ❌ not covered — known, documented gap. Not silently assumed safe.
- — not applicable to that layer for this vector.

## Matrix

| Attack vector (Bedrohungsmodell §2) | Layer 1 — Denylist (`KumlScriptGuard`, Welle 1) | Layer 2 — Timeout/Heap-Cap (Kindprozess/Pool, Wellen 2-3) | Layer OS — macOS `sandbox-exec` (Welle 4) | Layer OS — Linux `bwrap` (Welle 5) | Layer OS — Windows Job Object (Welle 6) | Layer B — Curated Classpath + `AllowlistClassLoader` (Welle 7) |
|---|---|---|---|---|---|---|
| **Dateisystem-Exfiltration** (`~/.ssh`, `~/.aws`, beliebige Datei außerhalb Workdir) | ✅ blockiert jede DSL-Formulierung, die `File(`/`readText`/`readBytes`/`java.io`/`java.nio` benennt | — (nicht diese Schicht) | ✅ blockiert — `(deny file-write*)` außer Workdir, `(deny file-read*)` auf `~/.ssh` etc., empirisch verifiziert (`OsSandboxTest`) | ✅ blockiert — auf echtem Linux (Ubuntu 26.04) verhaltensverifiziert (`OsSandboxTest`, `isLinux`-Fälle): Write blockiert; `~/.ssh`-Read war zunächst **nicht** blockiert (`--ro-bind / /` allein macht Secrets lesbar) und wurde per `SECRET_HOME_SUBPATHS`-`--tmpfs`-Shadowing gefixt (2026-07-04) | ❌ nicht abgedeckt — Job Object hat kein Mount-Namespace-Äquivalent; Restricted-Token-Scaffold dokumentiert, aber inaktiv (`restrictedTokenSupported() == false`) | ⚠️ teilweise — blockt Third-Party-Jars (JNA etc.) zur Compile-Zeit; `java.io`/`java.nio` sind JDK-Boot-Layer-Klassen und damit durch diese Schicht **nicht** filterbar |
| **Netzwerk-Pivot** (externe IP, `169.254.169.254`, `localhost`) | ✅ blockiert jede DSL-Formulierung, die `java.net`/`Socket(`/`URL(`/`HttpClient`/`URLConnection` benennt | — | ✅ blockiert — `(deny network*)`, DNS **und** Direkt-IP, empirisch verifiziert (`OsSandboxTest`) | ✅ blockiert — `--unshare-all` ohne `--share-net`, auf echtem Linux-Kernel verhaltensverifiziert (`OsSandboxTest`, Raw-IP-Connect) | ❌ **nicht abgedeckt (bekannte, dokumentierte Lücke)** — Job Object hat keine Netzwerk-Egress-Kontrolle; auf Windows mildert **nur** Layer 1 dieses Risiko | ⚠️ teilweise — dieselbe JDK-Boot-Layer-Grenze wie oben (`java.net` nicht filterbar) |
| **Persistenz** (`~/.zshrc`, `~/Library/LaunchAgents`, Cron/launchd) | ✅ blockiert jede DSL-Formulierung, die `File(`/`writeText`/`appendText` benennt | — | ✅ blockiert — dieselbe Workdir-Confinement wie Exfiltration; Ziel liegt außerhalb Workdir | ✅ blockiert — dieselbe Verhaltens-Verifikation wie oben (Write-Escape-Test) | ❌ nicht abgedeckt — dieselbe fehlende FS-Confinement wie Exfiltration | ⚠️ teilweise — dieselbe JDK-Boot-Layer-Grenze |
| **DoS** (Endlosschleife, OOM, Zip-Bomb-artig) | ❌ **bewusst nicht abgedeckt für Endlosschleifen/OOM** — kein Text-Pattern für `while(true){}` oder Allokations-Schleifen (dokumentierter Welle-2-Fund); ✅ blockiert aber die Zip-Bomb-adjacente Variante (übergroßes Skript) über `MAX_SCRIPT_LENGTH` | ✅ blockiert — Wall-Clock-Timeout (Default 15s) + `-Xmx256m`-Heap-Cap; Pool bleibt danach responsiv (Nachweis in dieser Welle: Suite prüft Folge-Request nach DoS-Versuch) | — (Layer 2 fängt es vor dem OS-Käfig-relevanten Bereich) | — | — | — |
| **Reflection-Bypass des Denylists** (`Runtime::class.java.getMethod(...).invoke(null)`, `getConstructor().newInstance()`, `MethodHandles`, `::class.java`) | ✅ blockiert alle vier audit-verifizierten Payloads (dedizierte Unit-Tests in `KumlScriptGuardTest`, Acceptance-Nachweis in dieser Welle) | — | — (Reflection selbst ist kein OS-Ressourcen-Problem) | — | — | ⚠️ teilweise — Backstop *falls* Layer 1 umgangen würde: kuratierter Classpath blockt Fremd-Jars zur Compile-Zeit, aber Reflection über bereits geladene `dev.kuml.*`/`kotlin.*`-Typen zu JDK-Boot-Klassen ist **nicht vollständig ausschließbar** (dokumentierte Welle-7-Grenze) |
| **Prozess-Start-Versuch** (`ProcessBuilder`/`Runtime.exec`) | ✅ blockiert jede DSL-Formulierung, die `ProcessBuilder`/`Runtime.getRuntime()`/`exec(` benennt | — | ✅ implizit — gekäfigter Prozess hat ohnehin kein Netz/FS für einen gestarteten Kindprozess | ⚠️ teilweise — `--unshare-all` beinhaltet PID-Namespace (verhindert Ausbruch aus dem Namespace), aber ein Spawn *innerhalb* des Käfigs gelingt (der Kindprozess erbt dieselbe Netz-/Mount-Isolation und kommt damit nicht weiter — auf echtem Linux verifiziert) | ⚠️ teilweise — `JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 1`, **konstruktions-verifiziert, nicht auf echtem Windows verhaltensverifiziert** (kritischer Nachweis für Struct-Offsets fehlt, siehe Welle-6-Umsetzungsstand Punkt (3)) | — |

## Bekannte, ehrlich dokumentierte Lücken (Querverweis)

Diese Lücken sind **nicht** neu in dieser Welle entdeckt — sie sind die
konsolidierte Zusammenfassung von Vermerken, die bereits in Wellen 5-7
geschrieben wurden:

1. **Linux-OS-Käfig (Welle 5) ist auf echtem Linux verhaltensverifiziert
   (2026-07-04, Ubuntu 26.04, Kernel 7.0, `bwrap` 0.11.1).** Dabei wurde ein
   echter Fund gemacht und gefixt: die ursprüngliche `bwrap`-Argv-Konstruktion
   ließ `~/.ssh`/`~/.aws`/`~/.gnupg`/`~/.config/gcloud` lesbar (nur
   Write/Netzwerk waren blockiert) — kein Denylist-Problem, sondern eine
   echte Lücke im OS-Käfig-Code selbst. Fix: `SECRET_HOME_SUBPATHS`-Liste,
   jedes existierende Verzeichnis wird per privatem `--tmpfs`-Mount
   überdeckt (Details in `OsSandbox`-KDoc, Abschnitt "Verified on real
   Linux"). **Weiterhin offen**: der Container-/Kein-unprivileged-userns-
   Degradationsfall wurde nicht geprüft (diese Testmaschine hatte
   `unprivileged_userns_clone=1`) — deshalb bleibt der Linux-Default
   `best-effort`, nicht `required`.
2. **Windows-OS-Käfig (Welle 6) ist verhaltens-ungetestet UND strukturell
   schwächer.** Selbst nach Verifikation fehlen Netzwerk-Egress-Kontrolle und
   enge FS-Confinement grundsätzlich (Job Object kann beides nicht). Auf
   Windows ist Netzwerk-Exfiltration **nur** durch Layer 1 (Denylist)
   gemildert — kein OS-Netz-Käfig wie bei macOS/Linux.
3. **JDK-Boot-Module-Layer ist durch Layer B (ClassLoader-Allowlist)
   grundsätzlich nicht filterbar.** `java.*`/`jdk.*` liegen im
   Boot-Class-Loader, der nie einen user-definierten Loader konsultiert
   (empirisch verifiziert in Welle 7: kein `loadClass`-Aufruf auf dem
   `AllowlistClassLoader` für `java.io.File` o.ä.). Nur der OS-Käfig (Wellen
   4-6) neutralisiert die *Wirkung* dieser Klassen — Layer B kann sie nicht
   *ausblenden*.
4. **Reflection-Bridges über bereits geladene erlaubte Typen sind über Layer B
   allein nicht vollständig ausschließbar.** Sobald `dev.kuml.*`/`kotlin.*`
   geladen sind (müssen sie für jedes legitime Skript), kann theoretisch über
   ein aufgelöstes Klassenobjekt zu Methoden navigiert werden, die selbst
   JDK-Boot-Klassen anfassen. Deshalb ist Layer B explizit **Schicht 2 hinter
   dem OS-Käfig**, nie die alleinige Verteidigung.
5. **DoS über Endlosschleifen/übermäßige Allokation hat kein Text-Pattern in
   Layer 1** — das ist beabsichtigt (ein solches Pattern wäre extrem
   false-positive-anfällig gegen legitime Skripte mit Schleifen zur
   Batch-Erzeugung von Elementen). Layer 2 (Timeout/Heap-Cap) ist die einzige
   und ausreichende Verteidigung hierfür.

## Gesamtfazit

Jeder Angriffsvektor aus dem Bedrohungsmodell wird auf der verifizierten
Plattform (macOS) durch **mindestens eine** Schicht nachweislich gestoppt —
in aller Regel bereits durch Layer 1 (Denylist) für DSL-formulierte Angriffe,
mit Layer OS (`sandbox-exec`) als empirisch verifiziertem Backstop für den
hypothetischen Fall eines Denylist-Bypasses. Die einzige **echte, auf jeder
Plattform bestehende Lücke** ist Netzwerk-Exfiltration auf Windows (Punkt 2
oben) — dort trägt ausschließlich Layer 1 die Last, ohne OS-Netz-Käfig als
Rückversicherung. Diese Lücke ist nicht neu — sie war bereits am Ende von
Welle 6 dokumentiert — und bleibt bis zu einer echten Windows-Firewall-/WFP-
Integration offen.
