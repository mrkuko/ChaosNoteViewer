<#
.SYNOPSIS
  Installs everything needed and builds the Windows desktop (tray) app into desktop\dist.

.DESCRIPTION
  Installs missing tools with winget (Python, CMake, Ninja, Visual Studio C++ Build Tools), downloads
  Qt with aqtinstall (pip), then configures, builds and runs windeployqt.
  Safe to re-run: anything already installed is skipped.
  The Visual Studio Build Tools install needs administrator rights (Windows asks once).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\build-windows.ps1
  powershell -ExecutionPolicy Bypass -File .\build-windows.ps1 -QtVersion 6.8.3 -QtDir D:\Qt
#>
param(
    [string]$QtVersion = '6.8.3',
    [string]$QtDir     = 'C:\Qt',
    [string]$QtArch    = 'win64_msvc2022_64',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# winget installs change PATH in the registry only; pull it into this session
function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user    = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machine;$user"
}

# true if the command exists and is a real program (not the Microsoft Store python stub)
function Test-Tool($name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $cmd) { return $false }
    if ($cmd.Source -like '*\WindowsApps\*') { return $false }
    & $cmd.Source --version *> $null
    return ($LASTEXITCODE -eq 0)
}

function Install-WithWinget($id, $override = $null) {
    $wingetArgs = @('install', '--id', $id, '-e', '--silent',
                    '--accept-package-agreements', '--accept-source-agreements')
    if ($override) { $wingetArgs += @('--override', $override) }
    & winget @wingetArgs
    # 0x8A150061 / -1978335189 etc. mean "already installed / nothing to do"; real failures are checked afterwards
    Update-SessionPath
}

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw 'winget not found. Install "App Installer" from the Microsoft Store and run this again.'
}

# --- 1. tools ---------------------------------------------------------------------------------
Step 'Python'
if (-not (Test-Tool python)) {
    Install-WithWinget 'Python.Python.3.12'
    if (-not (Test-Tool python)) { throw 'Python install failed (open a new terminal and re-run if it was just installed).' }
}
python --version

Step 'CMake'
if (-not (Test-Tool cmake)) {
    Install-WithWinget 'Kitware.CMake'
    if (-not (Test-Tool cmake)) {
        $p = 'C:\Program Files\CMake\bin'
        if (Test-Path $p) { $env:Path += ";$p" } else { throw 'CMake install failed.' }
    }
}
cmake --version | Select-Object -First 1

Step 'Ninja'
if (-not (Test-Tool ninja)) {
    Install-WithWinget 'Ninja-build.Ninja'
    if (-not (Test-Tool ninja)) { throw 'Ninja install failed (open a new terminal and re-run).' }
}
ninja --version

Step 'Visual Studio C++ compiler (MSVC)'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
function Get-VsPath {
    if (-not (Test-Path $vswhere)) { return $null }
    & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
}
$vs = Get-VsPath
if (-not $vs) {
    Write-Host 'No MSVC C++ tools found, installing Visual Studio 2022 Build Tools (large download, a few minutes)...'
    Install-WithWinget 'Microsoft.VisualStudio.2022.BuildTools' `
        '--quiet --wait --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended'
    $vs = Get-VsPath
    if (-not $vs) { throw 'MSVC install failed. Install "Desktop development with C++" in the Visual Studio Installer and re-run.' }
}
Write-Host "Using $vs"

# --- 2. Qt ------------------------------------------------------------------------------------
Step "Qt $QtVersion ($QtArch)"
$qtPrefix = Join-Path $QtDir "$QtVersion\$($QtArch -replace '^win64_', '')"
if (-not (Test-Path (Join-Path $qtPrefix 'bin\windeployqt.exe'))) {
    python -m pip install --quiet --upgrade aqtinstall
    if ($LASTEXITCODE -ne 0) { throw 'pip install aqtinstall failed.' }
    python -m aqt install-qt windows desktop $QtVersion $QtArch -O $QtDir
    if ($LASTEXITCODE -ne 0) { throw "Qt download failed. Check the version with: python -m aqt list-qt windows desktop" }
}
if (-not (Test-Path (Join-Path $qtPrefix 'bin\windeployqt.exe'))) { throw "Qt not found at $qtPrefix" }
Write-Host "Qt at $qtPrefix"

# --- 3. MSVC environment in this session ------------------------------------------------------
Step 'Loading MSVC environment'
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
cmd /c "`"$vcvars`" >nul && set" | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2] }
}

# --- 4. build ---------------------------------------------------------------------------------
Step 'Configure and build'
if ($Clean -and (Test-Path build)) { Remove-Item -Recurse -Force build }
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release "-DCMAKE_PREFIX_PATH=$qtPrefix"
if ($LASTEXITCODE -ne 0) { throw 'cmake configure failed.' }
cmake --build build
if ($LASTEXITCODE -ne 0) { throw 'build failed.' }

# --- 5. package -------------------------------------------------------------------------------
Step 'Collecting Qt DLLs into dist\'
if (Test-Path dist) { Remove-Item -Recurse -Force dist }
New-Item -ItemType Directory dist | Out-Null
Copy-Item build\chaosnoteviewer.exe dist\
& (Join-Path $qtPrefix 'bin\windeployqt.exe') --release --qmldir qml dist\chaosnoteviewer.exe
if ($LASTEXITCODE -ne 0) { throw 'windeployqt failed.' }

Write-Host "`nDone: $PSScriptRoot\dist\chaosnoteviewer.exe" -ForegroundColor Green
