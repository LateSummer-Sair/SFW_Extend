@echo off
REM ============================================
REM  SaComS_LibC Windows Build Script
REM  Requires: MinGW-w64 gcc on PATH
REM  Usage:   build.bat
REM ============================================

setlocal enabledelayedexpansion

set JAVA_HOME=D:\APPs\SairFrameWork\8x64
set CC=gcc
set CFLAGS=-std=c11 -O2 -Wall
set INCLUDES=-I"%JAVA_HOME%\include" -I"%JAVA_HOME%\include\win32"
set LDFLAGS=-shared -o
set SRCDIR=src\main\c
set OUTDIR=lib
set TARGET=%OUTDIR%\SaComS_LibC.dll

if not exist "%OUTDIR%" mkdir "%OUTDIR%"

echo ==========================================
echo  Building SaComS_LibC.dll ...
echo ==========================================

set OBJLIST=
for %%f in (%SRCDIR%\*.c) do (
    echo Compiling %%f ...
    %CC% %CFLAGS% %INCLUDES% -c "%%f" -o "%%f.o"
    if errorlevel 1 (
        echo ERROR: Failed to compile %%f
        goto :end
    )
    set OBJLIST=!OBJLIST! "%%f.o"
)

echo Linking ...
%CC% %LDFLAGS% "%TARGET%" %OBJLIST%
if errorlevel 1 (
    echo ERROR: Failed to link
    goto :end
)

echo.
echo ==========================================
echo  Build SUCCESS: %TARGET%
echo ==========================================

:end
endlocal
