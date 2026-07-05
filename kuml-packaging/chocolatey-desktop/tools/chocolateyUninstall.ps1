$ErrorActionPreference = 'Stop'

# jpackage's `--name kuml-desktop` (set via Compose Desktop's `packageName`)
# becomes the MSI's ProductName / the "Display name" shown in Windows'
# "Apps & features", so we look it up by that name rather than hardcoding a
# ProductCode GUID — jpackage regenerates the ProductCode on every version
# bump, but keeps the display name and the upgradeUuid (UpgradeCode, set once
# in kuml-desktop/build.gradle.kts) stable. `-like` matching in
# Get-UninstallRegistryKey is case-insensitive, so this also covers any
# casing jpackage/WiX might apply.
#
# NOTE: this display-name assumption has not been verified against a real
# installed MSI yet (no Windows host was available to build/install it while
# writing this package) — verify on the first real `choco install
# kuml-desktop` + `choco uninstall kuml-desktop` round-trip and adjust the
# -SoftwareName filter here if the actual registry entry differs.
[array]$key = Get-UninstallRegistryKey -SoftwareName 'kuml-desktop*'

if ($key.Count -eq 1) {
  $key | ForEach-Object {
    $packageArgs = @{
      packageName    = $env:ChocolateyPackageName
      fileType       = 'msi'
      silentArgs     = "$($_.PSChildName) /qn /norestart"
      validExitCodes = @(0, 3010, 1605, 1614, 1641)
    }
    if ($_.UninstallString -match '^msiexec') {
      Uninstall-ChocolateyPackage @packageArgs
    } else {
      Write-Warning "Unexpected UninstallString format for kuml-desktop: $($_.UninstallString)"
    }
  }
} elseif ($key.Count -eq 0) {
  Write-Warning "$env:ChocolateyPackageName has already been uninstalled by other means."
} elseif ($key.Count -gt 1) {
  Write-Warning "$($key.Count) matches found for kuml-desktop's uninstall registry key - expected exactly one. Uninstall manually via Windows Apps & features."
}
