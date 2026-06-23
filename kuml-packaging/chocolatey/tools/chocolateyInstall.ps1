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
