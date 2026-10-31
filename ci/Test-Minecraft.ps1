$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') { throw 'Disposable Windows runner required.' }
if (@(Get-Process java,javaw -ErrorAction SilentlyContinue).Count) { throw 'Unexpected Java process.' }
& python (Join-Path $PSScriptRoot 'Prepare-Minecraft.py')
if ($LASTEXITCODE) { throw 'Minecraft provisioning failed.' }
$lab = Join-Path $env:RUNNER_TEMP 'slinky-observation'
$gameRoot = Join-Path $env:RUNNER_TEMP 'slinky-game'
$procmon = Join-Path $lab 'procmon/Procmon64.exe'
if ((Get-AuthenticodeSignature -LiteralPath $procmon).Status -ne 'Valid') { throw 'Monitor signature invalid.' }
$exe = (Resolve-Path (Join-Path $PSScriptRoot '../Slinky-source/build/native/Release/Slinky.exe')).Path
$java = Join-Path $env:JAVA_HOME_8_X64 'bin/javaw.exe'
if (-not (Test-Path -LiteralPath $java)) { throw 'JDK 8 x64 unavailable.' }
# Third-party Mesa Windows distribution, pinned to the publisher's release digest.
# This changes only the disposable VM's JDK directory; no system-wide driver install.
$mesaArchive = Join-Path $gameRoot 'mesa.7z'
Invoke-WebRequest 'https://github.com/pal1000/mesa-dist-win/releases/download/26.2.5/mesa3d-26.2.5-release-msvc.7z' -OutFile $mesaArchive
if ((Get-FileHash -LiteralPath $mesaArchive -Algorithm SHA256).Hash -ne '997123ef4ce8896204ab912d5b54f19372c487ff9835bcefafa2fca23b8953c2') { throw 'Mesa release hash mismatch.' }
$mesa = Join-Path $gameRoot 'mesa'
& 7z x $mesaArchive ('-o' + $mesa) -y | Out-Null
if ($LASTEXITCODE) { throw 'Mesa extraction failed.' }
foreach ($name in @('opengl32.dll','libgallium_wgl.dll','libglapi.dll')) {
    $library = Join-Path $mesa ('x64/' + $name)
    if (Test-Path -LiteralPath $library) {
        Copy-Item -LiteralPath $library -Destination (Split-Path $java)
        [PSCustomObject]@{RendererLibrary=$name;SHA256=(Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash} | ConvertTo-Json -Compress
    }
}
if (-not (Test-Path -LiteralPath (Join-Path (Split-Path $java) 'opengl32.dll'))) { throw 'Mesa OpenGL library missing.' }
$env:GALLIUM_DRIVER = 'llvmpipe'
New-NetFirewallRule -DisplayName 'Slinky test JVM outbound block' -Direction Outbound -Action Block -Program $java | Out-Null
New-NetFirewallRule -DisplayName 'Slinky test JVM inbound block' -Direction Inbound -Action Block -Program $java | Out-Null
$pml = Join-Path $lab 'minecraft.pml'
$csv = Join-Path $lab 'minecraft.csv'
Start-Process -FilePath $procmon -ArgumentList @('/AcceptEula','/Quiet','/Minimized','/BackingFile',('"'+$pml+'"')) | Out-Null
Start-Sleep -Seconds 3
if (-not (Test-Path -LiteralPath $pml) -or -not (Get-Process Procmon64 -ErrorAction SilentlyContinue)) { throw 'Capture unavailable; refuse execution.' }
$game = $null
$injector = $null
$observationError = $null
try {
    $arguments = Get-Content -LiteralPath (Join-Path $gameRoot 'launch-arguments.json') -Raw | ConvertFrom-Json
    $quoted = @($arguments | ForEach-Object { '"' + $_ + '"' })
    $game = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $gameRoot -RedirectStandardOutput (Join-Path $gameRoot 'stdout.txt') -RedirectStandardError (Join-Path $gameRoot 'stderr.txt') -PassThru
    Start-Sleep -Seconds 30
    $game.Refresh()
    [PSCustomObject]@{Stage='minecraft-baseline';PID=$game.Id;Exited=$game.HasExited;WindowTitle=$game.MainWindowTitle;WindowHandle=$game.MainWindowHandle.ToInt64()} | ConvertTo-Json -Compress
    if (-not $game.HasExited -and $game.MainWindowHandle -ne [IntPtr]::Zero) {
        if (@(Get-Process java,javaw -ErrorAction SilentlyContinue).Count -ne 1) { throw 'Ambiguous injection target.' }
        Write-Output 'INJECTION_START'
        $injector = Start-Process -FilePath $exe -ArgumentList '--no-pause' -PassThru
        $finished = $injector.WaitForExit(150000)
        [PSCustomObject]@{Stage='minecraft-injection';PID=$injector.Id;ExitedWithin150Seconds=$finished;ExitCode=if($finished){$injector.ExitCode}else{$null}} | ConvertTo-Json -Compress
        Start-Sleep -Seconds 30
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName System.Drawing
        $bounds = [Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bitmap = [Drawing.Bitmap]::new($bounds.Width,$bounds.Height)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        $graphics.CopyFromScreen($bounds.Location,[Drawing.Point]::Empty,$bounds.Size)
        $screenPath = Join-Path $gameRoot 'after-injection.jpg'
        $bitmap.Save($screenPath,[Drawing.Imaging.ImageFormat]::Jpeg)
        $graphics.Dispose()
        $bitmap.Dispose()
        $screenEncoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($screenPath))
        for ($offset=0; $offset -lt $screenEncoded.Length; $offset+=3000) { Write-Output ('SCREEN_CHUNK ' + $screenEncoded.Substring($offset,[Math]::Min(3000,$screenEncoded.Length-$offset))) }
    } else {
        Write-Output 'MINECRAFT_NOT_READY: no live game window; no injection attempted.'
    }
} catch {
    $observationError = $_.Exception.Message
    Write-Output ('OBSERVATION_ERROR ' + $observationError)
} finally {
    foreach ($process in @($injector,$game)) { if ($null -ne $process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue } }
    Start-Sleep -Seconds 2
    Start-Process -FilePath $procmon -ArgumentList '/Terminate','/Quiet' -Wait | Out-Null
    foreach ($name in @('stdout.txt','stderr.txt','logs/latest.log','game/logs/latest.log')) {
        $file = Join-Path $gameRoot $name
        if (Test-Path -LiteralPath $file) { Write-Output ('GAME_LOG '+$name); Get-Content -LiteralPath $file -Tail 100 }
    }
    if ($null -ne $injector) {
        $nativeLog = Join-Path $env:TEMP ('slinky-' + $injector.Id + '/slinky-native.log')
        if (Test-Path -LiteralPath $nativeLog) { Write-Output 'NATIVE_LOG'; Get-Content -LiteralPath $nativeLog -Encoding UTF8 -Tail 160 }
    }
}
Start-Process -FilePath $procmon -ArgumentList @('/AcceptEula','/Quiet','/OpenLog',('"'+$pml+'"'),'/SaveAs',('"'+$csv+'"')) -Wait | Out-Null
$roots = @()
if ($null -ne $game) { $roots += [string]$game.Id }
if ($null -ne $injector) { $roots += [string]$injector.Id }
& (Join-Path $PSScriptRoot 'Export-Trace.ps1') -Csv $csv -RootPids $roots -Stage 'minecraft'
Write-Output 'BOUNDARY: offline startup/menu observation; no personal login, multiplayer, or gameplay. No absence-of-malware guarantee.'
if ($null -ne $observationError) { throw $observationError }
if ($null -eq $injector) { Write-Output '::warning::Injection was not exercised; inspect Minecraft startup failure.' }
