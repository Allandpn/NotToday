<#
.SYNOPSIS
  Gera o APK de release assinado, confere que NAO e testOnly/debuggable e instala via ADB.
  Opcionalmente ativa como Device Owner (-SetOwner).

.EXAMPLE
  .\scripts\build-install.ps1                       # build + instala no unico aparelho conectado
  .\scripts\build-install.ps1 -Serial R5CX123       # escolhe o aparelho
  .\scripts\build-install.ps1 -SetOwner             # tambem roda dpm set-device-owner (pede confirmacao)
  .\scripts\build-install.ps1 -SkipInstall          # so gera e confere o APK
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$SetOwner,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$pkg = 'com.allan.nottoday'
$receiver = "$pkg/.AdminReceiver"
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'

function Fail($msg) { Write-Host "ERRO: $msg" -ForegroundColor Red; exit 1 }

# --- ferramentas ------------------------------------------------------------
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $sdk 'platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { Fail "adb nao encontrado (ANDROID_HOME/platform-tools)." }
$aapt2 = Get-ChildItem (Join-Path $sdk 'build-tools') -Filter aapt2.exe -Recurse |
    Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
if (-not $aapt2) { Fail "aapt2 nao encontrado em build-tools." }
$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) { Fail "keytool nao encontrado (instale um JDK ou ajuste o PATH)." }

# --- keystore (criado uma vez; FACA BACKUP FORA DO REPOSITORIO) -----------------
$props = Join-Path $root 'keystore.properties'
if (-not (Test-Path $props)) {
    New-Item -ItemType Directory -Force (Join-Path $root 'keystore') | Out-Null
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pass = ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', 'x')
    # keytool escreve o progresso no stderr; no PowerShell 5.1 isso vira erro com 'Stop'.
    $ErrorActionPreference = 'Continue'
    & $keytool -genkeypair -keystore (Join-Path $root 'keystore\release.jks') -alias nottoday `
        -keyalg RSA -keysize 3072 -validity 10000 -storepass $pass -keypass $pass -dname 'CN=NotToday' 2>&1 | Out-Null
    $keytoolExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($keytoolExit -ne 0 -or -not (Test-Path (Join-Path $root 'keystore\release.jks'))) { Fail "keytool falhou." }
    @(
        'storeFile=keystore/release.jks'
        "storePassword=$pass"
        'keyAlias=nottoday'
        "keyPassword=$pass"
    ) | Set-Content -Path $props -Encoding ASCII
    Write-Host "Keystore criado em keystore\ (+ keystore.properties)." -ForegroundColor Yellow
    Write-Host "FACA BACKUP dos dois fora do repo: sem eles voce nao consegue atualizar o app como Device Owner." -ForegroundColor Yellow
}

# --- build ------------------------------------------------------------------
& (Join-Path $root 'gradlew.bat') ':app:assembleRelease'
if ($LASTEXITCODE -ne 0) { Fail "Build falhou." }
if (-not (Test-Path $apk)) { Fail "APK nao encontrado: $apk (assinatura configurada?)" }

# --- confere o APK ------------------------------------------------------------
$manifest = (& $aapt2 dump xmltree --file AndroidManifest.xml $apk) -join "`n"
if ($manifest -match 'testOnly\(0x[0-9a-f]+\)=(true|\(type 0x12\)0xffffffff)') { Fail "APK esta testOnly=true." }
if ($manifest -match 'debuggable\(0x[0-9a-f]+\)=(true|\(type 0x12\)0xffffffff)') { Fail "APK esta debuggable." }
Write-Host "APK ok (nao testOnly, nao debuggable): $apk" -ForegroundColor Green

if ($SkipInstall) { exit 0 }

# --- instala ------------------------------------------------------------------
$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }
$devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" }
if (-not $devices) { Fail "Nenhum aparelho autorizado no adb." }
if (-not $Serial -and @($devices).Count -gt 1) { Fail "Mais de um aparelho: use -Serial <id> (adb devices)." }

& $adb @adbArgs install -r $apk
if ($LASTEXITCODE -ne 0) { Fail "adb install falhou." }

$flags = (& $adb @adbArgs shell dumpsys package $pkg) -join "`n"
if ($flags -match 'TEST_ONLY') { Fail "O aparelho registrou o app como TEST_ONLY." }
Write-Host "Instalado; sem flag TEST_ONLY no aparelho." -ForegroundColor Green

if (-not $SetOwner) {
    Write-Host "Para ativar: .\scripts\build-install.ps1 -SetOwner (leia docs\LOCKOUT.md antes)." -ForegroundColor Cyan
    exit 0
}

# --- Device Owner ---------------------------------------------------------------
Write-Host ""
Write-Host "Checklist antes de ativar (docs\LOCKOUT.md):" -ForegroundColor Yellow
Write-Host "  [ ] A pessoa de confianca ja abriu o app e definiu o PIN, e guardou."
Write-Host "  [ ] Testei o fluxo completo (ativar -> remover) num emulador."
Write-Host "  [ ] Sem contas no aparelho e sem usuarios extras; Test DPC removido."
Write-Host "  [ ] Backup feito (reset de fabrica e a unica saida se o PIN se perder)."
$ok = Read-Host "Digite SIM para ativar o Device Owner"
if ($ok -ne 'SIM') { Write-Host "Cancelado."; exit 0 }

& $adb @adbArgs shell dpm set-device-owner $receiver
if ($LASTEXITCODE -ne 0) { Fail "set-device-owner falhou (contas ou usuarios extras? ja existe outro Device Owner?)." }
& $adb @adbArgs shell dumpsys device_policy | Select-String -Pattern 'Device Owner' -Context 0, 3
Write-Host "Device Owner ativo. Abra o app e confira o status." -ForegroundColor Green
Write-Host "Build final? Entregue keystore\ e keystore.properties a pessoa de confianca e apague-os daqui." -ForegroundColor Yellow
