$ErrorActionPreference = 'Stop'

$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$version  = $env:ChocolateyPackageVersion

# kuml-runtime-<version>-windows-x86_64.zip is a self-contained bundle:
# app jars + a jlink-built Java 21 runtime + a launcher (bin\kuml.bat) that is
# patched to point JAVA_HOME at the bundled runtime\. No system JDK required.
# The checksum is injected at release time by the publish-chocolatey job in
# kuml-dev/kuml's .github/workflows/release.yml.
$packageArgs = @{
  packageName    = $env:ChocolateyPackageName
  unzipLocation  = $toolsDir
  url64bit       = "https://github.com/kuml-dev/kuml/releases/download/v$version/kuml-runtime-$version-windows-x86_64.zip"
  checksum64     = '__SHA256__'
  checksumType64 = 'sha256'
}

Install-ChocolateyZipPackage @packageArgs

# Prevent Chocolatey's auto-shim step from exposing the bundled JRE's own
# executables (java.exe, keytool.exe, …) on the global PATH. We only want a
# single `kuml` shim — which Chocolatey creates automatically from
# kuml-<version>\bin\kuml.bat.
$runtimeDir = Join-Path $toolsDir "kuml-$version\runtime"
if (Test-Path $runtimeDir) {
  Get-ChildItem -Path $runtimeDir -Recurse -Include *.exe -ErrorAction SilentlyContinue |
    ForEach-Object { New-Item "$($_.FullName).ignore" -ItemType File -Force | Out-Null }
}

# The shell launcher (bin\kuml, no extension) is meaningless on Windows — keep
# Chocolatey from trying to shim it.
$shellLauncher = Join-Path $toolsDir "kuml-$version\bin\kuml"
if (Test-Path $shellLauncher) {
  New-Item "$shellLauncher.ignore" -ItemType File -Force | Out-Null
}

# kuml-mcp (v0.23.4): the zip ships TWO functioning kuml-mcp launchers —
# kuml-$version\bin\kuml-mcp.bat (the thin top-level wrapper Chocolatey should
# shim, matching how kuml.bat becomes the `kuml` command) and
# kuml-$version\mcp\bin\kuml-mcp.bat (the real installDist launcher the
# wrapper CALLs into, one directory level down). Both are valid .bat files
# with the same base name, so without an .ignore Chocolatey's auto-shim step
# would try to create two `kuml-mcp` shims from the same package. Ignore the
# nested one — same "single shim per command" hygiene as the shellLauncher
# ignore above and the runtime .exe loop below. The Unix-style extension-less
# kuml-mcp launchers (bin\kuml-mcp, mcp\bin\kuml-mcp) need no .ignore: choco
# only auto-shims .exe/.bat/.cmd, and Windows has no execute-bit for it to
# accidentally treat them as runnable.
$nestedMcpBatLauncher = Join-Path $toolsDir "kuml-$version\mcp\bin\kuml-mcp.bat"
if (Test-Path $nestedMcpBatLauncher) {
  New-Item "$nestedMcpBatLauncher.ignore" -ItemType File -Force | Out-Null
}

# kuml-lsp (2026-07-19): same double-shim situation as kuml-mcp above — the
# zip ships kuml-$version\bin\kuml-lsp.bat (thin top-level wrapper Chocolatey
# should shim) and kuml-$version\lsp\bin\kuml-lsp.bat (the real installDist
# launcher it CALLs into). Ignore the nested one for the same reason.
$nestedLspBatLauncher = Join-Path $toolsDir "kuml-$version\lsp\bin\kuml-lsp.bat"
if (Test-Path $nestedLspBatLauncher) {
  New-Item "$nestedLspBatLauncher.ignore" -ItemType File -Force | Out-Null
}
