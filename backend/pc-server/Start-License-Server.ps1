param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$serverDataDirectory = Join-Path $env:LOCALAPPDATA 'KakaoMacroLicenseServer'
$serverNodePath = Join-Path $PSScriptRoot 'runtime\node.exe'
$serverScriptPath = Join-Path $PSScriptRoot 'server.mjs'
$serverManifest = Get-Content -LiteralPath (Join-Path $serverDataDirectory 'runtime-manifest.json') -Raw | ConvertFrom-Json
$serverStatusUrl = 'http://127.0.0.1:' + $serverManifest.status_port
$serverAlreadyRunning = $false
try {
    $serverState = Invoke-RestMethod -Uri ($serverStatusUrl + '/status') -TimeoutSec 2
    $serverAlreadyRunning = $serverState.application -eq 'KakaoMacroLicenseServer'
} catch {}
if (-not $serverAlreadyRunning) {
    $serverArguments = @(('"' + $serverScriptPath + '"'), ('"' + $serverDataDirectory + '"'))
    Start-Process -FilePath $serverNodePath -ArgumentList $serverArguments -WindowStyle Hidden -RedirectStandardOutput (Join-Path $serverDataDirectory 'runtime.log') -RedirectStandardError (Join-Path $serverDataDirectory 'runtime-error.log') | Out-Null
}
if (-not $NoBrowser) { Start-Process $serverStatusUrl }
