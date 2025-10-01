@echo off
REM --- Configuration ---
set APP_NAME="Greenplum MCP Server"
set JAR_NAME="gp-mcp-server-0.0.1-SNAPSHOT.jar"
set MAVEN_COMMAND=".\mvnw.cmd"
set BUILD_COMMAND="clean compile -DskipTests"
set RUN_COMMAND="spring-boot:run"
set TARGET_DIR="target"

REM --- Colors (ANSI escape codes - might not work in all terminals) ---
set RED=^[[0;31m
set GREEN=^[[0;32m
set YELLOW=^[[0;33m
set BLUE=^[[0;34m
set PURPLE=^[[0;35m
set NC=^[[0m

REM --- Functions ---
:print_help
    echo %BLUE%Usage: run.bat [OPTIONS]%NC%
    echo %BLUE%Options:%NC%
    echo   %GREEN%-b, --build%NC%    Build the project before running (compile only, skips tests)
    echo   %GREEN%-c, --clean%NC%    Perform a clean build before running (clean, compile, skips tests)
    echo   %GREEN%-t, --test%NC%     Run tests before starting
    echo   %GREEN%-h, --help%NC%     Display this help message
    echo.
    echo %BLUE%Examples:%NC%
    echo   run.bat             REM Run without building
    echo   run.bat -b          REM Build and run
    echo   run.bat --clean     REM Clean build and run
    echo   run.bat -t          REM Run tests and start
    echo.
    echo %YELLOW%Environment Variables:%NC%
    echo   %PURPLE%DB_URL%NC%                    Database connection URL
    echo   %PURPLE%DB_USER%NC%                   Database username
    echo   %PURPLE%DB_PASSWORD%NC%               Database password
    echo   %PURPLE%DB_SEARCH_PATH%NC%            Default search path
    echo   %PURPLE%DB_STATEMENT_TIMEOUT_MS%NC%   Query timeout in milliseconds
    echo   %PURPLE%OTEL_EXPORTER_OTLP_ENDPOINT%NC% OpenTelemetry endpoint
    echo.
    echo %YELLOW%Note: Ensure database connection is configured via environment variables.%NC%
    goto :eof

:check_database_config
    if "%DB_URL%"=="" (
        echo %YELLOW%WARNING: DB_URL not set, using default: jdbc:postgresql://localhost:5432/gpdb%NC%
    )
    if "%DB_USER%"=="" (
        echo %YELLOW%WARNING: DB_USER not set, using default: gpuser%NC%
    )
    if "%DB_PASSWORD%"=="" (
        echo %YELLOW%WARNING: DB_PASSWORD not set, using default: secret%NC%
    )
    goto :eof

:display_config
    echo %BLUE%--- %APP_NAME% Configuration ---%NC%
    for /f "tokens=*" %%a in ('findstr /R /C:"<artifactId>spring-boot-starter-parent</artifactId>" pom.xml ^| findstr /R /C:"<version>"') do (
        for /f "tokens=2 delims=<>" %%b in ("%%a") do set SPRING_BOOT_VERSION=%%b
    )
    for /f "tokens=*" %%a in ('findstr /R /C:"<spring-ai.version>" pom.xml') do (
        for /f "tokens=2 delims=<>" %%b in ("%%a") do set SPRING_AI_VERSION=%%b
    )
    echo %BLUE%Spring Boot Version: %SPRING_BOOT_VERSION%%NC%
    echo %BLUE%Spring AI Version: %SPRING_AI_VERSION%%NC%
    echo %BLUE%Database URL: %DB_URL:="jdbc:postgresql://localhost:5432/gpdb"%%NC%
    echo %BLUE%Database User: %DB_USER:="gpuser"%%NC%
    echo %BLUE%Search Path: %DB_SEARCH_PATH:="public"%%NC%
    echo %BLUE%Statement Timeout: %DB_STATEMENT_TIMEOUT_MS:="5000"ms%NC%
    echo %BLUE%OpenTelemetry Endpoint: %OTEL_EXPORTER_OTLP_ENDPOINT:="http://localhost:4317"%%NC%
    echo %BLUE%-----------------------------------%NC%
    goto :eof

REM --- Main Script ---
set BUILD_NEEDED=0
set CLEAN_BUILD_NEEDED=0
set TEST_NEEDED=0

REM Parse arguments
:parse_args
if /%1/ == // goto :end_parse_args
if /%1/ == /-b/ set BUILD_NEEDED=1
if /%1/ == /--build/ set BUILD_NEEDED=1
if /%1/ == /-c/ set CLEAN_BUILD_NEEDED=1 & set BUILD_NEEDED=1
if /%1/ == /--clean/ set CLEAN_BUILD_NEEDED=1 & set BUILD_NEEDED=1
if /%1/ == /-t/ set TEST_NEEDED=1 & set BUILD_NEEDED=1
if /%1/ == /--test/ set TEST_NEEDED=1 & set BUILD_NEEDED=1
if /%1/ == /-h/ call :print_help & exit /b 0
if /%1/ == /--help/ call :print_help & exit /b 0
if /%1/ == /-b/ shift & goto :parse_args
if /%1/ == /-c/ shift & goto :parse_args
if /%1/ == /-t/ shift & goto :parse_args
if /%1/ == /-h/ shift & goto :parse_args
if /%1/ == /--build/ shift & goto :parse_args
if /%1/ == /--clean/ shift & goto :parse_args
if /%1/ == /--test/ shift & goto :parse_args
if /%1/ == /--help/ shift & goto :parse_args
echo %RED%Unknown option: %1%NC%
call :print_help
exit /b 1
shift
goto :parse_args
:end_parse_args

call :check_database_config
call :display_config

REM Perform build if requested
if %CLEAN_BUILD_NEEDED% equ 1 (
    echo %YELLOW%Performing clean build...%NC%
    %MAVEN_COMMAND% %BUILD_COMMAND%
    if %errorlevel% neq 0 (
        echo %RED%Clean build failed!%NC%
        exit /b 1
    )
    echo %GREEN%Clean build successful.%NC%
) else if %BUILD_NEEDED% equ 1 (
    echo %YELLOW%Performing build...%NC%
    %MAVEN_COMMAND% %BUILD_COMMAND%
    if %errorlevel% neq 0 (
        echo %RED%Build failed!%NC%
        exit /b 1
    )
    echo %GREEN%Build successful.%NC%
)

REM Run tests if requested
if %TEST_NEEDED% equ 1 (
    echo %YELLOW%Running tests...%NC%
    %MAVEN_COMMAND% test
    if %errorlevel% neq 0 (
        echo %RED%Tests failed!%NC%
        exit /b 1
    )
    echo %GREEN%Tests passed.%NC%
)

REM Run the application
echo %BLUE%Starting %APP_NAME%...%NC%
echo %PURPLE%🚀 MCP Server will be available at: http://localhost:8080/mcp%NC%
echo %PURPLE%📊 Metrics available at: http://localhost:8080/actuator/prometheus%NC%
echo %PURPLE%🏥 Health check at: http://localhost:8080/actuator/health%NC%
echo.
%MAVEN_COMMAND% %RUN_COMMAND%
