$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $project
$javaPath = & (Join-Path $PSScriptRoot 'Find-Java.ps1')
if (!(Test-Path -LiteralPath 'build\ExamFlow-2.1.jar')) { & (Join-Path $PSScriptRoot 'Build.ps1') }
$port = if ($env:PORT) { $env:PORT } else { '3030' }
try {
    $health = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $port + '/api/health') -TimeoutSec 2
    if ($health.backend -eq 'Java') {
        if ($health.release -ne '2.1-admin-accounts') {
            Write-Host 'An older ExamFlow server is running. Double-click Restart ExamFlow.cmd to activate the administrator account feature.'
            exit 1
        }
        Write-Host ('ExamFlow Java is already running at http://127.0.0.1:' + $port)
        Start-Process ('http://127.0.0.1:' + $port)
        exit 0
    }
} catch { }
Write-Host ('Open http://127.0.0.1:' + $port + ' in your browser.')
Write-Host 'Keep this window open while using ExamFlow. Press Ctrl+C to stop.'
& $javaPath -cp 'build\ExamFlow-2.1.jar;lib\*' examflow.ExamFlow
if ($LASTEXITCODE -ne 0) { throw 'ExamFlow stopped with an error. Check whether another app is using the port.' }
