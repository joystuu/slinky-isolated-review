$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'Run this script only inside the proposed GitHub-hosted Windows job.'
}
$sourceRoot = Join-Path $PSScriptRoot '..\Slinky-source'
$sourceRoot = (Resolve-Path -LiteralPath $sourceRoot).Path
if (Test-Path -LiteralPath (Join-Path $sourceRoot 'build')) { throw 'Generated build files must not be supplied.' }
$entries = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\source-input-manifest.json') -Raw | ConvertFrom-Json
foreach ($entry in $entries) {
    $path = [IO.Path]::GetFullPath((Join-Path $sourceRoot $entry.path))
    if (-not $path.StartsWith($sourceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Manifest path escapes source root.' }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Source mismatch: $($entry.path)" }
}
if (@(Get-ChildItem -LiteralPath $sourceRoot -File -Recurse).Count -ne @($entries).Count) { throw 'Unexpected source file count.' }
$dependencies = Get-Content -LiteralPath (Join-Path $sourceRoot 'deps\download-manifest.json') -Raw | ConvertFrom-Json
foreach ($dependency in $dependencies | Where-Object { $_.path -like 'tooling/*' }) {
    $cached = Join-Path $PSScriptRoot ('vendor/' + [IO.Path]::GetFileName($dependency.path))
    if ((Get-FileHash -LiteralPath $cached -Algorithm SHA256).Hash -ne $dependency.sha256) { throw 'Cached ASM hash mismatch.' }
    $destination = Join-Path $sourceRoot ('deps/' + $dependency.path)
    New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
    Copy-Item -LiteralPath $cached -Destination $destination
}
$jdk = $env:JAVA_HOME_17_X64
if (-not $jdk -or -not (Test-Path -LiteralPath (Join-Path $jdk 'bin\javac.exe'))) { throw 'Runner JDK 17 unavailable.' }
Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version | ConvertTo-Json -Compress
Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion | ConvertTo-Json -Compress
& (Join-Path $jdk 'bin\java.exe') -version
if ($LASTEXITCODE) { throw 'JDK check failed.' }
& (Join-Path $sourceRoot 'build.ps1') -JavaHome $jdk -RestoreDependencies -Generator 'Visual Studio 17 2022'
if ($LASTEXITCODE) { throw 'Build failed.' }
foreach ($relative in @('build\payload-101.jar','build\payload-102.jar','build\payload-103.jar','build\native\Release\SlinkyNative.dll','build\native\Release\Slinky.exe')) {
    $path = Join-Path $sourceRoot $relative
    if (-not (Test-Path -LiteralPath $path)) { throw "Build output missing: $relative" }
    [PSCustomObject]@{File=$relative; Bytes=(Get-Item -LiteralPath $path).Length; SHA256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash} | ConvertTo-Json -Compress
}
Write-Output 'SOURCE BUILD COMPLETED; CLIENT EXECUTION NOT PERFORMED.'
