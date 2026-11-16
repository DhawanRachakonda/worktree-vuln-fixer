@echo off
rem ============================================================================================
rem  appmod.cmd - AppMod kit setup and maintenance for Windows
rem
rem  One command prepares a developer machine and a repository:
rem      appmod init C:\src\my-repo
rem  Every step first CHECKS what is installed and only installs or changes what is missing/outdated.
rem  Run "appmod --help" for all commands.
rem ============================================================================================
setlocal EnableExtensions DisableDelayedExpansion

rem ---------------- version pins (override by setting the same variable before running) --------
if not defined APPMOD_NODE_MAJOR     set "APPMOD_NODE_MAJOR=22"
if not defined APPMOD_NPM_MAJOR      set "APPMOD_NPM_MAJOR=10"
if not defined APPMOD_JAVA_MAJOR     set "APPMOD_JAVA_MAJOR=25"
if not defined APPMOD_VOLTA_VERSION  set "APPMOD_VOLTA_VERSION=2.0.2"
if not defined APPMOD_MAVEN_VERSION  set "APPMOD_MAVEN_VERSION=3.9.11"
if not defined APPMOD_MAVEN_MIN      set "APPMOD_MAVEN_MIN=3.9.0"
if not defined APPMOD_DOTNET_MAJOR   set "APPMOD_DOTNET_MAJOR=8"
if not defined APPMOD_COPILOT_MIN    set "APPMOD_COPILOT_MIN=1.0.55"
if not defined APPMOD_PLUGIN_MIN     set "APPMOD_PLUGIN_MIN=1.24.0"
if not defined APPMOD_HOME           set "APPMOD_HOME=%LOCALAPPDATA%\appmod"

set "SCRIPT_VERSION=1.0.0"
set "KIT_DIR=%~dp0"
set "TOOLS=%APPMOD_HOME%\tools"
set "WORK=%TEMP%\appmod-setup"
set "TMPF=%TEMP%\appmod-setup\out.txt"
set "PLUGIN_NAME=github-copilot-modernization"
set "PLUGIN_ID=github-copilot-modernization@github-copilot-modernization"
set "MARKET_NAME=github-copilot-modernization"
set "MARKET_REPO=microsoft/github-copilot-modernization"
set "PROBLEMS=0"
set "MODE=install"
set "PATH_ADDITIONS="
set "CHOICE_EXE=%SystemRoot%\System32\choice.exe"
if not exist "%CHOICE_EXE%" set "CHOICE_EXE=choice"

if not exist "%WORK%" mkdir "%WORK%" >nul 2>nul

rem ---------------- command + options ----------------------------------------------------------
set "COMMAND=%~1"
if "%COMMAND%"=="" goto :help
if /i "%COMMAND%"=="--help" goto :help
if /i "%COMMAND%"=="-h" goto :help
if /i "%COMMAND%"=="/?" goto :help
if /i "%COMMAND%"=="help" goto :help
if /i "%COMMAND%"=="--version" goto :cmd_version
if /i "%COMMAND%"=="version" goto :cmd_version
rem setup and start take their own options (--roots, --nats ...), so they are handed on before the option parser below sees them
if /i "%COMMAND%"=="setup" goto :cmd_setup
if /i "%COMMAND%"=="start" goto :cmd_start
shift

set "POS1="
set "POS2="
set "YES=0"
set "INSTALLER="
set "WITH_MCP=0"
set "WITH_DOTNET=0"
set "SKIP_LOGIN=0"
set "FORCE=0"
set "PERSIST="
set "TOOL="
set "KIT_UPGRADE=0"

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--yes"          (set "YES=1" & shift & goto :parse)
if /i "%~1"=="-y"             (set "YES=1" & shift & goto :parse)
if /i "%~1"=="--with-mcp"     (set "WITH_MCP=1" & shift & goto :parse)
if /i "%~1"=="--with-dotnet"  (set "WITH_DOTNET=1" & shift & goto :parse)
if /i "%~1"=="--skip-login"   (set "SKIP_LOGIN=1" & shift & goto :parse)
if /i "%~1"=="--force"        (set "FORCE=1" & shift & goto :parse)
if /i "%~1"=="--persist-path" (set "PERSIST=Y" & shift & goto :parse)
if /i "%~1"=="--no-persist"   (set "PERSIST=N" & shift & goto :parse)
if /i "%~1"=="--installer"    (set "INSTALLER=%~2" & shift & shift & goto :parse)
if /i "%~1"=="--tool"         (set "TOOL=%~2" & shift & shift & goto :parse)
set "_arg=%~1"
if "%_arg:~0,2%"=="--" (
    echo Unknown option: %~1
    echo Run "appmod --help" for usage.
    exit /b 2
)
if not defined POS1 (set "POS1=%~1" & shift & goto :parse)
if not defined POS2 (set "POS2=%~1" & shift & goto :parse)
echo Unexpected argument: %~1
exit /b 2
:parsed

if defined INSTALLER if /i not "%INSTALLER%"=="npm" if /i not "%INSTALLER%"=="winget" (
    echo --installer must be npm or winget. uv is not offered: GitHub does not publish Copilot CLI for uv or pip.
    exit /b 2
)

if defined TOOL if /i not "%TOOL%"=="copilot" if /i not "%TOOL%"=="claude" if /i not "%TOOL%"=="both" (
    echo --tool must be copilot, claude or both.
    exit /b 2
)

if /i "%COMMAND%"=="init"    goto :cmd_init
if /i "%COMMAND%"=="doctor"  goto :cmd_doctor
if /i "%COMMAND%"=="upgrade" goto :cmd_upgrade
if /i "%COMMAND%"=="plugin"  goto :cmd_plugin
if /i "%COMMAND%"=="kit"     goto :cmd_kit
echo Unknown command: %COMMAND%
echo Run "appmod --help" for usage.
exit /b 2

rem ============================================================================================
rem  COMMANDS
rem ============================================================================================

