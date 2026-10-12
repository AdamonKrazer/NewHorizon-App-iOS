param([Parameter(Mandatory=$true)][string]$AndroidRoot)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$source = Join-Path $AndroidRoot 'nh_thin_client/src'
$destination = Join-Path $workspace 'ThinClient/src'
$adapted = @(
    'main/java/com/newhorizon/thinclient/ThinClientMain.java',
    'main/java/com/newhorizon/thinclient/ThinClientSelfTest.java',
    'main/java/com/newhorizon/thinclient/display/GeckoNativeBridge.java',
    'main/java/com/newhorizon/thinclient/render/LtwScene.java'
)
$checked = 0
foreach ($file in Get-ChildItem -LiteralPath $source -Recurse -File) {
    $relative = $file.FullName.Substring($source.Length + 1).Replace('\', '/')
    $target = Join-Path $destination $relative
    if (!(Test-Path -LiteralPath $target)) { throw "Missing imported file: $relative" }
    if ($adapted -contains $relative) { continue }
    if ((Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath $target).Hash) {
        throw "Unexpected difference in shared Android source/resource: $relative"
    }
    $checked++
}
Write-Output "PASS: $checked shared files match Android exactly; $($adapted.Count) declared iOS adaptations."
$readbacks = Get-ChildItem (Join-Path $destination 'main/java') -Recurse -Filter *.java |
    Select-String -Pattern 'glReadPixels\s*\('
if ($readbacks) { throw 'CPU pixel readback detected in the GPU-only iOS runtime.' }

function Assert-Import([string]$from, [string]$to) {
    if (!(Test-Path -LiteralPath $to) -or
        (Get-FileHash -LiteralPath $from).Hash -ne (Get-FileHash -LiteralPath $to).Hash) {
        throw "Imported dependency differs: $from -> $to"
    }
}
foreach ($name in @('InventoryLanguage','ThinItemTag','ThinContainerTag','MinePadDomainList','MinePadNavigationPolicy')) {
    Assert-Import (Join-Path $AndroidRoot "app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/gecko/$name.java") `
        (Join-Path $workspace "ThinClient/src/main/java/net/kdt/pojavlaunch/gecko/$name.java")
}
$assetRoot = Join-Path $AndroidRoot 'app_pojavlauncher/src/main/assets'
foreach ($folder in @('minepad','vanilla_item_icons','inventory_lang','wd_display')) {
    $from = Join-Path $assetRoot $folder
    $to = if ($folder -eq 'wd_display') { Join-Path $workspace 'Natives/resources/assets/wd_display' } `
          else { Join-Path $workspace "ThinClient/src/main/resources/$folder" }
    foreach ($file in Get-ChildItem -LiteralPath $from -Recurse -File) {
        Assert-Import $file.FullName (Join-Path $to $file.FullName.Substring($from.Length+1))
    }
}
foreach ($name in @('creative_inventory_1_20_1.tsv','creative_search_1_20_1.tsv')) {
    Assert-Import (Join-Path $assetRoot $name) (Join-Path $workspace "ThinClient/src/main/resources/assets/newhorizon/$name")
}
Assert-Import (Join-Path $assetRoot 'bundled-mods/webdisplays-2.0.2-1.20.1.jar') `
    (Join-Path $workspace 'Natives/resources/newhorizon/bundled-mods/webdisplays-2.0.2-1.20.1.jar')
Write-Output 'PASS: original UI helpers, browser policy, icons, languages, scripts, catalogs and WebDisplays match Android.'
