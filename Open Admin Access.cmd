@echo off
if not exist "%~dp0data\admin-access.txt" (
 echo Administrator details are created when ExamFlow starts for the first time.
 echo Run Start ExamFlow.cmd, then open this shortcut again.
 pause
 exit /b 1
)
start "" notepad.exe "%~dp0data\admin-access.txt"
