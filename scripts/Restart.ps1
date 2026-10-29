$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $project
$port = if ($env:PORT) { [int]$env:PORT } else { 3030 }
$health = $null
try { $health = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $port + '/api/health') -TimeoutSec 2 } catch { }
if ($health) {
    if ($health.backend -ne 'Java') { throw 'The selected port belongs to another application. It has not been stopped.' }
    $listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($listeners.Count -ne 1) { throw 'Could not identify a single ExamFlow process. Close its server window manually, then run Start ExamFlow.cmd.' }
    $serverId = [int]$listeners[0]
    $server = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $serverId)
    if (!$server -or $server.Name -notin @('java.exe','javaw.exe') -or $server.CommandLine -notmatch 'examflow\.ExamFlow') {
        throw 'The listening process could not be verified as ExamFlow. No process was stopped.'
    }
    Stop-Process -Id $serverId -ErrorAction Stop
    Wait-Process -Id $serverId -Timeout 10 -ErrorAction SilentlyContinue
}
& (Join-Path $PSScriptRoot 'Start.ps1')
