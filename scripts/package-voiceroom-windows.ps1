param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$destination = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$project = Join-Path $repoRoot 'desktop/VoiceRoomManager.Windows/VoiceRoomManager.Windows.csproj'
[xml]$projectXml = Get-Content -LiteralPath $project -Raw
$version = [string]$projectXml.Project.PropertyGroup.Version
if ([string]::IsNullOrWhiteSpace($version)) { throw 'Windows project Version is missing' }
$packageVersion = $version -replace '-rc\.', '-rc'
$packageName = "VoiceRoomManager-Windows-v$packageVersion-x64"
$publishDir = Join-Path $destination $packageName
& dotnet publish $project -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -p:DebugSymbols=false -o $publishDir
if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed' }
Copy-Item -LiteralPath (Join-Path $repoRoot 'desktop/VoiceRoomManager.Windows/README.md') -Destination (Join-Path $publishDir 'README.md')
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/VOICEROOM_WINDOWS_RC_CHECKLIST.md') -Destination (Join-Path $publishDir 'CHECKLIST.md')
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/VOICEROOM_WINDOWS_RC_AUDIT.md') -Destination (Join-Path $publishDir 'AUDIT.md')
$rc11Notes = Join-Path $repoRoot 'docs/VOICEROOM_WINDOWS_RC11_NOTES.md'
if (Test-Path -LiteralPath $rc11Notes) { Copy-Item -LiteralPath $rc11Notes -Destination (Join-Path $publishDir 'RC11_NOTES.md') }
$readmePath = Join-Path $publishDir 'README.md'
(Get-Content -LiteralPath $readmePath -Raw).Replace('../../docs/VOICEROOM_WINDOWS_RC_CHECKLIST.md', 'CHECKLIST.md').Replace('../../docs/VOICEROOM_WINDOWS_RC_AUDIT.md', 'AUDIT.md') | Set-Content -LiteralPath $readmePath -Encoding utf8
$auditPath = Join-Path $publishDir 'AUDIT.md'
(Get-Content -LiteralPath $auditPath -Raw).Replace('VOICEROOM_WINDOWS_RC_CHECKLIST.md', 'CHECKLIST.md') | Set-Content -LiteralPath $auditPath -Encoding utf8
$exe = Join-Path $publishDir 'VoiceRoomManager.Windows.exe'
$exeHash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
"$exeHash  VoiceRoomManager.Windows.exe" | Set-Content (Join-Path $publishDir 'SHA256.txt') -Encoding utf8
$zipName = "$packageName.zip"
$zip = Join-Path $destination $zipName
Compress-Archive -LiteralPath $publishDir -DestinationPath $zip -Force
$zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
@("$exeHash  $packageName/VoiceRoomManager.Windows.exe", "$zipHash  $zipName") | Set-Content (Join-Path $destination 'SHA256.txt') -Encoding utf8
