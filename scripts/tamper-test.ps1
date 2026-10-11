<#
.SYNOPSIS
  Tenta, pelo ADB, desfazer a protecao do app e mostra o que o sistema permitiu ou recusou.
  Rode DEPOIS de ativar o Device Owner, com a depuracao ainda ligada, para saber o que o ADB consegue.

  Nada aqui apaga dados (pm clear so com -IncludeClear, que zera o PIN: o app entra em "PIN ausente"
  e so um reset de fabrica desfaz; use apenas num aparelho de teste).

.EXAMPLE
  .\scripts\tamper-test.ps1 -Serial R5CX123
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$IncludeClear
)

$ErrorActionPreference = 'Continue'
$pkg = 'com.allan.nottoday'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $sdk 'platform-tools\adb.exe' }
$a = @()
if ($Serial) { $a += @('-s', $Serial) }

function Try-Adb($title, [string[]]$cmd) {
    Write-Host "`n== $title" -ForegroundColor Cyan
    Write-Host "   adb $($cmd -join ' ')"
    $out = & $adb @a @cmd | Out-String
    Write-Host ($out.Trim())
}

Try-Adb 'remover admin ativo (deve ser recusado: app nao e testOnly)' @('shell', 'dpm', 'remove-active-admin', "$pkg/.AdminReceiver")
Try-Adb 'desinstalar (deve ser recusado)' @('shell', 'pm', 'uninstall', '--user', '0', $pkg)
Try-Adb 'desabilitar (deve ser recusado)' @('shell', 'pm', 'disable-user', '--user', '0', $pkg)
Try-Adb 'forcar parada' @('shell', 'am', 'force-stop', $pkg)
if ($IncludeClear) {
    Try-Adb 'limpar dados (zera o PIN!)' @('shell', 'pm', 'clear', $pkg)
}
Try-Adb 'depuracao: o aparelho ainda responde ao ADB depois de ligar no_debugging_features?' @('devices')
Write-Host "`nConfira: Device Owner ainda ativo?" -ForegroundColor Yellow
& $adb @a shell dumpsys device_policy | Select-String -Pattern 'Device Owner' -Context 0, 2
