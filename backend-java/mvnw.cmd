@echo off
setlocal

rem This project ships a self-contained Maven launcher. The copied wrapper jar
rem in older project snapshots may be a ZIP without a Main-Class manifest, so
rem use an installed Maven first and only fall back to a local Maven archive.
set "BASE_DIR=%~dp0"
set "MAVEN_VERSION=3.9.9"
set "MAVEN_HOME=%BASE_DIR%.mvn\apache-maven-%MAVEN_VERSION%"
set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"

if exist "%MAVEN_CMD%" goto run_local_maven

where mvn.cmd >nul 2>nul
if not errorlevel 1 goto run_system_maven

set "MAVEN_ZIP=%BASE_DIR%.mvn\apache-maven-%MAVEN_VERSION%-bin.zip"
if not exist "%MAVEN_ZIP%" (
  echo Maven is not installed and the project-local Maven distribution is missing.
  echo Downloading Apache Maven %MAVEN_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force '%BASE_DIR%.mvn' | Out-Null; Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile '%MAVEN_ZIP%'"
  if errorlevel 1 (
    echo Failed to download Maven. Install Maven or place a Maven distribution under %MAVEN_HOME%.
    exit /b 1
  )
)

echo Extracting project-local Maven...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%MAVEN_ZIP%' '%BASE_DIR%.mvn'"
if not exist "%MAVEN_CMD%" (
  echo Maven extraction failed: %MAVEN_CMD% was not found.
  exit /b 1
)

:run_local_maven
call "%MAVEN_CMD%" %*
exit /b %errorlevel%

:run_system_maven
call mvn.cmd %*
exit /b %errorlevel%
