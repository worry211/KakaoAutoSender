$ErrorActionPreference = 'Stop'
$serverDataDirectory = Join-Path $env:LOCALAPPDATA 'KakaoMacroLicenseServer'
$serverNodePath = Join-Path $PSScriptRoot 'runtime\node.exe'
& $serverNodePath (Join-Path $PSScriptRoot 'control.mjs') stop $serverDataDirectory
if ($LASTEXITCODE -ne 0) { throw 'Server owner control failed' }