:help
echo.
echo  appmod %SCRIPT_VERSION% - AppMod kit setup for Windows (GitHub Copilot app modernization underneath)
echo.
echo  USAGE
echo    appmod setup [--roots a,b] [--tool copilot^|claude] [--yes] [--check]
echo                                                Once per machine: JDK, nats-server (downloaded, SHA-256 checked, only if missing),
echo                                                the engine program, and the folders of your repositories and your agent
echo    appmod start [--nats^|--engine]              Start NATS and the engine (both by default; NATS only on 127.0.0.1)
echo    appmod init ^<working-directory^> [options]   Prepare this machine and install the kit into a repository
echo    appmod doctor [^<working-directory^>]         Check everything, change nothing (exit code 1 if something is missing)
echo    appmod upgrade [plugin^|copilot^|node^|all]    Upgrade components (default: plugin)
echo    appmod upgrade kit ^<working-directory^>      Bring the kit files of a repository up to date (only files nobody edited; asks first)
echo    appmod plugin [status^|install^|upgrade]      Manage the GitHub Copilot app modernization plugin only
echo    appmod kit ^<working-directory^>              Install the kit files only (no tool installation)
echo    appmod version                              Show script version and pinned tool versions
echo    appmod --help                               This help
echo.
echo  WHAT "init" DOES (each step checks first and only acts if something is missing or too old)
echo    The kit, its tools and the engine need only a JDK %APPMOD_JAVA_MAJOR%. Steps 1 to 2 and 5 to 7 are for GitHub Copilot only: the
echo    Copilot app modernization plugin runs its hooks with Node.js and its MCP server with npx. With --tool claude they are skipped.
echo    1. Volta (Node.js version manager)        - Copilot only: installs Volta %APPMOD_VOLTA_VERSION% if missing (WinGet, else MSI)
echo    2. Node.js %APPMOD_NODE_MAJOR% LTS + npm %APPMOD_NPM_MAJOR%                - Copilot only: needed by the app modernization plugin; pinned through Volta
echo    3. PowerShell 7 (pwsh)                    - Copilot only: required by Copilot CLI on Windows
echo    4. Git                                    - required by agents and the harness
echo    5. GitHub Copilot CLI ^>= %APPMOD_COPILOT_MIN%           - Copilot only: WinGet unless you choose npm; skipped if already installed
echo    6. Copilot sign-in                        - Copilot only: offers "copilot login" unless a token variable is set
echo    7. App modernization plugin ^>= %APPMOD_PLUGIN_MIN%     - Copilot only: marketplace + plugin; installed only if missing, updated only if older
echo    8. JDK %APPMOD_JAVA_MAJOR%+                                - reuses JAVA_HOME / PATH / %%USERPROFILE%%\.jdks, else portable Temurin %APPMOD_JAVA_MAJOR%
echo    9. Kit files into the working directory   - via KitInstaller.java; identical files skipped, changed files kept unless --force
echo   10. Optional: .NET SDK %APPMOD_DOTNET_MAJOR% (--with-dotnet, SQL Server pack)
echo.
echo  OPTIONS
echo    --installer winget^|npm   How to install Copilot CLI (otherwise WinGet; npm needs the Node.js of step 2)
echo    --yes, -y                Answer yes to every question (unattended; Copilot CLI via WinGet, no sign-in prompt)
echo    --tool copilot^|claude^|both   Which tool's files to install: GitHub Copilot, Claude Code or both (asked on a new install; copilot with --yes)
echo    --with-mcp               Also copy .vscode/mcp.json (Copilot) and/or .mcp.json (Claude Code): the app modernization MCP server
echo    --with-dotnet            Also check/install the .NET SDK (SQL Server pack: builds .sqlproj)
echo    --skip-login             Do not offer "copilot login"
echo    --force                  Kit: overwrite existing kit files
echo    --persist-path           Add portable tools (JDK, Maven) to your user PATH without asking
echo    --no-persist             Never change your user PATH or JAVA_HOME
echo.
echo  VERSION PINS (set before running to override)
echo    APPMOD_NODE_MAJOR=%APPMOD_NODE_MAJOR%  APPMOD_NPM_MAJOR=%APPMOD_NPM_MAJOR%  APPMOD_JAVA_MAJOR=%APPMOD_JAVA_MAJOR%  APPMOD_VOLTA_VERSION=%APPMOD_VOLTA_VERSION%
echo    APPMOD_MAVEN_VERSION=%APPMOD_MAVEN_VERSION%  APPMOD_COPILOT_MIN=%APPMOD_COPILOT_MIN%  APPMOD_PLUGIN_MIN=%APPMOD_PLUGIN_MIN%
echo    APPMOD_HOME=%APPMOD_HOME%   (portable tools are kept in its "tools" folder)
echo.
echo  EXAMPLES
echo    appmod init C:\src\orders-service
echo    appmod init C:\src\orders-service --with-mcp --yes
echo    appmod init C:\src\orders-service --tool claude        ^(no Node, no Copilot CLI, no plugin^)
echo    appmod doctor C:\src\orders-service
echo    appmod upgrade plugin
echo    appmod upgrade all
echo.
echo  After init, start every task with:   copilot --agent=appmod-harness
echo.
exit /b 0

:cmd_version
echo appmod %SCRIPT_VERSION%
echo   JDK %APPMOD_JAVA_MAJOR% ^(the only runtime of the kit and the engine^), Maven %APPMOD_MAVEN_VERSION%,
echo   Node.js %APPMOD_NODE_MAJOR% / npm %APPMOD_NPM_MAJOR% via Volta %APPMOD_VOLTA_VERSION% ^(only for the Copilot app modernization plugin^),
echo   Copilot CLI ^>= %APPMOD_COPILOT_MIN%, app modernization plugin ^>= %APPMOD_PLUGIN_MIN%
exit /b 0

rem ------------------------------------------------------------------------------------------
:cmd_init
if not defined POS1 (
    echo Missing working directory.  Usage: appmod init ^<working-directory^> [options]
    exit /b 2
)
call :resolve_workdir "%POS1%"
if errorlevel 1 exit /b 2
echo.
echo  AppMod kit setup  -  working directory: %WORKDIR%
echo  Every step checks first; nothing is installed or changed if it is already in place.
call :windows_prereqs
if errorlevel 1 goto :finish
call :resolve_tool
if "%USES_COPILOT%"=="1" call :ensure_volta
if "%USES_COPILOT%"=="1" call :ensure_node
if "%USES_COPILOT%"=="1" call :ensure_pwsh
call :ensure_git
if "%USES_COPILOT%"=="1" call :ensure_copilot
if "%USES_COPILOT%"=="1" call :ensure_login
if "%USES_COPILOT%"=="1" call :ensure_plugin
call :ensure_java
if "%WITH_DOTNET%"=="1" call :ensure_dotnet
call :install_kit
call :persist_paths
goto :finish

rem ------------------------------------------------------------------------------------------
:cmd_doctor
set "MODE=check"
set "WORKDIR="
if not defined POS1 goto :doctor_checks
call :resolve_workdir "%POS1%"
if errorlevel 1 exit /b 2
:doctor_checks
echo.
echo  AppMod doctor  -  checks only, nothing is changed
call :windows_prereqs
call :resolve_tool
if "%USES_COPILOT%"=="1" call :ensure_volta
if "%USES_COPILOT%"=="1" call :ensure_node
if "%USES_COPILOT%"=="1" call :ensure_pwsh
call :ensure_git
if "%USES_COPILOT%"=="1" call :ensure_copilot
if "%USES_COPILOT%"=="1" call :ensure_login
if "%USES_COPILOT%"=="1" call :ensure_plugin
call :ensure_java
call :ensure_maven_optional
call :ensure_dotnet_optional
if defined WORKDIR call :check_kit
goto :finish

