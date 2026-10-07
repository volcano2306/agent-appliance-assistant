param(
    [string]$MongodPath = 'mongod.exe'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskCommand = Get-Command $MongodPath -ErrorAction Stop
$taskExe = $taskCommand.Source
if ([IO.Path]::GetFileName($taskExe) -ne 'mongod.exe') { throw 'mongod_executable_required' }
$taskId = [Guid]::NewGuid().ToString('N')
$taskReport = Join-Path $taskRoot "agent/reports/session-memory-engine-restart-$taskId"
$taskDb = Join-Path $taskReport 'db'
$taskLog = Join-Path $taskReport 'mongod.log'
$taskEvidence = Join-Path $taskReport 'engine-case.json'
$taskDatabase = "session_memory_test_$taskId"
New-Item -ItemType Directory -Path $taskDb | Out-Null
$taskResolvedDb = (Resolve-Path -LiteralPath $taskDb).Path
if (-not $taskResolvedDb.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar) -or
    $taskResolvedDb -ne (Join-Path $taskReport 'db')) { throw 'unsafe_test_db_path' }
$taskListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$taskListener.Start(); $taskPort = $taskListener.LocalEndpoint.Port; $taskListener.Stop()
if ($taskPort -eq 27017) { throw 'existing_mongo_port_forbidden' }
$taskUri = "mongodb://127.0.0.1:$taskPort"
$taskEngine = $null
$taskFirstPid = $null
$taskSecondPid = $null
$taskVerified = $false
function Start-TestEngine {
    $taskArgs = '--dbpath "{0}" --bind_ip 127.0.0.1 --port {1} --logpath "{2}" --logappend --noauth' -f $taskDb,$taskPort,$taskLog
    $taskChild = Start-Process -FilePath $taskExe -ArgumentList $taskArgs -WindowStyle Hidden -PassThru
    $taskDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $taskDeadline) {
        if ($taskChild.HasExited) { throw 'test_engine_exited_early' }
        $taskSocket = [Net.Sockets.TcpClient]::new()
        try { $taskSocket.Connect('127.0.0.1',$taskPort); return $taskChild }
        catch { Start-Sleep -Milliseconds 250 }
        finally { $taskSocket.Dispose() }
    }
    $taskChild.Kill(); $taskChild.WaitForExit(); throw 'test_engine_start_timeout'
}
function Stop-TestEngine($taskChild) {
    if ($null -ne $taskChild -and -not $taskChild.HasExited) {
        # Only this retained child handle; never stop a service or enumerate other PIDs.
        if ($taskChild.Path -ne $taskExe) { throw 'test_engine_identity_mismatch' }
        $taskChild.Kill(); $taskChild.WaitForExit()
    }
}
function Run-TestPhase([string]$taskPhase) {
    Push-Location $taskRoot
    try {
        # Windows PowerShell 5 treats native stderr warnings as errors. The exit
        # code below remains authoritative, and stderr is retained in the log.
        $taskPreviousErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
        & mvn -o -f BE/pom.xml '-Dtest=SessionMemoryEngineRestartTest' `
            "-Dsession.memory.restart.phase=$taskPhase" "-Dsession.memory.mongo.uri=$taskUri" `
            "-Dsession.memory.restart.database=$taskDatabase" "-Dsession.memory.restart.evidence=$taskEvidence" test `
            *> (Join-Path $taskReport "maven-$taskPhase.log")
        $taskMavenExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $taskPreviousErrorPreference }
        if ($taskMavenExitCode -ne 0) { throw "engine_test_phase_failed:$taskPhase" }
    } finally { Pop-Location }
}
Write-Output "ENGINE_REPORT=$taskReport"
try {
    $taskEngine = Start-TestEngine; $taskFirstPid = $taskEngine.Id
    Run-TestPhase 'seed'
    Stop-TestEngine $taskEngine; $taskEngine = $null
    $taskEngine = Start-TestEngine; $taskSecondPid = $taskEngine.Id
    if ($taskFirstPid -eq $taskSecondPid) { throw 'engine_pid_not_changed' }
    Run-TestPhase 'verify'; $taskVerified = $true
    Write-Output 'ENGINE_RESTART_AND_MIGRATION_ROLLBACK_PASSED'
} finally {
    Stop-TestEngine $taskEngine
    if ($taskVerified) {
        # Native PowerShell only; resolve and verify the exact task-owned target before recursion.
        $taskCleanupPath = (Resolve-Path -LiteralPath $taskDb).Path
        if ($taskCleanupPath -ne $taskResolvedDb -or
            -not $taskCleanupPath.StartsWith($taskReport + [IO.Path]::DirectorySeparatorChar)) { throw 'unsafe_cleanup_path' }
        Remove-Item -LiteralPath $taskCleanupPath -Recurse -Force
    }
    @{ firstPid=$taskFirstPid; secondPid=$taskSecondPid; port=$taskPort; database=$taskDatabase;
       verified=$taskVerified; independentEngine=$true; productionServiceStopped=$false;
       testDataRemoved=$taskVerified } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskReport 'run.json') -Encoding UTF8
}
