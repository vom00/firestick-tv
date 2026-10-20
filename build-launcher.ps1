# Builds launcher\ into TVHome.apk with the plain Android SDK tools (no Gradle).
# Usage: .\build-launcher.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$src = Join-Path $root "launcher"
$out = Join-Path $root "build"
$buildTools = Join-Path $root "tools\sdk\android-14"                  # build-tools 34
$androidJar = Join-Path $root "tools\sdk\android-11\android.jar"     # platform 30
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$keystore = Join-Path $src "tvhome.keystore"
$keytool = Get-Command keytool -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $keytool) {
    $keytool = Get-ChildItem "C:\Program Files\Java" -Recurse -Depth 3 -Filter keytool.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}

function Run($exe) {
    # Windows PowerShell treats any stderr from a native tool as an error; judge by exit code instead.
    $ErrorActionPreference = "Continue"
    & $exe @args 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^Note: ' }
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $exe -Leaf) failed ($LASTEXITCODE)" }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes" | Out-Null

# 1. Resources + manifest + assets
Run $aapt2 compile --dir "$src\res" -o "$out\res.zip"
$versionCode = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalMinutes
Run $aapt2 link -o "$out\unsigned.apk" -I $androidJar --manifest "$src\AndroidManifest.xml" `
    --min-sdk-version 22 --target-sdk-version 28 `
    --version-code $versionCode --version-name "1.0.$versionCode" "$out\res.zip"

# 2. Java -> classes -> dex
$sources = Get-ChildItem "$src\src" -Recurse -Filter *.java | ForEach-Object FullName
Run javac --release 17 -nowarn -encoding UTF-8 -cp $androidJar -d "$out\classes" @sources
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
Run $d8 --release --min-api 22 --lib $androidJar --output $out @classes

# 3. Add classes.dex and assets to the APK. (aapt2 -A on Windows writes "assets/dir\file" with a
#    backslash, which Android can't find, so assets go in here with forward slashes.)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\classes.dex", "classes.dex") | Out-Null
$assetRoot = (Resolve-Path "$src\assets").Path
foreach ($f in Get-ChildItem $assetRoot -Recurse -File) {
    $name = "assets/" + $f.FullName.Substring($assetRoot.Length + 1).Replace('\', '/')
    $level = if ($f.Extension -eq '.jpg') { 'NoCompression' } else { 'Optimal' }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $name, $level) | Out-Null
}
$zip.Dispose()

# 4. Align and sign (local key, kept so updates install over the top)
if (-not (Test-Path $keystore)) {
    Run $keytool -genkeypair -keystore $keystore -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass tvhome-local -keypass tvhome-local -dname "CN=TV Home, O=Local"
}
Run $zipalign -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
Run $apksigner sign --ks $keystore --ks-key-alias tvhome --ks-pass pass:tvhome-local --key-pass pass:tvhome-local `
    --out "$root\TVHome.apk" "$out\aligned.apk"
Run $apksigner verify "$root\TVHome.apk"
"Built TVHome.apk ({0:N0} KB, version {1})" -f ((Get-Item "$root\TVHome.apk").Length / 1KB), $versionCode
