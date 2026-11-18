<#
.SYNOPSIS
    Builds, tests, and launches the System Monitor: the C daemon (monitord) and the JavaFX dashboard.

.DESCRIPTION
    Default: build both parts, start the daemon, open the dashboard, and stop the daemon when the
    dashboard window is closed.

    The daemon is started with --exit-on-stdin-eof, and this script holds the write end of its
    standard-input pipe. When the script ends (normally, with Ctrl+C, or killed), the pipe closes and
    the daemon shuts down cleanly on its own, so a daemon is never left behind holding the socket.

    Run it from a normal terminal (PowerShell, Windows Terminal, cmd). The daemon's log lines
    appear in the same window.

.PARAMETER Build
    Build the daemon and the dashboard, then stop.

.PARAMETER Test
    Build, then run every test: the C unit tests, the sampler self-test, the dashboard unit tests,
    and an end-to-end smoke test (a private daemon queried by monctl and by the Java client).

.PARAMETER NoBuild
    Skip the build step and use what is already built.

.PARAMETER DaemonOnly
    Start only the daemon and keep it running in this window until Ctrl+C.

.PARAMETER Config
    Build configuration: Release (default) or Debug.

.PARAMETER IntervalMs
    Sampling interval in milliseconds (100 to 10000, default 1000). Can be changed live from the dashboard.

.PARAMETER History
    Size of the daemon's history buffer, in snapshots (default 600).

.PARAMETER Socket
    Socket path. Default: %LOCALAPPDATA%\monitord\monitor.sock (the same default the dashboard uses).
    Keep it under 108 characters.

.PARAMETER LogLevel
    Daemon log level: debug, info (default), warn, or error.

.PARAMETER DaemonDir
    The daemon project (the folder with CMakeLists.txt). Found automatically next to this script
    or in .\monitord.

.PARAMETER DashboardDir
    The dashboard project (the folder with pom.xml). Found automatically in .\dashboard.

.PARAMETER BuildDir
    CMake build folder. Default: <DaemonDir>\build

.EXAMPLE
    .\run-demo.ps1
    Build and run the whole demo.

.EXAMPLE
    .\run-demo.ps1 -Test
    Build and run all tests.

.EXAMPLE
    .\run-demo.ps1 -NoBuild -IntervalMs 500 -History 1200
    Run without rebuilding, sampling every 500 ms and keeping 1200 snapshots of history.

.EXAMPLE
    .\run-demo.ps1 -DaemonOnly -LogLevel debug
    Run just the daemon in this window.
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$Test,
    [switch]$NoBuild,
    [switch]$DaemonOnly,
    [ValidateSet('Debug', 'Release')][string]$Config = 'Release',
    [ValidateRange(100, 10000)][int]$IntervalMs = 1000,
    [ValidateRange(1, 100000)][int]$History = 600,
    [string]$Socket = '',
    [ValidateSet('debug', 'info', 'warn', 'error')][string]$LogLevel = 'info',
    [string]$DaemonDir = '',
    [string]$DashboardDir = '',
    [string]$BuildDir = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------------- helpers

function Write-Step([string]$Text) { Write-Host ''; Write-Host "== $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text)   { Write-Host "   $Text" -ForegroundColor Green }
function Write-Note([string]$Text) { Write-Host "   $Text" -ForegroundColor DarkGray }

# Quotes one command-line argument for CreateProcess when it contains spaces or quotes.
function Quote-Arg([string]$Value) {
    if ($Value -match '[\s"]') { return '"' + ($Value -replace '"', '\"') + '"' }
    return $Value
}

# The first candidate folder that contains $Marker, or $null.
function Find-Dir([string[]]$Candidates, [string]$Marker) {
    foreach ($c in $Candidates) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c $Marker))) {
            return (Resolve-Path -LiteralPath $c).Path
        }
    }
    return $null
}

function Require-Tool([string]$Name, [string]$Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "'$Name' was not found on PATH. $Hint"
    }
}

# 'java -version' writes to stderr; going through cmd.exe keeps that from raising a PowerShell error.
function Get-JavaMajor {
    $text = (& cmd.exe /c 'java -version 2>&1') | Out-String
    if ($text -match 'version "(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[2]) { return [int]$Matches[2] }    # old "1.8.0" style
        return $major
    }
    return 0
}

