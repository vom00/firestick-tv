# Puts your own photos on TV Home. While any are on the TV they replace the bundled nature photos;
# TV Home shuffles them and picks up changes at the next wallpaper change (or Settings > Next Wallpaper).
# Usage: .\add-wallpapers.ps1 -Folder "C:\Users\you\Pictures\TV"      add the JPG/PNG/WebP photos in a folder
#        .\add-wallpapers.ps1 -Folder "..." -Replace                 replace the ones already on the TV
#        .\add-wallpapers.ps1 -List                                  show what's on the TV
#        .\add-wallpapers.ps1 -Reset                                 remove them all (back to nature photos)
param(
    [string]$Folder,
    [switch]$Replace,
    [switch]$List,
    [switch]$Reset,
    [string]$Device = "192.168.0.148:5555"
)
. (Join-Path $PSScriptRoot "ftv-common.ps1")

$dest = "/sdcard/Android/data/local.tvhome/files/wallpapers"
Connect-FireTV

if ($Reset) {
    Sh "rm -f $dest/*" | Out-Null
    "Removed your photos; TV Home is back to the bundled nature photos."
    return
}
if ($List) {
    Sh "ls -l $dest 2>/dev/null"
    return
}
if (-not $Folder) { throw "Use -Folder <path>, -List or -Reset." }

$photos = Get-ChildItem -LiteralPath $Folder -File | Where-Object { $_.Extension -in '.jpg', '.jpeg', '.png', '.webp' }
if (-not $photos) { throw "No .jpg, .png or .webp photos in $Folder" }

Sh "mkdir -p $dest" | Out-Null
if ($Replace) { Sh "rm -f $dest/*" | Out-Null }
foreach ($p in $photos) {
    # Simple names on the TV side avoid quoting trouble with spaces and accents.
    $name = ($p.BaseName -replace '[^A-Za-z0-9._-]', '_') + $p.Extension.ToLower()
    & $adb -s $Device push $p.FullName "$dest/$name" | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Warning "Couldn't copy $($p.Name)" } else { "copied $($p.Name)" }
}
"{0} photo(s) on the TV. They appear at the next wallpaper change, or pick Settings > Next Wallpaper." -f ((Sh "ls $dest" | Measure-Object -Line).Lines)