rem ------------------------------------------------------------------------------------------
:cmd_upgrade
set "SUBJECT=%POS1%"
if not defined SUBJECT set "SUBJECT=plugin"
call :windows_prereqs
if errorlevel 1 goto :finish
if /i "%SUBJECT%"=="plugin"  (call :put_tools_on_path & call :upgrade_plugin & goto :finish)
if /i "%SUBJECT%"=="copilot" (call :put_tools_on_path & call :upgrade_copilot & goto :finish)
if /i "%SUBJECT%"=="node"    (call :ensure_volta & call :upgrade_node & goto :finish)
if /i "%SUBJECT%"=="all"     goto :upgrade_all
if /i "%SUBJECT%"=="kit"     goto :upgrade_kit
echo Unknown upgrade target: %SUBJECT%   (use plugin, copilot, node, all or kit)
exit /b 2

:upgrade_all
call :ensure_volta
call :upgrade_node
call :upgrade_copilot
call :upgrade_plugin
goto :finish

:upgrade_kit
if not defined POS2 (
    echo Usage: appmod upgrade kit ^<working-directory^>
    exit /b 2
)
call :resolve_workdir "%POS2%"
if errorlevel 1 exit /b 2
call :ensure_java
set "KIT_UPGRADE=1"
if "%FORCE%"=="1" goto :upgrade_kit_go
echo.
echo  Preview - what the current kit would change (files you edited and identical files are left alone):
call :run_kit_installer --upgrade --dry-run
if errorlevel 1 (
    echo  There is no record of an earlier install in %WORKDIR%. Install first:  appmod kit %WORKDIR% --tool copilot^|claude^|both
    exit /b 2
)
call :ask "  Apply these changes?"
if /i "%ANSWER%"=="N" (call :info "Nothing changed" & goto :finish)
:upgrade_kit_go
call :install_kit
goto :finish

rem ------------------------------------------------------------------------------------------
:cmd_plugin
set "SUBJECT=%POS1%"
if not defined SUBJECT set "SUBJECT=status"
call :put_tools_on_path
if /i "%SUBJECT%"=="status"  (set "MODE=check" & call :ensure_plugin & goto :finish)
if /i "%SUBJECT%"=="install" (call :ensure_plugin & goto :finish)
if /i "%SUBJECT%"=="upgrade" (call :upgrade_plugin & goto :finish)
echo Unknown plugin action: %SUBJECT%   (use status, install or upgrade)
exit /b 2

rem ------------------------------------------------------------------------------------------
:cmd_kit
if not defined POS1 (
    echo Usage: appmod kit ^<working-directory^> [--tool copilot^|claude^|both] [--with-mcp] [--force]
    exit /b 2
)
call :resolve_workdir "%POS1%"
if errorlevel 1 exit /b 2
call :ensure_java
call :install_kit
goto :finish


rem ------------------------------------------------------------------------------------------
rem  appmod setup: once per machine (JDK, nats-server, the engine program, the folders and the tool). The work is install\AppmodSetup.java.
:cmd_setup
set "REST="
for /f "tokens=1,*" %%a in ("%*") do set "REST=%%b"
if defined REST (
    if not "%REST:--yes=%"=="%REST%" set "YES=1"
    if not "%REST:--persist-path=%"=="%REST%" (set "PERSIST=Y" & set "REST=%REST:--persist-path=%")
    if not "%REST:--no-persist=%"=="%REST%" (set "PERSIST=N" & set "REST=%REST:--no-persist=%")
)
echo.
echo  AppMod setup  -  prepares this machine for the engine (once per machine)
call :ensure_java
if not defined JAVA_EXE (
    echo  A JDK %APPMOD_JAVA_MAJOR% or later is needed first; see the message above.
    exit /b 1
)
set "_pafile=%WORK%\path-additions.txt"
if exist "%_pafile%" del /q "%_pafile%" >nul 2>nul
pushd "%KIT_DIR%"
call "%JAVA_EXE%" install\AppmodSetup.java --java-home "%JAVA_DIR%" --kit-dir "%KIT_DIR:~0,-1%" --path-additions-file "%_pafile%" %REST%
set "_rc=%errorlevel%"
popd
if not "%_rc%"=="0" exit /b %_rc%
if exist "%_pafile%" set /p _more=<"%_pafile%"
if defined _more set "PATH_ADDITIONS=%PATH_ADDITIONS%%_more%"
call :persist_paths
exit /b 0

rem ------------------------------------------------------------------------------------------
rem  appmod start [--nats|--engine]: runs NATS and the engine from the saved configuration. The work is install\AppmodStart.java.
:cmd_start
set "REST="
for /f "tokens=1,*" %%a in ("%*") do set "REST=%%b"
call :find_java
if not defined JAVA_EXE (
    echo  No JDK %APPMOD_JAVA_MAJOR% or later found. Run: appmod setup
    exit /b 1
)
pushd "%KIT_DIR%"
call "%JAVA_EXE%" install\AppmodStart.java %REST%
set "_rc=%errorlevel%"
popd
exit /b %_rc%

rem ------------------------------------------------------------------------------------------
:finish
echo.
if not "%PROBLEMS%"=="0" goto :finish_problems
echo  Result: everything required is in place.
if /i not "%COMMAND%"=="init" exit /b 0
echo  Next:   cd /d "%WORKDIR%"
if /i "%TOOL%"=="claude" (echo          claude --agent security-audit) else (echo          copilot --agent=appmod-harness)
exit /b 0
:finish_problems
echo  Result: %PROBLEMS% problem^(s^) - see the [FAIL] lines above. Fix them and run the same command again.
exit /b 1

rem ============================================================================================
rem  STEPS  (each: check -> report -> act only when needed; MODE=check never changes anything)
rem ============================================================================================

rem ---------------------------------------------------------------- which tool: Copilot steps or not
rem Sets TOOL and USES_COPILOT. A tool given with --tool wins; else a kit already in the folder says which one it is; else the person is asked
rem (init only; --yes and doctor take Copilot). Claude Code needs no Node.js, no Copilot CLI and no plugin.
:resolve_tool
if defined TOOL goto :tool_resolved
if not defined WORKDIR goto :tool_default
set "_has_gh="
set "_has_cl="
if exist "%WORKDIR%\.github\agents\security-fixer.agent.md" set "_has_gh=1"
if exist "%WORKDIR%\.claude\agents\security-fixer.md" set "_has_cl=1"
if defined _has_gh if defined _has_cl (set "TOOL=both" & goto :tool_resolved)
if defined _has_cl (set "TOOL=claude" & goto :tool_resolved)
if defined _has_gh (set "TOOL=copilot" & goto :tool_resolved)
:tool_default
if "%YES%"=="1" (set "TOOL=copilot" & goto :tool_resolved)
if "%MODE%"=="check" (set "TOOL=copilot" & goto :tool_resolved)
"%CHOICE_EXE%" /c CLB /m "  Set up for GitHub Copilot (C), Claude Code (L) or both (B)?"
if errorlevel 3 (set "TOOL=both") else if errorlevel 2 (set "TOOL=claude") else (set "TOOL=copilot")
:tool_resolved
set "USES_COPILOT=1"
if /i "%TOOL%"=="claude" set "USES_COPILOT=0"
if "%USES_COPILOT%"=="0" call :info "Tool: Claude Code - no Node.js, Copilot CLI or app modernization plugin is needed"
exit /b 0