function Find-Exe([string]$Name) {
    $candidates = @(
        (Join-Path (Join-Path $BuildDir $Config) "$Name.exe"),   # Visual Studio generator: build\Release\x.exe
        (Join-Path $BuildDir "$Name.exe")                        # Ninja / NMake: build\x.exe
    )
    foreach ($p in $candidates) {
        if (Test-Path -LiteralPath $p) { return $p }
    }
    throw "$Name.exe was not found under $BuildDir. Run without -NoBuild to build it."
}

function Build-Daemon {
    Write-Step "Building the daemon ($Config)"
    & cmake -S $DaemonDir -B $BuildDir "-DCMAKE_BUILD_TYPE=$Config"
    if ($LASTEXITCODE -ne 0) { throw 'CMake configure failed (see the troubleshooting section of the README).' }
    & cmake --build $BuildDir --config $Config
    if ($LASTEXITCODE -ne 0) { throw 'The daemon build failed.' }
}

function Build-Dashboard {
    Write-Step 'Building the dashboard (Maven)'
    & mvn -B -q -f $script:Pom -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw 'The dashboard build failed.' }
}

function New-DaemonArgs([int]$Interval, [int]$Hist, [string]$SocketPath, [bool]$ExplicitSocket, [string]$LogFile, [string]$Level) {
    $a = @('--interval-ms', "$Interval", '--history', "$Hist", '--log-level', $Level,
           '--log-file', (Quote-Arg $LogFile), '--exit-on-stdin-eof')
    if ($ExplicitSocket) { $a += @('--socket', (Quote-Arg $SocketPath)) }
    return $a
}

# Starts the daemon with a piped stdin that stays open until Stop-Daemon (or this script ending).
# Its stdout and stderr are not redirected, so its log lines show up in this console.
function Start-Daemon([string]$Exe, [string[]]$ArgList) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ($ArgList -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    return $proc
}

# True once the daemon has logged that it is listening; false if it exits or times out first.
function Wait-DaemonReady($Proc, [string]$LogFile, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($Proc.HasExited) { return $false }
        if ((Test-Path -LiteralPath $LogFile) -and (Select-String -LiteralPath $LogFile -Pattern 'listening on' -Quiet)) {
            return $true
        }
        Start-Sleep -Milliseconds 150
    }
    return $false
}

function Show-LogTail([string]$LogFile) {
    if (Test-Path -LiteralPath $LogFile) {
        Write-Host '   --- last lines of the daemon log ---' -ForegroundColor Yellow
        Get-Content -LiteralPath $LogFile -Tail 15 | ForEach-Object { Write-Host "   $_" }
    }
}

