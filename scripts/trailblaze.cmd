@echo off
rem Trailblaze launcher for Windows.
rem
rem The Windows counterpart of the `trailblaze` bash launcher beside it. Windows runs Android and
rem web (Playwright) trails, not iOS. This launcher only finds Java and starts the JAR: the JVM
rem starts the background daemon itself on Windows, so none of the bash launcher's daemon fast
rem paths, CDS archive or startup lock are needed here.
rem
rem Environment variables:
rem   TRAILBLAZE_PORT           - HTTP port (default: 52525). Override to run multiple instances.
rem   TRAILBLAZE_JAR            - Path to the uber JAR (default: trailblaze.jar next to this script)
rem   TRAILBLAZE_MAX_HEAP       - JVM max heap (default: 4g)
rem   TRAILBLAZE_IO_PARALLELISM - Dispatchers.IO parallelism (default: 512)
rem   JAVA_HOME                 - JDK to run with (default: `java` on PATH). Needs JDK 17+.

rem Off from the start: under `cmd /v:on`, a `!` in a path (the JAR's folder, JAVA_HOME, a PATH
rem directory) would be read as a variable reference and dropped.
setlocal DisableDelayedExpansion

if not defined TRAILBLAZE_JAR set "TRAILBLAZE_JAR=%~dp0trailblaze.jar"
if exist "%TRAILBLAZE_JAR%" goto :have_jar
echo trailblaze: JAR not found at "%TRAILBLAZE_JAR%". Set TRAILBLAZE_JAR or reinstall. 1>&2
exit /b 1
:have_jar

set "JAVA_BIN="
if not defined JAVA_HOME goto :java_on_path
if not exist "%JAVA_HOME%\bin\java.exe" goto :java_on_path
set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
goto :have_java
:java_on_path
rem By its absolute path from the PATH directories: cmd.exe would run a bare `java` from the current
rem directory first, so an untrusted checkout could supply its own java.exe.
for %%J in (java.exe) do set "JAVA_BIN=%%~$PATH:J"
if defined JAVA_BIN goto :have_java
echo trailblaze: Java not found. Install JDK 17 or newer and put it on PATH, or set JAVA_HOME. 1>&2
exit /b 1
:have_java

rem Per-terminal device pins need the caller's shell PID, which the bash launcher passes as
rem $PPID. cmd.exe has no such variable, so hand the JVM this cmd.exe's own command line and let
rem it work the shell out. Delayed expansion reads the value without cmd.exe re-parsing it, and is
rem switched off again before anything touches the arguments.
setlocal EnableDelayedExpansion
set "TRAILBLAZE_LAUNCHER_CMDLINE=!CMDCMDLINE!"
setlocal DisableDelayedExpansion

if not defined TRAILBLAZE_MAX_HEAP set "TRAILBLAZE_MAX_HEAP=4g"
if not defined TRAILBLAZE_IO_PARALLELISM set "TRAILBLAZE_IO_PARALLELISM=512"

rem Trailblaze prints UTF-8 (check marks, dashes). Have the JVM write UTF-8 whether or not its
rem output is a console, and switch this console to UTF-8 for the run, back again afterwards.
rem `sun.std*.encoding` is the JDK 17 spelling, `std*.encoding` the JDK 19+ one. `file.encoding`
rem gives JDK 17 the UTF-8 default that JDK 18+ already has, in place of the legacy code page.
set "TB_OLD_CP="
for /f "tokens=2 delims=:." %%c in ('chcp 2^>nul') do set "TB_OLD_CP=%%c"
if defined TB_OLD_CP chcp 65001 >nul 2>nul
set "TB_UTF8=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

set "TB_JVM="%JAVA_BIN%" -Xmx%TRAILBLAZE_MAX_HEAP% -Dkotlinx.coroutines.io.parallelism=%TRAILBLAZE_IO_PARALLELISM% %TB_UTF8% -jar "%TRAILBLAZE_JAR%""

rem `stop` and `status` are answered by the bash launcher itself; the JVM spells them `app --stop`
rem and `app --status`. Their `--help` is answered here, so it never stops the daemon.
set "TB_CMD="
if /i "%~1"=="stop" set "TB_CMD=stop"
if /i "%~1"=="status" set "TB_CMD=status"
if not defined TB_CMD goto :jvm

:scan_help
shift
if "%~1"=="" goto :%TB_CMD%
if /i "%~1"=="--help" goto :help_%TB_CMD%
if /i "%~1"=="-h" goto :help_%TB_CMD%
goto :scan_help

:help_stop
echo Usage: trailblaze stop
echo.
echo Stop the running Trailblaze daemon.
goto :done

:help_status
echo Usage: trailblaze status
echo.
echo Check if the Trailblaze daemon is running.
goto :done

:jvm
%TB_JVM% %*
goto :done

:stop
%TB_JVM% app --stop
goto :done

:status
%TB_JVM% app --status

:done
set "TB_EXIT=%ERRORLEVEL%"
if defined TB_OLD_CP chcp %TB_OLD_CP% >nul 2>nul
exit /b %TB_EXIT%