:windows_prereqs
call :step "Windows prerequisites"
where curl >nul 2>nul
if errorlevel 1 (call :fail "curl not found - Windows 10 1803 or later is required" & exit /b 1)
where tar >nul 2>nul
if errorlevel 1 (call :fail "tar not found - Windows 10 1803 or later is required" & exit /b 1)
set "HAVE_WINGET=0"
where winget >nul 2>nul
if not errorlevel 1 set "HAVE_WINGET=1"
call :add_known_paths
if "%HAVE_WINGET%"=="1" (call :ok "curl, tar and WinGet available") else (call :ok "curl and tar available; WinGet not found - direct downloads will be used where possible")
exit /b 0

rem ---------------------------------------------------------------- Volta
:ensure_volta
call :step "Volta (manages the Node.js that the app modernization plugin needs)"
call :put_volta_on_path
call :volta_version
if defined VOLTA_VER (call :ok "Volta %VOLTA_VER% already installed" & exit /b 0)
if "%MODE%"=="check" (call :fail "Volta is not installed" & exit /b 0)
call :ask "  Volta is not installed. Install Volta %APPMOD_VOLTA_VERSION% now? It lets several Node.js versions live side by side"
if /i "%ANSWER%"=="N" (call :fail "Volta skipped - Node.js and Copilot CLI cannot be managed" & exit /b 0)
if "%HAVE_WINGET%"=="1" (
    call :info "Installing Volta with WinGet ..."
    call winget install --id Volta.Volta -e --silent --accept-source-agreements --accept-package-agreements
)
call :put_volta_on_path
call :volta_version
if defined VOLTA_VER (call :ok "Volta %VOLTA_VER% installed" & exit /b 0)
call :info "Downloading the Volta %APPMOD_VOLTA_VERSION% installer from GitHub ..."
set "_msi=%WORK%\volta-%APPMOD_VOLTA_VERSION%.msi"
call curl -fL --retry 3 -o "%_msi%" "https://github.com/volta-cli/volta/releases/download/v%APPMOD_VOLTA_VERSION%/volta-%APPMOD_VOLTA_VERSION%-windows-x86_64.msi"
if errorlevel 1 (call :fail "Volta download failed - check the proxy/network and run again" & exit /b 0)
call :info "Running the Volta installer (Windows may ask for administrator approval) ..."
call msiexec /i "%_msi%" /passive
call :put_volta_on_path
call :volta_version
if defined VOLTA_VER (call :ok "Volta %VOLTA_VER% installed" & exit /b 0)
call :fail "Volta installation did not succeed"
exit /b 0

:volta_version
set "VOLTA_VER="
for /f "delims=" %%v in ('volta --version 2^>nul') do if not defined VOLTA_VER set "VOLTA_VER=%%v"
exit /b 0

:put_volta_on_path
rem Volta's folders go FIRST so its shims win over any older Node.js on the machine PATH (this session only).
set "PATH=%ProgramFiles%\Volta\;%LOCALAPPDATA%\Volta\bin;%PATH%"
exit /b 0

rem ---------------------------------------------------------------- Node.js + npm (through Volta)
:ensure_node
call :step "Node.js %APPMOD_NODE_MAJOR% and npm %APPMOD_NPM_MAJOR% (through Volta; the app modernization plugin runs its hooks with node and its MCP server with npx)"
call :volta_version
if not defined VOLTA_VER (call :info "Skipped: needs Volta, see above" & exit /b 0)
call :report_foreign_node
call :volta_node_major
if not defined VNODE_MAJ goto :node_missing
if %VNODE_MAJ% GEQ %APPMOD_NODE_MAJOR% (call :ok "Node.js %VNODE_VER% is Volta's default" & goto :npm_check)
if "%MODE%"=="check" (call :fail "Volta's Node.js %VNODE_VER% is older than %APPMOD_NODE_MAJOR%" & goto :npm_check)
call :info "Volta's Node.js %VNODE_VER% is older than the recommended %APPMOD_NODE_MAJOR% - installing Node.js %APPMOD_NODE_MAJOR% (the old one stays available to projects that pin it)"
goto :node_install
:node_missing
if "%MODE%"=="check" (call :fail "Volta has no default Node.js" & exit /b 0)
call :info "Volta has no default Node.js yet - installing Node.js %APPMOD_NODE_MAJOR% LTS ..."
:node_install
call volta install node@%APPMOD_NODE_MAJOR%
call :volta_node_major
if not defined VNODE_MAJ (call :fail "Node.js installation through Volta failed" & exit /b 0)
call :ok "Node.js %VNODE_VER% installed and set as Volta's default"
:npm_check
call :npm_version
if not defined NPM_MAJ goto :npm_install
if %NPM_MAJ% GEQ %APPMOD_NPM_MAJOR% (call :ok "npm %NPM_VER% available" & exit /b 0)
if "%MODE%"=="check" (call :fail "npm %NPM_VER% is older than %APPMOD_NPM_MAJOR%" & exit /b 0)
:npm_install
if "%MODE%"=="check" (call :fail "npm not found" & exit /b 0)
call :info "Pinning npm %APPMOD_NPM_MAJOR% through Volta ..."
call volta install npm@%APPMOD_NPM_MAJOR%
call :npm_version
if defined NPM_MAJ (call :ok "npm %NPM_VER% available" & exit /b 0)
call :fail "npm could not be installed"
exit /b 0

:volta_node_major
set "VNODE_MAJ="
set "VNODE_VER="
rem only Volta's node shim counts; another Node.js on PATH is reported separately and never used
set "_vn="
for /f "delims=" %%p in ('where node 2^>nul ^| findstr /i "Volta"') do if not defined _vn set "_vn=%%p"
if not defined _vn exit /b 0
for /f "tokens=1-3 delims=v." %%a in ('call "%_vn%" --version 2^>nul') do (set "VNODE_MAJ=%%a" & set "VNODE_VER=%%a.%%b.%%c")
exit /b 0

:npm_version
set "NPM_MAJ="
set "NPM_VER="
for /f "tokens=1-3 delims=." %%a in ('npm --version 2^>nul') do (set "NPM_MAJ=%%a" & set "NPM_VER=%%a.%%b.%%c")
exit /b 0

:report_foreign_node
rem Tell the user about a Node.js that is not managed by Volta (it is left untouched).
set "_fn="
for /f "delims=" %%p in ('where node 2^>nul ^| findstr /v /i "Volta"') do if not defined _fn set "_fn=%%p"
if defined _fn call :info "Found another Node.js at %_fn% - left untouched; AppMod uses Volta's Node.js. If it is earlier on your PATH than Volta, move Volta first or uninstall it."
exit /b 0

