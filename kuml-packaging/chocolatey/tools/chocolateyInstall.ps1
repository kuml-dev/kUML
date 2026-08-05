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
# executables (java.exe, keytool.exe, …) on the global PATH. Chocolatey only
# auto-shims .exe files it finds under tools\ — these bundled runtime
# executables would otherwise each get their own shim. The `kuml` command
# itself is a .bat launcher, which Chocolatey does NOT auto-shim (auto-shim
# generation only ever considers .exe); it is registered explicitly below
# via Install-BinFile.
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
# kuml-$version\bin\kuml-mcp.bat (the thin top-level wrapper, registered as a
# shim explicitly below via Install-BinFile) and
# kuml-$version\mcp\bin\kuml-mcp.bat (the real installDist launcher the
# wrapper CALLs into, one directory level down). Only the top-level one
# should ever become a shim, so the nested one is marked ignore — this
# matters only in case a future fix starts auto-shimming .bat files, but
# costs nothing to keep as a safety net. The Unix-style extension-less
# kuml-mcp launchers (bin\kuml-mcp, mcp\bin\kuml-mcp) need no .ignore: choco
# never auto-shims extension-less files, and Windows has no execute-bit for
# it to accidentally treat them as runnable.
$nestedMcpBatLauncher = Join-Path $toolsDir "kuml-$version\mcp\bin\kuml-mcp.bat"
if (Test-Path $nestedMcpBatLauncher) {
  New-Item "$nestedMcpBatLauncher.ignore" -ItemType File -Force | Out-Null
}

# kuml-lsp (2026-07-19): same double-launcher situation as kuml-mcp above —
# the zip ships kuml-$version\bin\kuml-lsp.bat (thin top-level wrapper,
# shimmed explicitly below) and kuml-$version\lsp\bin\kuml-lsp.bat (the real
# installDist launcher it CALLs into). Ignore the nested one for the same
# reason.
$nestedLspBatLauncher = Join-Path $toolsDir "kuml-$version\lsp\bin\kuml-lsp.bat"
if (Test-Path $nestedLspBatLauncher) {
  New-Item "$nestedLspBatLauncher.ignore" -ItemType File -Force | Out-Null
}

# Explicit shim registration (fix 2026-08-05): Chocolatey's automatic
# shim generation only ever considers .exe files under tools\ — it does NOT
# auto-shim .bat/.cmd launchers, contrary to the assumption baked into
# earlier versions of this script. Without this, `choco install kuml`
# reports success and deploys all files correctly, but leaves NO `kuml`
# command anywhere on PATH. Install-BinFile generates the missing shim
# (kuml.exe etc. under the Chocolatey bin dir) and registers it so
# Uninstall-BinFile in chocolateyUninstall.ps1 can remove it again.
Install-BinFile -Name 'kuml' -Path (Join-Path $toolsDir "kuml-$version\bin\kuml.bat")

$mcpBatLauncher = Join-Path $toolsDir "kuml-$version\bin\kuml-mcp.bat"
if (Test-Path $mcpBatLauncher) {
  Install-BinFile -Name 'kuml-mcp' -Path $mcpBatLauncher
}

$lspBatLauncher = Join-Path $toolsDir "kuml-$version\bin\kuml-lsp.bat"
if (Test-Path $lspBatLauncher) {
  Install-BinFile -Name 'kuml-lsp' -Path $lspBatLauncher
}
