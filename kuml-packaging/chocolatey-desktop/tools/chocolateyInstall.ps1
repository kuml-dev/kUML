$ErrorActionPreference = 'Stop'

$version = $env:ChocolateyPackageVersion

# kuml-desktop-<version>.msi is the unsigned Windows MSI produced by
# `./gradlew :kuml-desktop:packageMsi` (jpackage, bundles its own Java 21
# runtime — no system JDK required). The checksum is injected at release time
# by the publish-chocolatey-desktop job in kuml-dev/kuml's
# .github/workflows/release.yml, from the exact MSI attached to that
# release's GitHub Release. The URL itself needs no injection — Chocolatey
# sets ChocolateyPackageVersion from the nuspec version it just packed, same
# pattern as kuml.nuspec's own chocolateyInstall.ps1.
$packageArgs = @{
  packageName    = $env:ChocolateyPackageName
  fileType       = 'msi'
  url64bit       = "https://github.com/kuml-dev/kuml/releases/download/v$version/kuml-desktop-$version.msi"
  checksum64     = '__SHA256__'
  checksumType64 = 'sha256'
  # jpackage-generated MSIs are unsigned in this Phase-1 release (see the
  # "Apple-Signierung/Notarisierung (Phase 2)" tracking note in the vault —
  # the Windows counterpart is Authenticode signing, not yet done either).
  # `/qn /norestart` is the standard silent-install pair for an MSI produced
  # by jpackage's WiX backend. Exit codes: 0 = success, 3010 = success but a
  # reboot is required, 1641 = success and a reboot has been initiated.
  silentArgs     = '/qn /norestart'
  validExitCodes = @(0, 3010, 1641)
}

Install-ChocolateyPackage @packageArgs
