@echo off
set VERSION=8.13
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (gradle %* & exit /b %ERRORLEVEL%)
set BASE=%USERPROFILE%\.gradle\wrapper\dists\castpro-zero\gradle-%VERSION%
set BIN=%BASE%\gradle-%VERSION%\bin\gradle.bat
if not exist "%BIN%" (
  mkdir "%BASE%" 2>nul
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip -OutFile '%BASE%\gradle.zip'; Expand-Archive -Force '%BASE%\gradle.zip' '%BASE%'"
)
call "%BIN%" %*
