@echo off
REM Jhopan VPN Cross-Platform Build Script
REM Usage: build.bat [windows|linux|macos|all] [release|debug]

setlocal enabledelayedexpansion

REM Variables
set APP_NAME=Jhopan VPN
set APP_VERSION=1.0.0
set OUTPUT_DIR=%~dp0dist
set BINARY_NAME=jhopan-vpn

REM Parse arguments
set BUILD_TARGET=all
set BUILD_TYPE=release

if not "%1"=="" set BUILD_TARGET=%1
if not "%2"=="" set BUILD_TYPE=%2

echo.
echo =====================================
echo %APP_NAME% - Cross-Platform Build
echo =====================================
echo Target: %BUILD_TARGET%
echo Type: %BUILD_TYPE%
echo Output: %OUTPUT_DIR%
echo.

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
cd /d "%~dp0"

REM Set flags based on build type
if "%BUILD_TYPE%"=="release" (
    set LDFLAGS_COMMON=-ldflags="-s -w -linkmode external"
    set LDFLAGS_WINDOWS=-ldflags="-s -w -H windowsgui -linkmode external"
) else (
    set LDFLAGS_COMMON=-ldflags="-linkmode external"
    set LDFLAGS_WINDOWS=-ldflags="-H windowsgui -linkmode external"
)

REM Windows builds
if "%BUILD_TARGET%"=="all" goto windows_builds
if "%BUILD_TARGET%"=="windows" goto windows_builds
goto linux_builds

:windows_builds
echo [BUILD] Windows targets ^(console hidden^)...

echo   - windows/amd64...
set GOOS=windows&set GOARCH=amd64
go build %LDFLAGS_WINDOWS% -o "%OUTPUT_DIR%\%BINARY_NAME%-windows-amd64.exe" .
if errorlevel 1 (echo     ✗ FAILED) else (echo     ✓ OK)

echo   - windows/386...
set GOOS=windows&set GOARCH=386
go build %LDFLAGS_WINDOWS% -o "%OUTPUT_DIR%\%BINARY_NAME%-windows-386.exe" .
if errorlevel 1 (echo     ✗ FAILED) else (echo     ✓ OK)

echo   - windows/arm64...
set GOOS=windows&set GOARCH=arm64
go build %LDFLAGS_WINDOWS% -o "%OUTPUT_DIR%\%BINARY_NAME%-windows-arm64.exe" .
if errorlevel 1 (echo     ✗ FAILED) else (echo     ✓ OK)

if "%BUILD_TARGET%"=="windows" goto done

REM Linux builds
:linux_builds
if "%BUILD_TARGET%"=="all" (
    echo.
    echo [BUILD] Linux targets...
)
if "%BUILD_TARGET%"=="linux" (
    echo [BUILD] Linux targets...
)
if not "%BUILD_TARGET%"=="linux" if not "%BUILD_TARGET%"=="all" goto macos_builds

echo   - linux/amd64...
set GOOS=linux&set GOARCH=amd64
go build %LDFLAGS_COMMON% -o "%OUTPUT_DIR%\%BINARY_NAME%-linux-amd64" .
if errorlevel 1 (echo     ✗ FAILED) else (echo     ✓ OK)

echo   - linux/arm64...
set GOOS=linux&set GOARCH=arm64
go build %LDFLAGS_COMMON% -o "%OUTPUT_DIR%\%BINARY_NAME%-linux-arm64" .
if errorlevel 1 (echo     ✗ FAILED) else (echo     ✓ OK)

echo   - linux/arm...
set GOOS=linux&set GOARCH=arm
go build %LDFLAGS_COMMON% -o "%OUTPUT_DIR%\%BINARY_NAME%-linux-arm" .
if errorlevel 1 (echo     ✗ FAILED) else (echo     ✓ OK)

if "%BUILD_TARGET%"=="linux" goto done

REM macOS builds
:macos_builds
if "%BUILD_TARGET%"=="all" (
    echo.
    echo [BUILD] macOS targets...
)
if "%BUILD_TARGET%"=="macos" (
    echo [BUILD] macOS targets...
)
if not "%BUILD_TARGET%"=="macos" if not "%BUILD_TARGET%"=="all" goto done

echo   - darwin/amd64 ^(Intel^)...
set GOOS=darwin&set GOARCH=amd64
go build %LDFLAGS_COMMON% -o "%OUTPUT_DIR%\%BINARY_NAME%-darwin-amd64" ..
if errorlevel 1 (echo     ✗ FAILED) else (echo     ✓ OK)

echo   - darwin/arm64 ^(Apple Silicon^)...
set GOOS=darwin&set GOARCH=arm64
go build %LDFLAGS_COMMON% -o "%OUTPUT_DIR%\%BINARY_NAME%-darwin-arm64" ..
if errorlevel 1 (echo     ✗ FAILED) else (echo     ✓ OK)

:done
echo.
echo =====================================
echo.
if exist "%OUTPUT_DIR%" (
    dir "%OUTPUT_DIR%"
) else (
    echo No binaries created - check errors above
)
echo.
echo Supported platforms:
echo   Windows: amd64, 386, arm64
echo   Linux:   amd64, arm64, arm
echo   macOS:   amd64 ^(Intel^), arm64 ^(Apple Silicon^)
echo.
echo Usage examples:
echo   build.bat                 - Build all platforms
echo   build.bat windows         - Build Windows only
echo   build.bat linux           - Build Linux only  
echo   build.bat macos           - Build macOS only
echo   build.bat all debug       - Build all with debug symbols
echo.
echo For more info, see DEPLOYMENT.md
echo =====================================
echo.

endlocal

