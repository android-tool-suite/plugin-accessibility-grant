[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('release', 'debug')]
    [string]$Channel,
    [Parameter(Mandatory)]
    [string]$Reference
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($env:GH_TOKEN)) {
    Write-Warning 'REGISTRY_DISPATCH_TOKEN 未配置；请手动运行 plugin-registry 部署工作流。'
    return
}
$payload = [ordered]@{
    event_type = 'component_published'
    client_payload = [ordered]@{
        channel = $Channel
        repository = $env:GITHUB_REPOSITORY
        commitSha = $env:GITHUB_SHA
        reference = $Reference
    }
} | ConvertTo-Json -Depth 5 -Compress
$payload | gh api --method POST repos/android-tool-suite/plugin-registry/dispatches --input -
if ($LASTEXITCODE -ne 0) { throw '无法通知 plugin-registry 更新索引' }
