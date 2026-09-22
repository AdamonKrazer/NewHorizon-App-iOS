param([Parameter(Mandatory=$true)][string]$AndroidRoot)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$workspace = Split-Path -Parent $PSScriptRoot
$sourceAssets = Join-Path $AndroidRoot 'app_pojavlauncher/src/main/assets'
$destination = Join-Path $workspace 'Natives/resources/newhorizon/bundled-mods'
$eventPath = 'com/newhorizon/sessionflow/NewHorizonSessionFlow$ClientEvents.class'

function Read-ZipEntry([string]$archive, [string]$name) {
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        $entry = $zip.GetEntry($name)
        if ($null -eq $entry) { throw "Missing $name in $archive" }
        $input = $entry.Open()
        $buffer = [IO.MemoryStream]::new()
        try { $input.CopyTo($buffer); return ,$buffer.ToArray() }
        finally { $input.Dispose(); $buffer.Dispose() }
    } finally { $zip.Dispose() }
}

# Preserve the compiled iOS superflat creator and matching iOS mod metadata.
# The remaining Android session classes retain their base/low-memory variants.
foreach ($variant in @('base', 'low-memory')) {
    $target = Join-Path $destination "newhorizon_session_flow-1.0.3-$variant.jar"
    $events = Read-ZipEntry $target $eventPath
    $metadata = Read-ZipEntry $target 'META-INF/mods.toml'
    $relative = if ($variant -eq 'base') { 'memory-profiles/base' } else { 'bundled-mods' }
    $source = Join-Path $sourceAssets "$relative/newhorizon_session_flow-1.0.1.jar"
    $temporary = "$target.importing"
    Copy-Item -LiteralPath $source -Destination $temporary
    $zip = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($replacement in @(@{Name=$eventPath; Bytes=$events}, @{Name='META-INF/mods.toml'; Bytes=$metadata})) {
            $zip.GetEntry($replacement.Name).Delete()
            $entry = $zip.CreateEntry($replacement.Name)
            $output = $entry.Open()
            try { $output.Write($replacement.Bytes, 0, $replacement.Bytes.Length) }
            finally { $output.Dispose() }
        }
    } finally { $zip.Dispose() }
    Move-Item -LiteralPath $temporary -Destination $target -Force
    Write-Output "Updated $variant session classes; retained iOS superflat creator"
}
Copy-Item -LiteralPath (Join-Path $sourceAssets 'bundled-mods/newhorizon_lowmemory_engine-0.1.0.jar') -Destination $destination -Force
