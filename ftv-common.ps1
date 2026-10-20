# Shared helpers for the Fire TV scripts. Dot-source it: . .\ftv-common.ps1
if (-not $Device) { $Device = "192.168.0.148:5555" }
$adb = Join-Path $PSScriptRoot "tools\platform-tools\adb.exe"
$ShotDir = Join-Path $PSScriptRoot "screenshots"
New-Item -ItemType Directory -Force $ShotDir | Out-Null

function Sh([string]$cmd) { & $adb -s $Device shell $cmd }

function Connect-FireTV {
    & $adb connect $Device | Out-Null
    $state = (& $adb -s $Device get-state 2>$null)
    if ($state -ne "device") { throw "Fire TV at $Device is not reachable (state: $state). Wake it with the remote." }
    Sh "input keyevent KEYCODE_WAKEUP" | Out-Null   # also resets the 20-minute sleep timer
}

# Saves a PNG screenshot from the TV and returns its path.
function Save-Shot([string]$name) {
    $path = Join-Path $ShotDir "$name.png"
    cmd /c "`"$adb`" -s $Device exec-out screencap -p > `"$path`""
    $path
}

function Send-Key([string[]]$keys, [int]$delayMs = 600) {
    foreach ($k in $keys) { Sh "input keyevent $k" | Out-Null; Start-Sleep -Milliseconds $delayMs }
}

# Reboots and waits until Android reports boot complete and the home screen is up.
function Restart-FireTV([int]$timeoutSec = 240) {
    & $adb -s $Device reboot | Out-Null
    Start-Sleep -Seconds 20
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        & $adb connect $Device 2>$null | Out-Null
        $boot = (& $adb -s $Device shell getprop sys.boot_completed 2>$null)
        if ("$boot".Trim() -eq "1") {
            Start-Sleep -Seconds 15
            return [int]($timeoutSec - ($deadline - (Get-Date)).TotalSeconds)
        }
        Start-Sleep -Seconds 5
    }
    throw "Fire TV did not finish booting within $timeoutSec s"
}
