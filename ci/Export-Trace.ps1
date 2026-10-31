param([string]$Csv, [string[]]$RootPids, [string]$Stage)
$ErrorActionPreference = 'Stop'
Write-Output ('TRACE_STAGE ' + $Stage)
if (-not (Test-Path -LiteralPath $csv)) { throw 'Monitor CSV export failed.' }
$events = @(Import-Csv -LiteralPath $csv)
$ids = [Collections.Generic.HashSet[string]]::new()
foreach ($rootId in $RootPids) { [void]$ids.Add($rootId) }
do {
    $added = $false
    foreach ($event in $events) {
        if ($ids.Contains($event.PID) -and $event.Operation -eq 'Process Create' -and $event.Detail -match '(?:^|, )PID: (\d+)') {
            if ($ids.Add($Matches[1])) { $added = $true }
        }
    }
} while ($added)
$selected = @($events | Where-Object { $ids.Contains($_.PID) })
foreach ($event in $selected) {
    if ($event.Operation -eq 'Process Start') {
        $event.Detail = $event.Detail -replace '(?s)Environment:.*$', 'Environment: [omitted from published trace]'
    }
}
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
