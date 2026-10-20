# Disables (never uninstalls) Fire TV ads, telemetry and unused Amazon apps, one batch at a time.
# Everything here is undone by restore-firetv.ps1. Safe to re-run: Fire OS turns a few of these
# back on now and then (Alexa's voice service at every boot), so "-Batch all" re-applies everything.
# Usage: .\debloat-firetv.ps1 -Batch ads|unused|alexa|updates|tweaks|all [-Device ip:5555]
param(
    [Parameter(Mandatory)][ValidateSet("ads", "unused", "alexa", "updates", "tweaks", "all")][string]$Batch,
    [string]$Device = "192.168.0.148:5555"
)
. (Join-Path $PSScriptRoot "ftv-common.ps1")

$Batches = [ordered]@{
    # Ad and tracking services. All small background stubs; nothing you open.
    ads = @(
        "com.amazon.tv.acr"                    # automatic content recognition (reports what you watch)
        "com.amazon.hybridadidservice"         # advertising ID service
        "com.amazon.ftvads.deeplinking"        # ad deep-link handler
        "com.amazon.sneakpeek"                 # sponsored previews
        "com.amazon.shoptv.client"             # "Shop the TV"
        "com.amazon.shoptv.firetv.client"      # "Shop the TV" launcher stub
        "com.amazon.kso.blackbird"             # special-offers (ads) client
        "com.amazon.tv.fw.metrics"             # firmware metrics upload
        "com.amazon.wirelessmetrics.service"   # Wi-Fi metrics upload
        "com.amazon.device.crashmanager"       # crash report upload
        "com.amazon.device.logmanager"         # log upload
        "com.amazon.tv.csapp"                  # customer-service data collection
        "com.amazon.perfc"                     # native performance collection
        "com.amazon.perfcollection"            # performance collection
        "com.amazon.neo.minerva.logcat"        # logcat metrics upload
    )
    # Amazon apps you said you don't use.
    unused = @(
        "com.amazon.bueller.photos"            # Amazon Photos
        "com.amazon.bueller.music"             # Amazon Music
        "com.amazon.audiohome"                 # Music & More
        "com.amazon.spiderpork"                # Amazon Luna
        "com.amazon.gamehub"                   # games hub
        "com.amazon.tahoe"                     # Amazon Kids
        "com.amazon.hedwig"                    # News
        # Silk (com.amazon.cloud9) stays on: TV Home's Search uses it.
    )
    # Alexa voice. Keeps smart-home/Matter, casting and Prime Video.
    alexa = @(
        "com.amazon.vizzini"                   # Alexa voice
        "com.amazon.alexamediaplayer.runtime.ftv" # Alexa media player
        "com.amazon.tv.alexaalerts"            # Alexa alerts
        "com.amazon.tv.alexanotifications"     # Alexa notifications
        "com.amazon.whad"                      # Whole Home Audio (Alexa multi-room music)
        "com.amazon.whasettings"               # Whole Home Audio settings
        "com.amazon.wha.mediabrowserservice"   # Whole Home Audio media browser
    )
    # Automatic Fire OS updates.
    updates = @(
        "com.amazon.device.software.ota"
        "com.amazon.device.software.ota.override"
        "com.amazon.tv.forcedotaupdater.v2"
        "com.amazon.tv.easyupgrade"
    )
}

# Settings changed by the "tweaks" batch. restore-firetv.ps1 puts back the backed-up values.
$Tweaks = @(
    @{ ns = "global"; key = "window_animation_scale";       value = "0.5" }
    @{ ns = "global"; key = "transition_animation_scale";   value = "0.5" }
    @{ ns = "global"; key = "animator_duration_scale";      value = "0.5" }
    @{ ns = "secure"; key = "USAGE_METRICS_UPLOAD_ENABLED"; value = "0" }
    @{ ns = "secure"; key = "usage_metrics_marketing_enabled"; value = "0" }
)

Connect-FireTV
$log = Join-Path $PSScriptRoot "debloat-log.csv"
if (-not (Test-Path $log)) { "time,batch,kind,target,result" | Set-Content $log -Encoding utf8 }

$installed = (Sh "pm list packages") -replace '^package:', ''
$disabled = (Sh "pm list packages -d") -replace '^package:', ''
$run = if ($Batch -eq "all") { @($Batches.Keys) + "tweaks" } else { @($Batch) }

foreach ($b in $run) {
    if ($b -eq "tweaks") {
        foreach ($t in $Tweaks) {
            $before = (Sh "settings get $($t.ns) $($t.key)").Trim()
            Sh "settings put $($t.ns) $($t.key) $($t.value)" | Out-Null
            $after = (Sh "settings get $($t.ns) $($t.key)").Trim()
            "{0},{1},setting,{2}/{3},{4} -> {5}" -f (Get-Date -Format s), $b, $t.ns, $t.key, $before, $after | Add-Content $log
            "{0,-45} {1} -> {2}" -f "$($t.ns)/$($t.key)", $before, $after
        }
        continue
    }
    foreach ($p in $Batches[$b]) {
        if ($installed -notcontains $p) { $result = "not installed" }
        elseif ($disabled -contains $p) { "{0,-45} {1}" -f $p, "already disabled"; continue }
        else {
            $out = (Sh "pm disable-user --user 0 $p 2>&1") -join ' '
            $result = if ($out -match 'new state: disabled-user') { "disabled" }
                      elseif ($out -match 'protected package') { "skipped (Amazon-protected)" }
                      else { "FAILED: " + ($out -split '\s+at\s')[0] }
        }
        "{0},{1},package,{2},{3}" -f (Get-Date -Format s), $b, $p, ($result -replace ',', ';') | Add-Content $log
        "{0,-45} {1}" -f $p, $result
    }
}
