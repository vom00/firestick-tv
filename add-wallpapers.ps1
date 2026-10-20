# Puts your own photos on TV Home. They join the bundled nature photos in the shuffle; TV Home picks
# up changes at the next wallpaper change (or Settings > Next Wallpaper).
# Usage: .\add-wallpapers.ps1 -Folder "C:\Users\you\Pictures\TV"      add the JPG/PNG/WebP photos in a folder
#        .\add-wallpapers.ps1 -Folder "..." -Replace                 replace the ones already on the TV
#        .\add-wallpapers.ps1 -Nature Off                            show only your photos (-Nature On to undo)
#        .\add-wallpapers.ps1 -List                                  show what's on the TV
#        .\add-wallpapers.ps1 -Reset                                 remove them all (back to nature photos)
param(
    [string]$Folder,
    [switch]$Replace,
    [ValidateSet('On', 'Off')][string]$Nature,
    [switch]$List,
    [switch]$Reset,
    [string]$Device = "192.168.0.148:5555"
)
. (Join-Path $PSScriptRoot "ftv-common.ps1")

$dest = "/sdcard/Android/data/local.tvhome/files/wallpapers"
# TV Home hides the nature photos while this file is in the folder (and you have photos there).
$mineOnly = "$dest/.mine-only"
Connect-FireTV

if ($Reset) {
    Sh "rm -f $dest/* $mineOnly" | Out-Null
    "Removed your photos; TV Home is back to the bundled nature photos."
    return
}
if ($List) {
    Sh "ls -l $dest 2>/dev/null"
    if ((Sh "ls $mineOnly 2>/dev/null") -match 'mine-only') { "Nature photos: off" } else { "Nature photos: on" }
    return
}
if ($Nature) {
    Sh "mkdir -p $dest" | Out-Null
    if ($Nature -eq 'Off') {
        Sh "touch $mineOnly" | Out-Null
        "Nature photos off: only your own photos rotate (while there are any on the TV)."
    } else {
        Sh "rm -f $mineOnly" | Out-Null
        "Nature photos on: they rotate along with your own."
    }
    if (-not $Folder) { return }
}
if (-not $Folder) { throw "Use -Folder <path>, -Nature On|Off, -List or -Reset." }

$photos = Get-ChildItem -LiteralPath $Folder -File | Where-Object { $_.Extension -in '.jpg', '.jpeg', '.png', '.webp' }
if (-not $photos) { throw "No .jpg, .png or .webp photos in $Folder" }

Sh "mkdir -p $dest" | Out-Null
if ($Replace) { Sh "rm -f $dest/*" | Out-Null }
foreach ($p in $photos) {
    # Simple names on the TV side avoid quoting trouble with spaces and accents.
    $name = ($p.BaseName -replace '[^A-Za-z0-9._-]', '_') + $p.Extension.ToLower()
    # adb prints its progress line on stderr, which Windows PowerShell shows as an error.
    & $adb -s $Device push $p.FullName "$dest/$name" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Warning "Couldn't copy $($p.Name)" } else { "copied $($p.Name)" }
}
"{0} photo(s) on the TV. They appear at the next wallpaper change, or pick Settings > Next Wallpaper." -f ((Sh "ls $dest" | Measure-Object -Line).Lines)
