# Puts the Fire TV back the way the original backup found it:
#  - re-enables every package that is disabled now but wasn't in the backup
#  - restores the settings these scripts change (animation speed, usage data, accessibility)
#  - removes TV Home (unless -KeepLauncher), which also takes its tile off Amazon's favourites row
# Usage: .\restore-firetv.ps1 [-DryRun] [-KeepLauncher] [-NoReboot] [-Backup 20261019-210330] [-Device ip:5555]
#   -DryRun   show what would change without touching the TV
param(
    [string]$Backup,
    [switch]$DryRun,
    [switch]$KeepLauncher,
    [switch]$NoReboot,
    [string]$Device = "192.168.0.148:5555"
)
. (Join-Path $PSScriptRoot "ftv-common.ps1")

$LauncherPackage = "local.tvhome"
$ManagedSettings = @(
    "global/window_animation_scale"
    "global/transition_animation_scale"
    "global/animator_duration_scale"
    "secure/USAGE_METRICS_UPLOAD_ENABLED"
    "secure/usage_metrics_marketing_enabled"
    "secure/settingsApp:deviceUsageData:value"
    "secure/settingsApp:appUsageData:value"
    "secure/enabled_accessibility_services"
    "secure/accessibility_enabled"
)

if (-not $Backup) { $Backup = (Get-Content (Join-Path $PSScriptRoot "backups\ORIGINAL")).Trim() }
$dir = Join-Path $PSScriptRoot "backups\$Backup"
if (-not (Test-Path "$dir\packages-disabled.txt")) { throw "Backup not found: $dir" }
Write-Host "Restoring from backup $Backup$(if ($DryRun) { ' (dry run: nothing will change)' })"
Connect-FireTV

function Change([string]$what, [string]$cmd) {
    if ($DryRun) { "{0,-45} would {1}" -f $what, $cmd; return }
    $out = (Sh "$cmd 2>&1") -join ' '
    "{0,-45} {1}" -f $what, $(if ($out -match 'Exception|Error|Unknown') { "FAILED: $out" } else { "done" })
}

# 1. Packages
"--- Packages ---"
$wasDisabled = (Get-Content "$dir\packages-disabled.txt") -replace '^package:', '' | Where-Object { $_ }
$nowDisabled = (Sh "pm list packages -d") -replace '^package:', '' | Where-Object { $_ }
$toEnable = $nowDisabled | Where-Object { $wasDisabled -notcontains $_ }
foreach ($p in $toEnable) { Change $p "pm enable $p" }
if (-not $toEnable) { "No packages to re-enable." }

# 2. Settings
"--- Settings ---"
function Get-BackupValue([string]$ns, [string]$key) {
    foreach ($line in Get-Content "$dir\settings-$ns.txt") {
        if ($line.StartsWith("$key=")) { return $line.Substring($key.Length + 1) }
    }
    return $null
}
foreach ($s in $ManagedSettings) {
    $ns, $key = $s -split '/', 2
    $old = Get-BackupValue $ns $key
    $now = "$(Sh "settings get $ns '$key'")".Trim()
    if ($null -eq $old) {
        if ($now -ne "null") { Change $s "settings delete $ns '$key'" }
    } elseif ($now -ne $old) {
        Change $s "settings put $ns '$key' '$old'"
    }
}

# 3. TV Home
"--- TV Home ---"
if ($KeepLauncher) { "Keeping TV Home installed." }
elseif ((Sh "pm list packages $LauncherPackage") -match $LauncherPackage) { Change $LauncherPackage "pm uninstall $LauncherPackage" }
else { "TV Home isn't installed." }

if ($DryRun) { return }
Sh "am start -a android.intent.action.MAIN -c android.intent.category.HOME" | Out-Null
if (-not $NoReboot) {
    Write-Host "Rebooting..."
    $secs = Restart-FireTV
    Write-Host "Booted in $secs s"
}
$shot = Save-Shot "restore-$(Get-Date -Format yyyyMMdd-HHmmss)"
Write-Host "Done. Screenshot: $shot"