# Asks the daemon to stop by closing its stdin; kills it only if it does not exit within 5 seconds.
function Stop-Daemon($Proc, [string]$SocketPath) {
    if ($null -eq $Proc) { return }
    if (-not $Proc.HasExited) {
        try { $Proc.StandardInput.Close() } catch { }
        if (-not $Proc.WaitForExit(5000)) {
            Write-Warning 'The daemon did not stop on its own; terminating it.'
            try { $Proc.Kill() } catch { }
            [void]$Proc.WaitForExit(2000)
            # A killed daemon cannot remove its socket file. It is certainly dead now, so remove it.
            if ($SocketPath -and (Test-Path -LiteralPath $SocketPath)) {
                Remove-Item -LiteralPath $SocketPath -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

# Runs a program with its output captured to files (robust in Windows PowerShell 5.1).
function Invoke-Capture([string]$Exe, [string[]]$ArgList) {
    $outFile = [System.IO.Path]::GetTempFileName()
    $errFile = [System.IO.Path]::GetTempFileName()
    try {
        $p = Start-Process -FilePath $Exe -ArgumentList $ArgList -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $outFile -RedirectStandardError $errFile
        return [pscustomobject]@{
            ExitCode = $p.ExitCode
            Out      = (Get-Content -LiteralPath $outFile -Raw)
            Err      = (Get-Content -LiteralPath $errFile -Raw)
        }
    } finally {
        Remove-Item -LiteralPath $outFile, $errFile -Force -ErrorAction SilentlyContinue
    }
}

$results = New-Object System.Collections.ArrayList
function Add-Result([string]$Name, [bool]$Passed, [string]$Detail = '') {
    [void]$script:results.Add([pscustomobject]@{ Name = $Name; Passed = $Passed; Detail = $Detail })
    if ($Passed) { Write-Ok "PASS  $Name" } else { Write-Host "   FAIL  $Name  $Detail" -ForegroundColor Red }
}

# ------------------------------------------------------------------------------------ main

$daemon = $null
$sockToClean = $null
$oldSocketEnv = $env:MONITOR_SOCKET

try {
    if ($Build -and $NoBuild) { throw '-Build and -NoBuild cannot be used together.' }

    # ---- locate the two projects ----
    $repoRoot = $PSScriptRoot
    if (-not $DaemonDir) {
        $DaemonDir = Find-Dir @($repoRoot, (Join-Path $repoRoot 'monitord')) 'CMakeLists.txt'
    }
    if (-not $DashboardDir) {
        $DashboardDir = Find-Dir @((Join-Path $repoRoot 'dashboard'), (Join-Path (Split-Path -Parent $repoRoot) 'dashboard')) 'pom.xml'
    }
    if (-not $DaemonDir)    { throw 'Could not find the daemon project (a folder with CMakeLists.txt next to this script or in .\monitord). Use -DaemonDir.' }
    if (-not $DashboardDir -and -not $DaemonOnly) { throw 'Could not find the dashboard project (.\dashboard with a pom.xml). Use -DashboardDir.' }
    if (-not $BuildDir) { $BuildDir = Join-Path $DaemonDir 'build' }
    $script:Pom = if ($DashboardDir) { Join-Path $DashboardDir 'pom.xml' } else { '' }

    # ---- tools ----
    Require-Tool 'cmake' 'Install CMake 3.20 or newer from https://cmake.org/download/.'
    if (-not $DaemonOnly) {
        Require-Tool 'java' 'Install a JDK, version 17 or newer.'
        Require-Tool 'mvn'  'Install Apache Maven 3.9 or newer and add its bin folder to PATH.'
        $javaMajor = Get-JavaMajor
        if ($javaMajor -lt 17) { throw "Java 17 or newer is required (found major version $javaMajor)." }
    }

    # ---- socket path (the daemon and the dashboard must agree on it) ----
    if ($Socket) {
        if (-not [System.IO.Path]::IsPathRooted($Socket)) { $Socket = Join-Path (Get-Location).Path $Socket }
        $sockPath = $Socket
        $dir = Split-Path -Parent $sockPath
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    } else {
        if (-not $env:LOCALAPPDATA) { throw 'LOCALAPPDATA is not set; pass -Socket explicitly.' }
        $sockPath = Join-Path $env:LOCALAPPDATA 'monitord\monitor.sock'
    }
    if ($sockPath.Length -ge 108) { throw "The socket path is too long (the AF_UNIX limit is 107 characters): $sockPath" }

    # ---- build ----
    if (-not $NoBuild) {
        Build-Daemon
        if (-not $DaemonOnly) { Build-Dashboard }
    }
    if ($Build) { Write-Host ''; Write-Ok 'Build finished.'; return }

    $daemonExe = Find-Exe 'monitord'

    # ---------------------------------------------------------------- test mode
    if ($Test) {
        Write-Step 'C unit tests (CTest)'
        & ctest --test-dir $BuildDir -C $Config --output-on-failure
        Add-Result 'protocol unit tests (ctest)' ($LASTEXITCODE -eq 0)

        Write-Step 'Sampler self-test (about 12 seconds)'
        & (Find-Exe 'selftest') --duration-s 12 --quiet
        Add-Result 'sampler self-test' ($LASTEXITCODE -eq 0)

        Write-Step 'Dashboard unit tests (Maven)'
        & mvn -B -f $script:Pom test
        Add-Result 'dashboard unit tests (maven)' ($LASTEXITCODE -eq 0)

        Write-Step 'End-to-end smoke test: a private daemon, monctl, and the Java client'
        $monctl   = Find-Exe 'monctl'
        $testSock = Join-Path $env:TEMP ('mon-{0}.sock' -f $PID)
        $testLog  = Join-Path $env:TEMP ('mon-{0}.log' -f $PID)
        if ($testSock.Length -ge 108) { throw "The temp path is too long for an AF_UNIX socket: $testSock" }
        Remove-Item -LiteralPath $testLog -Force -ErrorAction SilentlyContinue

        $d = Start-Daemon $daemonExe (New-DaemonArgs 200 100 $testSock $true $testLog 'info')
        try {
            if (-not (Wait-DaemonReady $d $testLog 10)) {
                Add-Result 'smoke: the daemon starts' $false 'it did not report that it is listening'
                Show-LogTail $testLog
            } else {
                Add-Result 'smoke: the daemon starts' $true
                Start-Sleep -Seconds 2                     # at 200 ms per sample the history fills quickly

                $q = Quote-Arg $testSock
                $r = Invoke-Capture $monctl @('--socket', $q, '--history', '0', '--until-last', '--brief', '--timeout', '15')
                $ok = ($r.ExitCode -eq 0) -and ($r.Err -match 'contiguous') -and ($r.Err -notmatch 'NOT contiguous|never arrived')
                Add-Result 'smoke: monctl receives a complete, contiguous history' $ok ("exit {0}: {1}" -f $r.ExitCode, $r.Err)

                $r = Invoke-Capture $monctl @('--socket', $q, '--ping', '--brief', '--count', '4', '--timeout', '15')
                Add-Result 'smoke: monctl ping gets a pong' (($r.ExitCode -eq 0) -and ($r.Out -match 'pong')) ("exit {0}" -f $r.ExitCode)

                $env:MONITOR_SOCKET = $testSock
                $out = & mvn -B -q -f $script:Pom compile exec:java '-Dexec.args=--history --ping --seconds 5' | Out-String
                $javaOk = ($out -match 'history complete: \d+ snapshots, seq \d+\.\.\d+, contiguous') -and ($out -match 'pong')
                Add-Result 'smoke: the Java client gets history and a pong' $javaOk
                if (-not $javaOk) { Write-Note $out }
            }
        } finally {
            Stop-Daemon $d $testSock
            Remove-Item -LiteralPath $testLog -Force -ErrorAction SilentlyContinue
        }

        Write-Step 'Summary'
        $failed = 0
        foreach ($r in $results) {
            if ($r.Passed) { Write-Host ("   PASS  {0}" -f $r.Name) -ForegroundColor Green }
            else           { Write-Host ("   FAIL  {0}" -f $r.Name) -ForegroundColor Red; $failed++ }
        }
        Write-Host ''
        if ($failed -gt 0) { Write-Host "$failed of $($results.Count) checks failed." -ForegroundColor Red; exit 1 }
        Write-Host "All $($results.Count) checks passed." -ForegroundColor Green
        return
    }

    # ---------------------------------------------------------------- run mode
    $logFile = Join-Path $env:TEMP 'monitord-demo.log'
    Remove-Item -LiteralPath $logFile -Force -ErrorAction SilentlyContinue
    $env:MONITOR_SOCKET = $sockPath                       # tells the dashboard where the daemon listens
    $sockToClean = $sockPath

    Write-Step 'Starting the daemon'
    $daemon = Start-Daemon $daemonExe (New-DaemonArgs $IntervalMs $History $sockPath ([bool]$Socket) $logFile $LogLevel)
    if (-not (Wait-DaemonReady $daemon $logFile 10)) {
        Show-LogTail $logFile
        if ($daemon.HasExited) { throw "The daemon exited with code $($daemon.ExitCode) (see the log above)." }
        throw 'The daemon did not report that it is listening within 10 seconds.'
    }
    Write-Ok "listening on $sockPath (log file: $logFile)"

    if ($DaemonOnly) {
        Write-Host ''
        Write-Host 'The daemon is running. Press Ctrl+C to stop it.' -ForegroundColor Cyan
        while (-not $daemon.WaitForExit(250)) { }
    } else {
        Write-Step 'Opening the dashboard (close its window to stop the demo)'
        & mvn -B -q -f $script:Pom javafx:run
        if ($LASTEXITCODE -ne 0) { Write-Warning "The dashboard exited with code $LASTEXITCODE." }
    }
}
catch {
    Write-Host ''
    Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
finally {
    Stop-Daemon $daemon $sockToClean
    if ($null -eq $oldSocketEnv) { Remove-Item Env:\MONITOR_SOCKET -ErrorAction SilentlyContinue }
    else { $env:MONITOR_SOCKET = $oldSocketEnv }
}
