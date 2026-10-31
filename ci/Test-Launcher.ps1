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
if (-not (Test-Path -LiteralPath $csv)) { throw 'Monitor CSV export failed.' }
$events = @(Import-Csv -LiteralPath $csv)
$ids = [Collections.Generic.HashSet[string]]::new()
[void]$ids.Add([string]$specimen.Id)
do {
    $added = $false
    foreach ($event in $events) {
        if ($ids.Contains($event.PID) -and $event.Operation -eq 'Process Create' -and $event.Detail -match '(?:^|, )PID: (\d+)') {
            if ($ids.Add($Matches[1])) { $added = $true }
        }
    }
} while ($added)
$selected = @($events | Where-Object { $ids.Contains($_.PID) })
if (-not @($selected | Where-Object { $_.Operation -eq 'Process Start' }).Count) { throw 'Missing specimen Process Start: capture coverage not established.' }
Write-Output ('TRACE_EVENT_COUNT ' + $selected.Count)
Write-Output ('TRACE_PROCESS_IDS ' + (@($ids) -join ','))
$selected | Group-Object Operation | Sort-Object Name | ForEach-Object { [PSCustomObject]@{Operation=$_.Name;Count=$_.Count} | ConvertTo-Json -Compress }
$selected | Where-Object { $_.Operation -match 'Write|Set|Delete|Rename|TCP|UDP|Process Create|Load Image' } | Select-Object -First 250 | ForEach-Object { $_ | ConvertTo-Json -Compress }
# Store the complete filtered trace in the ordinary job log without billed artifact storage.
$json = ConvertTo-Json -InputObject $selected -Depth 5 -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($json)
$memory = [IO.MemoryStream]::new()
$gzip = [IO.Compression.GZipStream]::new($memory,[IO.Compression.CompressionLevel]::Optimal,$true)
$gzip.Write($bytes,0,$bytes.Length)
$gzip.Dispose()
$encoded = [Convert]::ToBase64String($memory.ToArray())
Write-Output 'TRACE_GZIP_BASE64_BEGIN'
for ($offset=0; $offset -lt $encoded.Length; $offset+=3000) { Write-Output ('TRACE_CHUNK ' + $encoded.Substring($offset,[Math]::Min(3000,$encoded.Length-$offset))) }
Write-Output 'TRACE_GZIP_BASE64_END'
$memory.Dispose()
Write-Output 'BOUNDARY: launcher without a JVM only; no injected native/Java payload execution. Network blocked for the launcher. Not a safety clearance.'
