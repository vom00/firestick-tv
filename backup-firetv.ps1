# Snapshot the Fire TV's package and settings state so every change can be undone.
# Usage: .\backup-firetv.ps1 [-Device 192.168.0.148:5555]
param([string]$Device = "192.168.0.148:5555")

$ErrorActionPreference = "Stop"
$adb = Join-Path $PSScriptRoot "tools\platform-tools\adb.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $PSScriptRoot "backups\$stamp"
New-Item -ItemType Directory -Force $out | Out-Null

& $adb connect $Device | Out-Null
function Save($name, $cmd) {
    $text = & $adb -s $Device shell $cmd
    if ($LASTEXITCODE -ne 0) { throw "adb failed for $name" }
    Set-Content -Path (Join-Path $out $name) -Value $text -Encoding utf8
}

Save "packages-all.txt"      "pm list packages -f -U"
Save "packages-enabled.txt"  "pm list packages -e"
Save "packages-disabled.txt" "pm list packages -d"
Save "packages-system.txt"   "pm list packages -s"
Save "packages-3rdparty.txt" "pm list packages -3"
Save "settings-global.txt"   "settings list global"
Save "settings-secure.txt"   "settings list secure"
Save "settings-system.txt"   "settings list system"
Save "home-activity.txt"     "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN"
Save "getprop.txt"           "getprop"
Save "meminfo.txt"           "dumpsys meminfo"
Save "package-dump.txt"      "dumpsys package packages"

& $adb -s $Device exec-out screencap -p > $null
cmd /c "`"$adb`" -s $Device exec-out screencap -p > `"$out\screen.png`""

Set-Content (Join-Path $PSScriptRoot "backups\LATEST") $stamp
# The first backup is the untouched original; restore-firetv.ps1 goes back to it by default.
$original = Join-Path $PSScriptRoot "backups\ORIGINAL"
if (-not (Test-Path $original)) { Set-Content $original $stamp }
Write-Host "Backup saved to $out"
Get-ChildItem $out | ForEach-Object { "{0,-24} {1,10:N0} bytes" -f $_.Name, $_.Length }
