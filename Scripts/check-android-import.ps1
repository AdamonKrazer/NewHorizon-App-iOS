param([Parameter(Mandatory=$true)][string]$AndroidRoot)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$source = Join-Path $AndroidRoot 'nh_thin_client/src'
$destination = Join-Path $workspace 'ThinClient/src'
$adapted = @(
    'main/java/com/newhorizon/thinclient/ThinClientMain.java',
    'main/java/com/newhorizon/thinclient/ThinClientSelfTest.java',
    'main/java/com/newhorizon/thinclient/display/GeckoNativeBridge.java'
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
Write-Output "PASS: $checked shared files match Android exactly; 3 declared iOS adaptations."
