$ErrorActionPreference = 'Stop'
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($javaCommand) { $candidates += $javaCommand.Source }
$extensions = Join-Path $env:USERPROFILE '.vscode\extensions'
if (Test-Path -LiteralPath $extensions) {
    $candidates += Get-ChildItem -Path (Join-Path $extensions 'redhat.java-*\jre\*\bin\java.exe') -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName
}
foreach ($base in @('C:\Program Files\Eclipse Adoptium','C:\Program Files\Java','C:\Program Files\Microsoft')) {
    if (Test-Path -LiteralPath $base) { $candidates += Get-ChildItem -Path (Join-Path $base '*\bin\java.exe') -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName }
}
foreach ($candidate in ($candidates | Select-Object -Unique)) {
    if (!(Test-Path -LiteralPath $candidate)) { continue }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $candidate
    $info.Arguments = '-version'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $probe = [System.Diagnostics.Process]::Start($info)
    $version = $probe.StandardError.ReadToEnd() + $probe.StandardOutput.ReadToEnd()
    $probe.WaitForExit()
    $probe.Dispose()
    if ($version -match 'version "(\d+)' -and [int]$Matches[1] -ge 21) { return $candidate }
}
throw 'Java 21 or newer is required. Install a Java 21 JDK and set JAVA_HOME, then try again.'
