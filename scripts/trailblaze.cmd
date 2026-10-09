@echo off
rem Trailblaze launcher for Windows.
rem
rem The Windows counterpart of the `trailblaze` bash launcher beside it. Windows runs web
rem (Playwright) trails only. This launcher only finds Java and starts the JAR: the JVM starts the
rem background daemon itself on Windows, so none of the bash launcher's daemon fast paths, CDS
rem archive or startup lock are needed here.
rem
rem Environment variables:
rem   TRAILBLAZE_PORT           - HTTP port (default: 52525). Override to run multiple instances.
rem   TRAILBLAZE_JAR            - Path to the uber JAR (default: trailblaze.jar next to this script)
rem   TRAILBLAZE_MAX_HEAP       - JVM max heap (default: 4g)
rem   TRAILBLAZE_IO_PARALLELISM - Dispatchers.IO parallelism (default: 512)
rem   JAVA_HOME                 - JDK to run with (default: `java` on PATH). Needs JDK 17+.

setlocal

if not defined TRAILBLAZE_JAR set "TRAILBLAZE_JAR=%~dp0trailblaze.jar"
if exist "%TRAILBLAZE_JAR%" goto :have_jar
echo trailblaze: JAR not found at "%TRAILBLAZE_JAR%". Set TRAILBLAZE_JAR or reinstall. 1>&2
exit /b 1
:have_jar

set "JAVA_BIN=java"
if not defined JAVA_HOME goto :java_on_path
if not exist "%JAVA_HOME%\bin\java.exe" goto :java_on_path
set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
goto :have_java
:java_on_path
where java >nul 2>nul
if not errorlevel 1 goto :have_java
echo trailblaze: Java not found. Install JDK 17 or newer and put it on PATH, or set JAVA_HOME. 1>&2
exit /b 1
:have_java

rem cmd.exe cannot tell whether stdin is a terminal. Default to non-interactive, as the bash
rem launcher does when unsure: it only decides whether `device connect` warns that its pin does
rem not outlive one shell.
if not defined TRAILBLAZE_INTERACTIVE set "TRAILBLAZE_INTERACTIVE=0"

if not defined TRAILBLAZE_MAX_HEAP set "TRAILBLAZE_MAX_HEAP=4g"
if not defined TRAILBLAZE_IO_PARALLELISM set "TRAILBLAZE_IO_PARALLELISM=512"

set "TB_JVM="%JAVA_BIN%" -Xmx%TRAILBLAZE_MAX_HEAP% -Dkotlinx.coroutines.io.parallelism=%TRAILBLAZE_IO_PARALLELISM% -jar "%TRAILBLAZE_JAR%""

rem `stop` and `status` are answered by the bash launcher itself; the JVM spells them `app --stop`
rem and `app --status`.
if /i "%~1"=="stop" goto :stop
if /i "%~1"=="status" goto :status

%TB_JVM% %*
exit /b %ERRORLEVEL%

:stop
%TB_JVM% app --stop
exit /b %ERRORLEVEL%

:status
%TB_JVM% app --status
exit /b %ERRORLEVEL%