rem ---------------------------------------------------------------- PowerShell 7
:ensure_pwsh
call :step "PowerShell 7 (required by Copilot CLI on Windows)"
call :pwsh_version
rem (a comparison with an unset variable is a syntax error, so test for it first: found by running doctor on a machine without PowerShell 7)
if not defined PWSH_MAJ goto :pwsh_missing
if "%PWSH_MAJ%"=="" goto :pwsh_missing
if %PWSH_MAJ% GEQ 6 (call :ok "PowerShell %PWSH_VER% available" & exit /b 0)
:pwsh_missing
if "%MODE%"=="check" (call :fail "PowerShell 6 or later not found (pwsh)" & exit /b 0)
if not "%HAVE_WINGET%"=="1" (call :fail "PowerShell 7 missing - install it from https://aka.ms/powershell-release?tag=stable and run again" & exit /b 0)
call :ask "  PowerShell 7 is not installed. Install it with WinGet now?"
if /i "%ANSWER%"=="N" (call :fail "PowerShell 7 skipped - Copilot CLI may not work" & exit /b 0)
call winget install --id Microsoft.PowerShell -e --silent --accept-source-agreements --accept-package-agreements
set "PATH=%ProgramFiles%\PowerShell\7;%PATH%"
call :pwsh_version
if defined PWSH_MAJ (call :ok "PowerShell %PWSH_VER% installed" & exit /b 0)
call :fail "PowerShell 7 installation did not succeed"
exit /b 0

:pwsh_version
set "PWSH_MAJ="
set "PWSH_VER="
for /f "tokens=1-3 delims=." %%a in ('pwsh -NoLogo -NoProfile -Command "$PSVersionTable.PSVersion.ToString()" 2^>nul') do (set "PWSH_MAJ=%%a" & set "PWSH_VER=%%a.%%b.%%c")
exit /b 0

rem ---------------------------------------------------------------- Git
:ensure_git
call :step "Git"
call :git_version
if defined GIT_VER (call :ok "Git %GIT_VER% available" & exit /b 0)
if "%MODE%"=="check" (call :fail "Git not found" & exit /b 0)
if not "%HAVE_WINGET%"=="1" (call :fail "Git missing - install it from https://git-scm.com/download/win and run again" & exit /b 0)
call :ask "  Git is not installed. Install Git with WinGet now?"
if /i "%ANSWER%"=="N" (call :fail "Git skipped" & exit /b 0)
call winget install --id Git.Git -e --silent --accept-source-agreements --accept-package-agreements
set "PATH=%ProgramFiles%\Git\cmd;%PATH%"
call :git_version
if defined GIT_VER (call :ok "Git %GIT_VER% installed" & exit /b 0)
call :fail "Git installation did not succeed"
exit /b 0

:git_version
set "GIT_VER="
for /f "tokens=3" %%v in ('git --version 2^>nul') do if not defined GIT_VER set "GIT_VER=%%v"
exit /b 0

rem ---------------------------------------------------------------- GitHub Copilot CLI
:ensure_copilot
call :step "GitHub Copilot CLI (minimum %APPMOD_COPILOT_MIN%)"
call :copilot_info
if not defined COP_VER goto :copilot_missing
call :vergeq "%COP_VER%" "%APPMOD_COPILOT_MIN%"
if "%VERGEQ%"=="1" (call :ok "Copilot CLI %COP_VER% already installed (%COP_SOURCE%) - nothing to do" & exit /b 0)
if "%MODE%"=="check" (call :fail "Copilot CLI %COP_VER% is older than %APPMOD_COPILOT_MIN% - run: appmod upgrade copilot" & exit /b 0)
call :info "Copilot CLI %COP_VER% is older than %APPMOD_COPILOT_MIN% - upgrading"
call :upgrade_copilot_by_source
exit /b 0
:copilot_missing
if "%MODE%"=="check" (call :fail "Copilot CLI not installed" & exit /b 0)
call :choose_installer
if /i "%INSTALLER%"=="winget" goto :copilot_winget
call :npm_version
if not defined NPM_MAJ (call :fail "npm is not available, so Copilot CLI cannot be installed with npm - fix Node.js/npm or use --installer winget" & exit /b 0)
call :info "npm %NPM_VER% found - installing GitHub Copilot CLI with npm (Volta keeps it on Node.js %APPMOD_NODE_MAJOR%) ..."
call npm install -g @github/copilot
goto :copilot_verify
:copilot_winget
if not "%HAVE_WINGET%"=="1" (call :fail "WinGet not found - use --installer npm" & exit /b 0)
call :info "Installing GitHub Copilot CLI with WinGet ..."
call winget install --id GitHub.Copilot -e --silent --accept-source-agreements --accept-package-agreements
set "PATH=%LOCALAPPDATA%\Microsoft\WinGet\Links;%PATH%"
:copilot_verify
call :copilot_info
if defined COP_VER (call :ok "Copilot CLI %COP_VER% installed (%COP_SOURCE%)" & exit /b 0)
call :fail "Copilot CLI installation did not succeed - open a new terminal and run: appmod doctor"
exit /b 0

:choose_installer
if defined INSTALLER exit /b 0
if "%YES%"=="1" (set "INSTALLER=winget" & exit /b 0)
echo.
echo   How should GitHub Copilot CLI be installed?
echo     [W] WinGet - standalone executable from Microsoft's package manager (recommended)
echo     [N] npm    - through the Volta-managed Node.js %APPMOD_NODE_MAJOR%
echo   uv is not offered: GitHub publishes Copilot CLI only for npm, WinGet, Homebrew and direct download.
"%CHOICE_EXE%" /c WN /m "  Your choice"
if errorlevel 2 (set "INSTALLER=npm") else (set "INSTALLER=winget")
exit /b 0

:copilot_info
set "COP_VER="
set "COP_PATH="
set "COP_SOURCE=unknown source"
for /f "tokens=4" %%v in ('copilot --version 2^>nul ^| findstr /b /c:"GitHub Copilot CLI"') do if not defined COP_VER set "COP_VER=%%v"
if defined COP_VER if "%COP_VER:~-1%"=="." set "COP_VER=%COP_VER:~0,-1%"
for /f "delims=" %%p in ('where copilot 2^>nul') do if not defined COP_PATH set "COP_PATH=%%p"
if not defined COP_PATH exit /b 0
rem substring tests (case-insensitive) instead of findstr, which treats backslashes as escapes
if not "%COP_PATH:\Volta\=%"=="%COP_PATH%" set "COP_SOURCE=npm"
if not "%COP_PATH:\npm\=%"=="%COP_PATH%" set "COP_SOURCE=npm"
if not "%COP_PATH:\WinGet\=%"=="%COP_PATH%" set "COP_SOURCE=WinGet"
exit /b 0

rem ---------------------------------------------------------------- Copilot sign-in
:ensure_login
call :step "GitHub Copilot sign-in"
if defined COPILOT_GITHUB_TOKEN (call :ok "COPILOT_GITHUB_TOKEN is set - Copilot uses it" & exit /b 0)
if defined GH_TOKEN (call :ok "GH_TOKEN is set - Copilot uses it" & exit /b 0)
if defined GITHUB_TOKEN (call :ok "GITHUB_TOKEN is set - Copilot uses it" & exit /b 0)
call :copilot_info
if not defined COP_VER (call :info "Copilot CLI not available yet - sign-in skipped" & exit /b 0)
if "%MODE%"=="check" (call :info "No token variable set. A stored sign-in cannot be checked offline - if Copilot asks you to log in, run: copilot login" & exit /b 0)
if "%SKIP_LOGIN%"=="1" (call :info "Sign-in skipped (--skip-login)" & exit /b 0)
if "%YES%"=="1" (call :info "Unattended run: sign in later with  copilot login  or set COPILOT_GITHUB_TOKEN" & exit /b 0)
call :ask "  Sign in to GitHub Copilot now? Answer N if you are already signed in"
if /i "%ANSWER%"=="N" (call :ok "Sign-in not requested" & exit /b 0)
call copilot login
if errorlevel 1 (call :fail "copilot login did not complete" & exit /b 0)
call :ok "Signed in"
exit /b 0

