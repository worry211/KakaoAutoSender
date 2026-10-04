param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$destination = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$publishDir = Join-Path $destination 'VoiceRoomManager-Windows-v0.4.0-rc1-x64'
& dotnet publish (Join-Path $repoRoot 'desktop/VoiceRoomManager.Windows/VoiceRoomManager.Windows.csproj') -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -p:DebugSymbols=false -o $publishDir
if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed' }
Copy-Item -LiteralPath (Join-Path $repoRoot 'desktop/VoiceRoomManager.Windows/README.md') -Destination (Join-Path $publishDir 'README.md')
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/VOICEROOM_WINDOWS_RC_CHECKLIST.md') -Destination (Join-Path $publishDir 'CHECKLIST.md')
$exe = Join-Path $publishDir 'VoiceRoomManager.Windows.exe'
$exeHash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
"$exeHash  VoiceRoomManager.Windows.exe" | Set-Content (Join-Path $publishDir 'SHA256.txt') -Encoding utf8
$zip = Join-Path $destination 'VoiceRoomManager-Windows-v0.4.0-rc1-x64.zip'
Compress-Archive -LiteralPath $publishDir -DestinationPath $zip -Force
$zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
@("$exeHash  VoiceRoomManager-Windows-v0.4.0-rc1-x64/VoiceRoomManager.Windows.exe", "$zipHash  VoiceRoomManager-Windows-v0.4.0-rc1-x64.zip") | Set-Content (Join-Path $destination 'SHA256.txt') -Encoding utf8
