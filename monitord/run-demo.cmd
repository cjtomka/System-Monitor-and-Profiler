@echo off
rem Starts run-demo.ps1 from cmd.exe (or Git Bash) without changing the PowerShell execution policy.
rem All arguments are passed through, for example:  run-demo.cmd -Test    or    run-demo.cmd -Config Debug
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-demo.ps1" %*
exit /b %ERRORLEVEL%
