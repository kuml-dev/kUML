$ErrorActionPreference = 'Stop'

# Install-ChocolateyZipPackage records every extracted file in
# <packageName>.zip.txt and removes them automatically on uninstall, including
# the auto-generated `kuml` shim. Nothing extra to clean up here — this script
# exists so `choco uninstall kuml` has an explicit, audited no-op hook.
Write-Host "kuml: bundled runtime and shim removed by Chocolatey."
