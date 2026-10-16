<#
.SYNOPSIS
  Bloqueia, no Chrome, Edge e Brave deste Windows, o acesso a sites pelo endereco IP (numero), que escaparia do filtro de DNS (Pi-hole).
  Roda como Administrador (a pessoa de confianca). Aplicativos comuns continuam livres para falar por IP: so os navegadores sao afetados.

.DESCRIPTION
  Grava a politica de proxy dos navegadores com o script block-ip.pac (o mesmo do app NotToday no Android):
    - IP publico (v4/v6, tambem em decimal, hexadecimal e octal) -> a pagina nao abre;
    - nomes de dominio -> passam direto (e pelo DNS filtrante);
    - rede local (10/8, 172.16/12, 192.168/16, 127/8), Tailscale (100.64/10, fd00::/8) -> continuam abrindo.
  Tambem desliga o DNS sobre HTTPS dos navegadores (com DoH eles ignoram o Pi-hole).
  Os navegadores passam a mostrar "Gerenciado pela sua organizacao"; o usuario comum nao consegue mudar essas opcoes.

  NAO use em computador de trabalho: a politica de proxy substitui o proxy da empresa.

.EXAMPLE
  .\Bloquear-IP-Navegadores.ps1 -DryRun      # so mostra o que seria gravado (nao precisa ser Administrador)
  .\Bloquear-IP-Navegadores.ps1              # aplica
  .\Bloquear-IP-Navegadores.ps1 -Remover     # desfaz (so as chaves que este script criou)
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Remover,
    [string]$PacFile
)

$ErrorActionPreference = 'Stop'
if (-not $PacFile) { $PacFile = Join-Path $PSScriptRoot 'block-ip.pac' }  # (no PowerShell 5.1 o $PSScriptRoot vazio no valor padrao do param)

# Navegadores baseados em Chromium e a chave de politica de cada um.
$browsers = [ordered]@{
    'Chrome' = 'HKLM:\SOFTWARE\Policies\Google\Chrome'
    'Edge'   = 'HKLM:\SOFTWARE\Policies\Microsoft\Edge'
    'Brave'  = 'HKLM:\SOFTWARE\Policies\BraveSoftware\Brave'
}
$managed = @('ProxyMode', 'ProxyPacUrl', 'DnsOverHttpsMode')  # so mexemos nestes valores

function Test-Admin {
    ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not $DryRun -and -not (Test-Admin)) {
    Write-Host 'ERRO: rode como Administrador (clique com o botao direito > Executar como administrador).' -ForegroundColor Red
    exit 1
}

if ($Remover) {
    foreach ($b in $browsers.GetEnumerator()) {
        foreach ($name in $managed) {
            $has = (Test-Path $b.Value) -and ($null -ne (Get-ItemProperty -Path $b.Value -Name $name -ErrorAction SilentlyContinue))
            if (-not $has) { continue }
            if ($DryRun) { Write-Host "[simulacao] removeria $($b.Key): $name"; continue }
            Remove-ItemProperty -Path $b.Value -Name $name -ErrorAction SilentlyContinue
            Write-Host "removido $($b.Key): $name"
        }
    }
    Write-Host 'Pronto. Feche e abra os navegadores.'
    exit 0
}

if (-not (Test-Path $PacFile)) {
    Write-Host "ERRO: nao achei o script PAC em $PacFile" -ForegroundColor Red
    exit 1
}
$pac = (Get-Content -Raw -Encoding UTF8 $PacFile).Replace("`r`n", "`n").Trim()
if ($pac -notmatch 'function FindProxyForURL') {
    Write-Host 'ERRO: o arquivo nao parece um script PAC (falta FindProxyForURL).' -ForegroundColor Red
    exit 1
}
$url = 'data:application/x-ns-proxy-autoconfig;base64,' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pac))

$values = [ordered]@{ ProxyMode = 'pac_script'; ProxyPacUrl = $url; DnsOverHttpsMode = 'off' }

foreach ($b in $browsers.GetEnumerator()) {
    if ($DryRun) {
        Write-Host "[simulacao] $($b.Key): $($b.Value)"
        foreach ($v in $values.GetEnumerator()) { Write-Host ("    {0} = {1}" -f $v.Key, ($v.Value.Substring(0, [Math]::Min(60, $v.Value.Length)))) }
        continue
    }
    if (-not (Test-Path $b.Value)) { New-Item -Path $b.Value -Force | Out-Null }
    foreach ($v in $values.GetEnumerator()) {
        New-ItemProperty -Path $b.Value -Name $v.Key -Value $v.Value -PropertyType String -Force | Out-Null
    }
    Write-Host "aplicado: $($b.Key)"
}

if ($DryRun) { Write-Host 'Simulacao: nada foi gravado.'; exit 0 }

Write-Host ''
Write-Host 'Pronto. Feche todas as janelas dos navegadores e abra de novo. Confira:'
Write-Host '  chrome://policy   (ProxyMode = pac_script)   |   edge://policy'
Write-Host '  Teste: http://1.1.1.1 deve falhar (ERR_PROXY_CONNECTION_FAILED); https://example.com deve abrir.'
Write-Host 'Atencao: Firefox, Opera, Vivaldi e outros nao leem estas chaves. Eles continuam abrindo IPs.'
Write-Host 'Mantenha so os navegadores acima instalados e a conta do usuario sem permissao de instalar programas.'
