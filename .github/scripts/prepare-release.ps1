[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArtifactPath,
    [Parameter(Mandatory)]
    [string]$OutputDirectory,
    [string]$ExpectedTag = '',
    [ValidateSet('release', 'debug')]
    [string]$Channel = 'release',
    [string]$CommitSha = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$artifact = Get-Item -LiteralPath $ArtifactPath
$archive = [IO.Compression.ZipFile]::OpenRead($artifact.FullName)
try {
    $entry = $archive.GetEntry('manifest.json')
    if ($null -eq $entry) { throw '插件包缺少 manifest.json' }
    $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
}
finally {
    $archive.Dispose()
}

$plugin = $manifest.plugin
if ($manifest.formatVersion -ne '2') { throw 'Release 插件必须使用 formatVersion 2' }
if ($Channel -eq 'release') {
    if ($ExpectedTag -ne "v$($plugin.version)") {
        throw "标签 $ExpectedTag 与插件版本 $($plugin.version) 不一致"
    }
    if (-not (Select-String -LiteralPath (Join-Path $repositoryRoot 'CHANGELOG.md') -SimpleMatch $plugin.version -Quiet)) {
        throw "CHANGELOG.md 缺少 $($plugin.version)"
    }
}
elseif ($CommitSha -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'Debug 发布必须提供完整的 commit SHA'
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$targetArtifact = Join-Path $OutputDirectory $artifact.Name
Copy-Item -LiteralPath $artifact.FullName -Destination $targetArtifact -Force
$targetFile = Get-Item -LiteralPath $targetArtifact
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetArtifact).Hash.ToLowerInvariant()
$repositoryUrl = if ($env:GITHUB_REPOSITORY) {
    "https://github.com/$($env:GITHUB_REPOSITORY)"
} else {
    'https://github.com/android-tool-suite/plugin-accessibility-grant'
}

$metadata = [ordered]@{
    schemaVersion = 1
    type = 'plugin'
    channel = $Channel
    id = $plugin.id
    title = $plugin.title
    description = $plugin.description
    author = $plugin.author
    repositoryUrl = $repositoryUrl
    versionName = $plugin.version
    versionCode = [int]$plugin.versionCode
    minHostVersionCode = [int]$plugin.minHostVersionCode
    sdkVersion = $plugin.sdkVersion
    dependencies = @($manifest.dependencies)
    artifactName = $targetFile.Name
}
if ($Channel -eq 'debug') {
    $metadata.commitSha = $CommitSha.ToLowerInvariant()
}
$metadata | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'release-metadata.json') -Encoding utf8
"$hash  $($targetFile.Name)" |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS.txt') -Encoding ascii