rem ---------------------------------------------------------------- app modernization plugin
:ensure_plugin
call :step "GitHub Copilot app modernization plugin (minimum %APPMOD_PLUGIN_MIN%)"
call :copilot_info
if not defined COP_VER (call :info "Skipped: needs Copilot CLI, see above" & exit /b 0)
call :marketplace_registered
if "%MK_OK%"=="1" (call :ok "Marketplace %MARKET_NAME% already registered") else (call :marketplace_add)
if not "%MK_OK%"=="1" exit /b 0
call :plugin_info
if not defined PL_VER goto :plugin_missing
call :vergeq "%PL_VER%" "%APPMOD_PLUGIN_MIN%"
if "%VERGEQ%"=="1" (call :ok "Plugin %PLUGIN_ID% v%PL_VER% already installed - nothing to do") else (call :plugin_outdated)
call :plugin_enable_if_needed
exit /b 0
:plugin_missing
if "%MODE%"=="check" (call :fail "Plugin %PLUGIN_ID% is not installed - run: appmod plugin install" & exit /b 0)
call :info "Installing %PLUGIN_ID% ..."
call copilot plugin install %PLUGIN_ID%
call :plugin_info
if defined PL_VER (call :ok "Plugin v%PL_VER% installed") else (call :fail "Plugin installation did not succeed")
call :plugin_enable_if_needed
exit /b 0

:plugin_outdated
if "%MODE%"=="check" (call :fail "Plugin v%PL_VER% is older than %APPMOD_PLUGIN_MIN% - run: appmod upgrade plugin" & exit /b 0)
call :info "Plugin v%PL_VER% is older than %APPMOD_PLUGIN_MIN% - updating ..."
call :upgrade_plugin_core
exit /b 0

:plugin_enable_if_needed
if /i not "%PL_ENABLED%"=="false" exit /b 0
if "%MODE%"=="check" (call :fail "Plugin is installed but disabled - run: copilot plugin enable %PLUGIN_ID%" & exit /b 0)
call copilot plugin enable %PLUGIN_ID%
call :ok "Plugin enabled"
exit /b 0

:marketplace_registered
set "MK_OK=0"
call copilot plugin marketplace list >"%TMPF%" 2>&1
findstr /i /c:"%MARKET_REPO%" "%TMPF%" >nul
if not errorlevel 1 set "MK_OK=1"
exit /b 0

:marketplace_add
if "%MODE%"=="check" (call :fail "Marketplace %MARKET_NAME% is not registered - run: appmod plugin install" & exit /b 0)
call :info "Registering marketplace %MARKET_REPO% ..."
call copilot plugin marketplace add %MARKET_REPO% >"%TMPF%" 2>&1
type "%TMPF%"
call :marketplace_registered
if "%MK_OK%"=="1" (call :ok "Marketplace registered") else (call :fail "Could not register the marketplace")
exit /b 0

:plugin_info
rem Reads "copilot plugin list --json": finds the object whose name is the plugin, keeps its version/enabled/source.
set "PL_VER="
set "PL_ENABLED="
set "PL_SOURCE="
set "_inplug="
call copilot plugin list --json >"%TMPF%" 2>nul
for /f "usebackq tokens=1,2 delims=:, " %%a in ("%TMPF%") do call :plugin_json_line "%%~a" "%%~b"
if /i "%PL_SOURCE%"=="builtin" set "PL_VER="
exit /b 0

:plugin_json_line
if "%~1"=="{" (set "_inplug=" & exit /b 0)
if "%~1"=="name" (
    if "%~2"=="%PLUGIN_NAME%" (set "_inplug=1") else (set "_inplug=")
    exit /b 0
)
if not defined _inplug exit /b 0
if "%~1"=="version" set "PL_VER=%~2"
if "%~1"=="enabled" set "PL_ENABLED=%~2"
if "%~1"=="source" set "PL_SOURCE=%~2"
exit /b 0

rem ---------------------------------------------------------------- JDK
:ensure_java
call :step "JDK %APPMOD_JAVA_MAJOR%+ (KitInstaller, the kit tools, the validators and the engine)"
call :find_java
if defined JAVA_EXE (call :ok "JDK %JAVA_VER% at %JAVA_DIR%" & call :use_java & exit /b 0)
if "%MODE%"=="check" (call :fail "No JDK %APPMOD_JAVA_MAJOR% or later found (checked JAVA_HOME, PATH, %%USERPROFILE%%\.jdks, Program Files, %TOOLS%)" & exit /b 0)
call :ask "  No JDK %APPMOD_JAVA_MAJOR%+ found. Download a portable Eclipse Temurin %APPMOD_JAVA_MAJOR% into %TOOLS% (no admin rights needed)?"
if /i "%ANSWER%"=="N" (call :fail "JDK skipped - the kit files cannot be installed" & exit /b 0)
if not exist "%TOOLS%" mkdir "%TOOLS%"
set "_zip=%WORK%\temurin-%APPMOD_JAVA_MAJOR%.zip"
call :info "Downloading Eclipse Temurin %APPMOD_JAVA_MAJOR% ..."
call curl -fL --retry 3 -o "%_zip%" "https://api.adoptium.net/v3/binary/latest/%APPMOD_JAVA_MAJOR%/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
if errorlevel 1 (call :fail "JDK download failed - check the proxy/network, or install a JDK %APPMOD_JAVA_MAJOR% and set JAVA_HOME" & exit /b 0)
call tar -xf "%_zip%" -C "%TOOLS%"
call :find_java
if defined JAVA_EXE (call :ok "JDK %JAVA_VER% installed at %JAVA_DIR%" & call :use_java & set "PATH_ADDITIONS=%PATH_ADDITIONS%;%JAVA_DIR%\bin" & exit /b 0)
call :fail "The downloaded JDK could not be used"
exit /b 0

:find_java
set "JAVA_EXE="
set "JAVA_DIR="
set "JAVA_VER="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" call :java_probe "%JAVA_HOME%\bin\java.exe"
if defined JAVA_EXE exit /b 0
for /f "delims=" %%p in ('where java 2^>nul') do if not defined JAVA_EXE call :java_probe "%%p"
if defined JAVA_EXE exit /b 0
call :scan_jdks "%USERPROFILE%\.jdks" "*"
call :scan_jdks "%TOOLS%" "jdk*"
for %%r in ("Eclipse Adoptium" "Microsoft" "Java" "Amazon Corretto" "Zulu") do call :scan_jdks "%ProgramFiles%\%%~r" "*"
exit /b 0

