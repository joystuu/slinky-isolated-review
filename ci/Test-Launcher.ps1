$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'This test must run in the disposable GitHub-hosted Windows VM.'
}
if (@(Get-Process java,javaw -ErrorAction SilentlyContinue).Count) { throw 'Unexpected Java process: refuse ambiguous injection.' }
$lab = Join-Path $env:RUNNER_TEMP 'slinky-observation'
New-Item -ItemType Directory -Force -Path $lab | Out-Null
$archive = Join-Path $lab 'ProcessMonitor.zip'
Invoke-WebRequest 'https://download.sysinternals.com/files/ProcessMonitor.zip' -OutFile $archive
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $lab 'procmon')
$procmon = Join-Path $lab 'procmon/Procmon64.exe'
$signature = Get-AuthenticodeSignature -LiteralPath $procmon
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') { throw 'Invalid Microsoft Process Monitor signature.' }
Write-Output ('MONITOR ' + (Get-FileHash -LiteralPath $procmon -Algorithm SHA256).Hash)
$exe = (Resolve-Path (Join-Path $PSScriptRoot '../Slinky-source/build/native/Release/Slinky.exe')).Path
$pml = Join-Path $lab 'launcher.pml'
$csv = Join-Path $lab 'launcher.csv'
New-NetFirewallRule -DisplayName 'Slinky test launcher outbound block' -Direction Outbound -Action Block -Program $exe | Out-Null
$capture = Start-Process -FilePath $procmon -ArgumentList @('/AcceptEula','/Quiet','/Minimized','/BackingFile',('"'+$pml+'"')) -PassThru
Start-Sleep -Seconds 3
if ($capture.HasExited -and -not (Get-Process Procmon64 -ErrorAction SilentlyContinue)) { throw 'Process Monitor exited before the test.' }
$specimen = $null
try {
    if (-not (Test-Path -LiteralPath $pml)) { throw 'Capture backing file missing; refuse unmonitored execution.' }
    $specimen = Start-Process -FilePath $exe -ArgumentList '--no-pause' -PassThru
    $finished = $specimen.WaitForExit(20000)
    if (-not $finished) { Stop-Process -Id $specimen.Id -Force }
    Start-Sleep -Seconds 2
    [PSCustomObject]@{Stage='launcher-no-target';PID=$specimen.Id;ExitedWithin20Seconds=$finished;ExitCode=if($finished){$specimen.ExitCode}else{$null};InjectedPayloadTested=$false} | ConvertTo-Json -Compress
} finally {
    Start-Process -FilePath $procmon -ArgumentList '/Terminate','/Quiet' -Wait | Out-Null
}
Start-Process -FilePath $procmon -ArgumentList @('/AcceptEula','/Quiet','/OpenLog',('"'+$pml+'"'),'/SaveAs',('"'+$csv+'"')) -Wait | Out-Null
& (Join-Path $PSScriptRoot 'Export-Trace.ps1') -Csv $csv -RootPids @([string]$specimen.Id) -Stage 'launcher-no-target'
Write-Output 'BOUNDARY: launcher without a JVM only; no injected native/Java payload execution. Network blocked for the launcher. Not a safety clearance.'
