[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-pc-windows-msvc")]
    [string]$Target,
    [Parameter(Mandatory)]
    [string]$Binary,
    [Parameter(Mandatory)]
    [string]$Output
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $root "plugin/herdr-plugin.toml"
$binaryPath = [IO.Path]::GetFullPath($Binary)
$outputPath = [IO.Path]::GetFullPath($Output)
if (-not [IO.Path]::IsPathFullyQualified($Binary) -or -not (Test-Path -LiteralPath $binaryPath -PathType Leaf)) {
    throw "-Binary must be an existing absolute file path"
}

$targetPath = Join-Path $outputPath $Target
$binaryName = if ($Target -eq "x86_64-pc-windows-msvc") { "herdroid-bridge.exe" } else { "herdroid-bridge" }
$binaryRelativePath = "$Target/bin/$binaryName"
$manifestRelativePath = "$Target/herdr-plugin.toml"
New-Item -ItemType Directory -Force -Path (Join-Path $targetPath "bin") | Out-Null
Copy-Item -LiteralPath $manifest -Destination (Join-Path $targetPath "herdr-plugin.toml") -Force
Copy-Item -LiteralPath $binaryPath -Destination (Join-Path $targetPath "bin/$binaryName") -Force

$entries = @()
$catalogPath = Join-Path $outputPath "catalog.json"
if (Test-Path -LiteralPath $catalogPath -PathType Leaf) {
    $entries = @((Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json -AsHashtable).targets)
}
$entry = [ordered]@{
    target = $Target
    manifest = $manifestRelativePath
    binary = $binaryRelativePath
    sha256 = (Get-FileHash -LiteralPath (Join-Path $outputPath $binaryRelativePath) -Algorithm SHA256).Hash.ToLowerInvariant()
}
$entries = @($entries | Where-Object { $_.target -ne $Target }) + @($entry)
$entries = @($entries | Sort-Object { $_["target"] } | ForEach-Object {
    [ordered]@{
        binary = $_.binary
        manifest = $_.manifest
        sha256 = $_.sha256
        target = $_.target
    }
})
$catalog = [ordered]@{
    min_herdr_version = "0.8.0"
    plugin_id = "dev.herdroid.bridge"
    plugin_version = "0.1.0"
    protocol = 1
    targets = $entries
}
$json = ($catalog | ConvertTo-Json -Depth 3) -replace "`r`n", "`n"
[IO.File]::WriteAllText($catalogPath, $json, [Text.UTF8Encoding]::new($false))