:scan_jdks
rem %1 = parent folder, %2 = folder pattern; newest name first (dir /o-n handles spaces in paths)
if defined JAVA_EXE exit /b 0
if not exist "%~1\" exit /b 0
for /f "delims=" %%d in ('dir /b /ad /o-n "%~1\%~2" 2^>nul') do if not defined JAVA_EXE if exist "%~1\%%d\bin\java.exe" call :java_probe "%~1\%%d\bin\java.exe"
exit /b 0

:java_probe
rem %1 = path to java.exe ; accepts it when its major version is at least APPMOD_JAVA_MAJOR
set "_jv="
set "_jm="
call "%~1" -version >"%TMPF%" 2>&1
for /f "usebackq tokens=3" %%v in (`findstr /i /c:" version " "%TMPF%"`) do if not defined _jv set "_jv=%%~v"
if not defined _jv exit /b 0
for /f "tokens=1,2 delims=._-" %%a in ("%_jv%") do (if "%%a"=="1" (set "_jm=%%b") else (set "_jm=%%a"))
if not defined _jm exit /b 0
if %_jm% LSS %APPMOD_JAVA_MAJOR% exit /b 0
set "JAVA_EXE=%~1"
set "JAVA_VER=%_jv%"
for %%f in ("%~dp1..") do set "JAVA_DIR=%%~ff"
exit /b 0

:use_java
set "JAVA_HOME=%JAVA_DIR%"
set "PATH=%JAVA_DIR%\bin;%PATH%"
exit /b 0

rem ---------------------------------------------------------------- Maven (reported by doctor when present)
:ensure_maven_optional
call :maven_version
if defined MVN_VER (call :step "Maven (optional)" & call :ok "Maven %MVN_VER% available")
exit /b 0

:maven_version
set "MVN_VER="
for /f "tokens=3" %%v in ('mvn -v 2^>nul ^| findstr /b /c:"Apache Maven"') do if not defined MVN_VER set "MVN_VER=%%v"
exit /b 0

rem ---------------------------------------------------------------- .NET SDK (SQL Server pack)
:ensure_dotnet_optional
call :dotnet_version
if defined DOTNET_VER (call :step ".NET SDK (optional, SQL Server pack)" & call :ok ".NET SDK %DOTNET_VER% available")
exit /b 0

:ensure_dotnet
call :step ".NET SDK %APPMOD_DOTNET_MAJOR%+ (SQL Server pack: builds .sqlproj)"
call :dotnet_version
if defined DOTNET_VER (call :ok ".NET SDK %DOTNET_VER% available" & exit /b 0)
if "%MODE%"=="check" (call :fail ".NET SDK %APPMOD_DOTNET_MAJOR%+ not found" & exit /b 0)
if not "%HAVE_WINGET%"=="1" (call :fail ".NET SDK missing - install it from https://dotnet.microsoft.com/download" & exit /b 0)
call :ask "  .NET SDK %APPMOD_DOTNET_MAJOR% not found. Install it with WinGet?"
if /i "%ANSWER%"=="N" (call :fail ".NET SDK skipped" & exit /b 0)
call winget install --id Microsoft.DotNet.SDK.%APPMOD_DOTNET_MAJOR% -e --silent --accept-source-agreements --accept-package-agreements
set "PATH=%ProgramFiles%\dotnet;%PATH%"
call :dotnet_version
if defined DOTNET_VER (call :ok ".NET SDK %DOTNET_VER% installed" & exit /b 0)
call :fail ".NET SDK installation did not succeed"
exit /b 0

:dotnet_version
set "DOTNET_VER="
for /f "tokens=1" %%v in ('dotnet --list-sdks 2^>nul') do call :dotnet_pick %%v
exit /b 0

:dotnet_pick
for /f "tokens=1 delims=." %%m in ("%~1") do if %%m GEQ %APPMOD_DOTNET_MAJOR% set "DOTNET_VER=%~1"
exit /b 0

rem ---------------------------------------------------------------- kit files
:install_kit
call :step "AppMod kit files into %WORKDIR%"
if not defined JAVA_EXE call :find_java
if not defined JAVA_EXE (call :fail "JDK %APPMOD_JAVA_MAJOR%+ is required to run KitInstaller" & exit /b 0)
if defined TOOL goto :kit_tool_known
if "%KIT_UPGRADE%"=="1" goto :kit_tool_known
if exist "%WORKDIR%\.appmod\kit.json" (set "KIT_UPGRADE=1" & goto :kit_tool_known)
if "%YES%"=="1" (set "TOOL=copilot" & goto :kit_tool_known)
"%CHOICE_EXE%" /c CLB /m "  Install the kit for GitHub Copilot (C), Claude Code (L) or both (B)?"
if errorlevel 3 (set "TOOL=both") else if errorlevel 2 (set "TOOL=claude") else (set "TOOL=copilot")
:kit_tool_known
set "_kitargs="
if "%WITH_MCP%"=="1" set "_kitargs=--with-mcp"
if "%FORCE%"=="1" set "_kitargs=%_kitargs% --force"
if defined TOOL if not "%KIT_UPGRADE%"=="1" set "_kitargs=%_kitargs% --tool %TOOL%"
if "%KIT_UPGRADE%"=="1" set "_kitargs=%_kitargs% --upgrade"
call :run_kit_installer %_kitargs%
if errorlevel 1 (call :fail "KitInstaller reported an error" & exit /b 0)
call :check_kit
exit /b 0

:run_kit_installer
pushd "%KIT_DIR%"
call "%JAVA_EXE%" install\KitInstaller.java --target "%WORKDIR%" %*
set "_rc=%errorlevel%"
popd
exit /b %_rc%

:check_kit
if exist "%WORKDIR%\.github\agents\appmod-harness.agent.md" (call :ok "Kit present in %WORKDIR% (entry agent: appmod-harness)" & exit /b 0)
if exist "%WORKDIR%\.claude\agents\security-fixer.md" (call :ok "Kit present in %WORKDIR% for Claude Code (agents in .claude\agents)" & exit /b 0)
call :fail "Kit not installed in %WORKDIR% - run: appmod kit %WORKDIR%"
exit /b 0

rem ---------------------------------------------------------------- upgrades
:upgrade_plugin
call :step "Upgrade: GitHub Copilot app modernization plugin"
call :copilot_info
if not defined COP_VER (call :fail "Copilot CLI is not installed - run: appmod init C:\path\to\repo" & exit /b 0)
call :marketplace_registered
if not "%MK_OK%"=="1" call :marketplace_add
if not "%MK_OK%"=="1" exit /b 0
call :plugin_info
if not defined PL_VER (call :info "Plugin not installed yet - installing" & call :ensure_plugin & exit /b 0)
call :upgrade_plugin_core
exit /b 0

