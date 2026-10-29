$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $project
$javaPath = & (Join-Path $PSScriptRoot 'Find-Java.ps1')
$javaBin = Split-Path -Parent $javaPath
New-Item -ItemType Directory -Force -Path 'build\classes' | Out-Null
$sources = Get-ChildItem -LiteralPath 'src\main\java\examflow' -Filter '*.java' | Select-Object -ExpandProperty FullName
$libraries = (Get-ChildItem -LiteralPath 'lib' -Filter '*.jar' | Where-Object Name -NotLike 'ecj-*' | Select-Object -ExpandProperty FullName) -join ';'
& $javaPath -jar 'lib\ecj-3.42.0.jar' -21 -encoding UTF-8 -cp $libraries -d 'build\classes' $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
& (Join-Path $javaBin 'jar.exe') --create --file 'build\ExamFlow-2.1.jar' --main-class examflow.ExamFlow -C 'build\classes' .
if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
Write-Host 'Java backend built successfully.'
