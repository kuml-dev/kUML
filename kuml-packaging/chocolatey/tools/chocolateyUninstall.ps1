$ErrorActionPreference = 'Stop'

# Install-ChocolateyZipPackage records every extracted file in
# <packageName>.zip.txt and removes them automatically on uninstall. The
# `kuml` / `kuml-mcp` / `kuml-lsp` shims are NOT part of that — they are
# registered explicitly via Install-BinFile in chocolateyInstall.ps1
# (Chocolatey does not auto-shim .bat launchers), so they must be torn down
# explicitly here too. No -ErrorAction override on any of the three:
# Uninstall-BinFile already Test-Path-guards each shim internally and no-ops
# (Write-Debug, no throw) when a shim was never registered — e.g. for
# kuml-mcp/kuml-lsp on installs predating the 2026-08-05 fix — so suppressing
# errors here would only hide a genuine removal failure (e.g. a locked
# shim .exe), inconsistent with how the mandatory `kuml` call already behaves.
Uninstall-BinFile -Name 'kuml'
Uninstall-BinFile -Name 'kuml-mcp'
Uninstall-BinFile -Name 'kuml-lsp'

Write-Host "kuml: bundled runtime and shims removed by Chocolatey."
