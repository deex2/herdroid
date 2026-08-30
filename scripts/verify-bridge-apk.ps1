[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Apk
)

$ErrorActionPreference = "Stop"

function Get-Sha256([IO.Stream]$Stream) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        [BitConverter]::ToString($algorithm.ComputeHash($Stream)).Replace("-", "").ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

$apkPath = [IO.Path]::GetFullPath($Apk)
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
    throw "APK does not exist: $apkPath"
}

$targets = [ordered]@{
    "aarch64-unknown-linux-gnu" = "herdroid-bridge"
    "x86_64-unknown-linux-gnu" = "herdroid-bridge"
    "x86_64-pc-windows-msvc" = "herdroid-bridge.exe"
}
$expected = @("assets/bridge/catalog.json")
foreach ($target in $targets.Keys) {
    $expected += "assets/bridge/$target/herdr-plugin.toml"
    $expected += "assets/bridge/$target/bin/$($targets[$target])"
}
$noticePath = "assets/THIRD_PARTY_NOTICES.md"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $actual = @($zip.Entries | Where-Object {
        $_.FullName.StartsWith("assets/bridge/") -and -not $_.FullName.EndsWith("/")
    } | ForEach-Object FullName)
    $missing = @($expected | Where-Object { $_ -notin $actual })
    $extra = @($actual | Where-Object { $_ -notin $expected })
    if ($actual.Count -ne $expected.Count -or $missing.Count -or $extra.Count) {
        throw "Bridge assets must be the exact seven files; missing=[$($missing -join ', ')]; extra=[$($extra -join ', ')]"
    }
    if ($null -eq $zip.GetEntry($noticePath)) {
        throw "APK must contain $noticePath"
    }
    $noticeStream = $zip.GetEntry($noticePath).Open()
    try {
        $packagedNoticeHash = Get-Sha256 $noticeStream
    } finally {
        $noticeStream.Dispose()
    }
    $sourceNotice = Join-Path (Split-Path -Parent $PSScriptRoot) "THIRD_PARTY_NOTICES.md"
    $sourceNoticeStream = [IO.File]::OpenRead($sourceNotice)
    try {
        $sourceNoticeHash = Get-Sha256 $sourceNoticeStream
    } finally {
        $sourceNoticeStream.Dispose()
    }
    if ($packagedNoticeHash -cne $sourceNoticeHash) {
        throw "Packaged third-party notices do not match THIRD_PARTY_NOTICES.md"
    }

    $catalogEntry = $zip.GetEntry("assets/bridge/catalog.json")
    $reader = [IO.StreamReader]::new($catalogEntry.Open())
    try {
        $catalog = $reader.ReadToEnd() | ConvertFrom-Json
    } finally {
        $reader.Dispose()
    }
    $catalogTargets = @($catalog.targets)
    if ($catalogTargets.Count -ne $targets.Count -or @($catalogTargets.target | Sort-Object -Unique).Count -ne $targets.Count) {
        throw "Catalog must contain each of the three targets exactly once"
    }

    foreach ($target in $targets.Keys) {
        $item = @($catalogTargets | Where-Object target -eq $target)
        $binary = "$target/bin/$($targets[$target])"
        $manifest = "$target/herdr-plugin.toml"
        if ($item.Count -ne 1 -or $item[0].binary -cne $binary -or $item[0].manifest -cne $manifest) {
            throw "Catalog paths do not match the packaged assets for $target"
        }
        $stream = $zip.GetEntry("assets/bridge/$binary").Open()
        try {
            $hash = Get-Sha256 $stream
        } finally {
            $stream.Dispose()
        }
        if ($hash -cne $item[0].sha256) {
            throw "Catalog SHA-256 does not match the packaged binary for $target"
        }
    }
} finally {
    $zip.Dispose()
}

Write-Output "Verified exact bridge assets and hashes in $apkPath"