:upgrade_plugin_core
set "_before=%PL_VER%"
call :info "Refreshing the %MARKET_NAME% catalog ..."
call copilot plugin marketplace update %MARKET_NAME% >nul 2>&1
call copilot plugin update %PLUGIN_ID%
call :plugin_info
if not defined PL_VER (call :fail "Plugin update failed" & exit /b 0)
if "%_before%"=="%PL_VER%" (call :ok "Plugin already at the latest version v%PL_VER%") else (call :ok "Plugin updated from v%_before% to v%PL_VER%")
call :vergeq "%PL_VER%" "%APPMOD_PLUGIN_MIN%"
if not "%VERGEQ%"=="1" call :fail "Plugin v%PL_VER% is still older than the required %APPMOD_PLUGIN_MIN%"
exit /b 0

:upgrade_copilot
call :step "Upgrade: GitHub Copilot CLI"
call :copilot_info
if not defined COP_VER (call :fail "Copilot CLI is not installed - run: appmod init C:\path\to\repo" & exit /b 0)
call :upgrade_copilot_by_source
exit /b 0

:upgrade_copilot_by_source
set "_before=%COP_VER%"
if /i "%COP_SOURCE%"=="npm" (call :info "Installed with npm - updating with npm ..." & call npm install -g @github/copilot@latest)
if /i "%COP_SOURCE%"=="WinGet" (call :info "Installed with WinGet - updating with WinGet ..." & call winget upgrade --id GitHub.Copilot -e --silent --accept-source-agreements --accept-package-agreements)
if /i "%COP_SOURCE%"=="unknown source" (call :info "Install source unknown - using copilot update ..." & call copilot update)
call :copilot_info
if "%_before%"=="%COP_VER%" (call :ok "Copilot CLI already at the latest version %COP_VER%") else (call :ok "Copilot CLI updated from %_before% to %COP_VER%")
exit /b 0

:upgrade_node
call :step "Upgrade: Node.js %APPMOD_NODE_MAJOR% and npm %APPMOD_NPM_MAJOR% through Volta"
call :volta_version
if not defined VOLTA_VER (call :fail "Volta is not installed - run: appmod init C:\path\to\repo" & exit /b 0)
call :volta_node_major
set "_before=%VNODE_VER%"
call volta install node@%APPMOD_NODE_MAJOR%
call volta install npm@%APPMOD_NPM_MAJOR%
call :volta_node_major
call :npm_version
call :ok "Node.js now %VNODE_VER% (was %_before%), npm %NPM_VER%"
exit /b 0

:add_known_paths
rem Tools installed earlier (also in this same terminal, before Windows refreshed PATH) are found at their usual places.
if exist "%ProgramFiles%\PowerShell\7\" set "PATH=%ProgramFiles%\PowerShell\7;%PATH%"
if exist "%ProgramFiles%\Git\cmd\" set "PATH=%ProgramFiles%\Git\cmd;%PATH%"
if exist "%ProgramFiles%\dotnet\" set "PATH=%ProgramFiles%\dotnet;%PATH%"
if exist "%LOCALAPPDATA%\Microsoft\WinGet\Links\" set "PATH=%LOCALAPPDATA%\Microsoft\WinGet\Links;%PATH%"
if exist "%TOOLS%\apache-maven-%APPMOD_MAVEN_VERSION%\bin\" set "PATH=%TOOLS%\apache-maven-%APPMOD_MAVEN_VERSION%\bin;%PATH%"
exit /b 0

:put_tools_on_path
call :put_volta_on_path
call :add_known_paths
exit /b 0

rem ---------------------------------------------------------------- user PATH / JAVA_HOME persistence
:persist_paths
if not defined PATH_ADDITIONS exit /b 0
if /i "%PERSIST%"=="N" (call :info "Portable tools were not added to your user PATH (--no-persist). They are in %TOOLS%" & exit /b 0)
call :step "Make portable tools available in new terminals"
set "_pa=%PATH_ADDITIONS:~1%"
if /i not "%PERSIST%"=="Y" call :ask "  Add %_pa:;=, % to your user PATH, and set JAVA_HOME if it is not set?"
if /i not "%PERSIST%"=="Y" if /i "%ANSWER%"=="N" (call :info "Not persisted. This session used them; future sessions: appmod init re-detects them in %TOOLS%" & exit /b 0)
call powershell -NoProfile -ExecutionPolicy Bypass -Command "$add='%PATH_ADDITIONS%'.Split(';')|?{$_}; $p=[Environment]::GetEnvironmentVariable('Path','User'); $parts=@($p -split ';'|?{$_}); foreach($a in $add){ if($parts -notcontains $a){ $parts+=$a } }; [Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User'); if('%JAVA_DIR%' -and -not [Environment]::GetEnvironmentVariable('JAVA_HOME','User')){ [Environment]::SetEnvironmentVariable('JAVA_HOME','%JAVA_DIR%','User') }"
if errorlevel 1 (call :fail "Could not update the user PATH" & exit /b 0)
call :ok "User PATH updated - open a new terminal to use them"
exit /b 0

rem ============================================================================================
rem  HELPERS
rem ============================================================================================

:resolve_workdir
if exist "%~1\" goto :resolve_workdir_ok
echo Working directory not found: %~1
exit /b 1
:resolve_workdir_ok
for %%f in ("%~1") do set "WORKDIR=%%~ff"
exit /b 0

:vergeq
rem VERGEQ=1 when version %1 is greater than or equal to version %2 (numeric, up to 4 parts)
set "VERGEQ=0"
call :verparts "%~1" A
call :verparts "%~2" B
if %A1% GTR %B1% (set "VERGEQ=1" & exit /b 0)
if %A1% LSS %B1% exit /b 0
if %A2% GTR %B2% (set "VERGEQ=1" & exit /b 0)
if %A2% LSS %B2% exit /b 0
if %A3% GTR %B3% (set "VERGEQ=1" & exit /b 0)
if %A3% LSS %B3% exit /b 0
if %A4% LSS %B4% exit /b 0
set "VERGEQ=1"
exit /b 0

:verparts
rem splits %1 into %2 1..4, dropping leading zeros so "08" is not read as octal
set "%~21=0" & set "%~22=0" & set "%~23=0" & set "%~24=0"
for /f "tokens=1-4 delims=.-+ " %%a in ("%~1") do (call :verpart %~2 1 %%a & call :verpart %~2 2 %%b & call :verpart %~2 3 %%c & call :verpart %~2 4 %%d)
exit /b 0

:verpart
set "_v=%~3"
if not defined _v exit /b 0
for /f "tokens=* delims=0" %%z in ("%_v%") do set "_v=%%z"
if not defined _v set "_v=0"
set /a "%~1%~2=%_v%" 2>nul
exit /b 0

:ask
rem Y/N question; --yes answers Y
if "%YES%"=="1" (set "ANSWER=Y" & exit /b 0)
"%CHOICE_EXE%" /c YN /m "%~1"
if errorlevel 2 (set "ANSWER=N") else (set "ANSWER=Y")
exit /b 0

:step
echo.
echo == %~1
exit /b 0

:ok
echo    [OK]   %~1
exit /b 0

:info
echo    [..]   %~1
exit /b 0

:fail
echo    [FAIL] %~1
set /a PROBLEMS+=1
exit /b 0
