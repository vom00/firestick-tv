# Installs TVHome.apk on the Fire TV and turns on its Home-button service.
# restore-firetv.ps1 undoes all of this.
# Usage: .\install-launcher.ps1 [-Device ip:5555]
param([string]$Device = "192.168.0.148:5555")
. (Join-Path $PSScriptRoot "ftv-common.ps1")

$pkg = "local.tvhome"
$service = "$pkg/$pkg.HomeKeyService"

Connect-FireTV
& $adb -s $Device install -r (Join-Path $PSScriptRoot "TVHome.apk")
if ($LASTEXITCODE -ne 0) { throw "install failed" }

# Add our service to any accessibility services already on, without removing them.
$enabled = "$(Sh 'settings get secure enabled_accessibility_services')".Trim()
$list = @($enabled -split ':' | Where-Object { $_ -and $_ -ne 'null' })
if ($list -notcontains $service) { $list += $service }
Sh "settings put secure enabled_accessibility_services '$($list -join ':')'" | Out-Null
Sh "settings put secure accessibility_enabled 1" | Out-Null
"Accessibility services: $(Sh 'settings get secure enabled_accessibility_services')"

Sh "am start -n $pkg/.HomeActivity" | Out-Null
Start-Sleep -Seconds 4
Save-Shot "launcher-installed"
