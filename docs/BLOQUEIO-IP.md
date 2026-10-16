# Bloqueio de acesso por endereço IP (navegadores)

Problema: um site adulto aberto pelo número (`http://203.0.113.5`) não faz consulta DNS, então o Pi-hole nunca vê. A regra precisa
distinguir **navegador** de **app** (apps às vezes falam por IP e não podem quebrar). Só o próprio aparelho sabe quem está conectando
(na rede, as duas conexões são idênticas), então a regra fica **no navegador**, por política gerenciada.

## Como funciona
`app/src/main/assets/block-ip.pac` (o mesmo arquivo em `scripts/windows/block-ip.pac`; um teste confere que são iguais):
- IP público (v4 e v6, também em decimal `http://16843009/`, hexadecimal e octal) → proxy morto (`127.0.0.1:9`): a página não abre
  (`ERR_PROXY_CONNECTION_FAILED`). Sem `DIRECT` de reserva.
- Nome de domínio → direto (passa pelo Pi-hole).
- Redes locais (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16), Tailscale (100.64/10, `fd00::/8`) → abrem (painel do roteador, Pi-hole etc.).

Validado no Chrome do desktop com 20 casos (script de teste: Playwright, `--proxy-pac-url` com `data:`), todos corretos.

## Android (Tab S8, A56)
Regras → Chrome → **Bloquear acesso por endereço IP** (ligado por padrão). O app grava `ProxyMode=pac_script` e `ProxyPacUrl=data:…`
na configuração gerenciada do Chrome. Desligar espera o atraso (24 h) como as outras regras. **Não validado no Chrome do Android real:**
conferir em `chrome://policy` e abrindo `http://1.1.1.1`. Só vale no Chrome; outros navegadores só entram se o guardião aprovar o app
(o aviso na aprovação diz "navegador sem bloqueio de acesso por IP"). Apps comuns não são afetados.

## Windows (conta comum, administrador com a pessoa de confiança)
`scripts/windows/Bloquear-IP-Navegadores.ps1` (como Administrador; `-DryRun` só mostra; `-Remover` desfaz). Grava a política de
**Chrome, Edge e Brave** e desliga o DoH deles. Firefox, Opera, Vivaldi etc. **não** leem essas chaves: não instalar (conta comum
não instala programas, mas versões portáteis rodam da pasta do usuário: manter a pasta do usuário sem executáveis é outra camada).
Não usar em computador de trabalho (a política de proxy substitui o proxy da empresa).

## O que continua fora (TV, Xbox, outros aparelhos)
O Pi não está no caminho do tráfego, então não dá para bloquear IP na rede sem derrubar os apps. Para aparelhos sem o NotToday:
- **TV LG (webOS):** bloquear o app de navegador da Web com o PIN dos pais (Configurações → Segurança / Bloqueio de apps).
- **Xbox:** conta filha no Microsoft Family Safety (filtro de conteúdo e bloqueio do Edge) e senha na conta de administrador.

## Pi-hole: serviços que transformam IP em "nome"
`1.2.3.4.nip.io` resolve para `1.2.3.4` por DNS e escaparia da regra de IP; como é um nome, o Pi-hole pode bloquear. Regex deny:
```
^([a-zA-Z0-9-]+\.)*nip\.io$
^([a-zA-Z0-9-]+\.)*sslip\.io$
^([a-zA-Z0-9-]+\.)*xip\.io$
^([a-zA-Z0-9-]+\.)*traefik\.me$
```
