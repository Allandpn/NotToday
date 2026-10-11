# NotToday — especificação

Estado: **implementada no código, sem teste em aparelho** (testes unitários e build release passam). Desvio da especificação: não há
"IP do Pi" configurável; o teste de saúde consulta o canário pela rede da VPN (`DnsResolver.rawQuery`), que prova o DNS que os apps
marcados realmente usam.
Nada aqui foi validado num aparelho. Marcado com (?) o que depende de teste.

## Objetivo
DPC próprio (Device Owner) em Samsung A56 e Tab S8 (Android 16 / One UI 8) como guardrail pessoal contra conteúdo adulto.
A pessoa de confiança guarda o PIN, a conta do Tailscale, o Pi-hole e o keystore. O proprietário do aparelho é desenvolvedor,
então a proteção tem de resistir ao próprio usuário, não só a um usuário comum.

## Arquitetura de filtragem
- Sem filtro de DNS no app. O filtro é o **Pi-hole** (24/7, em caixa trancada com a pessoa de confiança), alcançado pelo **Tailscale**.
- O app impõe o Tailscale como VPN sempre ativa (`setAlwaysOnVpnPackage`, sem lockdown) e envia a configuração gerenciada:
  `UseTailscaleDNSSettings=always`, `IncludedPackageNames` = apps marcados + o próprio NotToday. **Sem** `ForceEnabled`:
  o usuário liga/desliga o Tailscale no app dele. Exit node e acessos: ACL da tailnet, com a pessoa de confiança.
- **Apps marcados** = os que passam pelo Pi. Os navegadores vêm marcados; o usuário escolhe os demais na tela (com PIN).
  Marcar vale na hora; desmarcar (inclusive navegador) espera o atraso e a tela avisa que fica sem filtro.
- Demais apps: conexão direta, sem VPN.

## Falha fechada (fase 1: suspensão)
- Apps marcados são **suspensos** (`setPackagesSuspended`) sempre que o filtro não está confirmado. Voltam quando confirma.
- **Teste de saúde**: o app resolve, pela rede da VPN, um domínio-canário que a pessoa de confiança mantém
  bloqueado no Pi-hole. Resposta bloqueada (0.0.0.0 ou NXDOMAIN) = filtro ok. Timeout ou IP real = suspende.
  Exige 2–3 falhas seguidas antes de suspender; volta na primeira confirmação.
- Gatilhos: evento de rede/VPN (callback com PendingIntent), vigia de 1 min, aplicação completa a cada 5 min.
- O domínio-canário (um domínio REAL, bloqueado como exato no Pi-hole) fica na tela com PIN; trocá-lo espera o atraso.
- Janela de exposição: alguns segundos entre a queda do Tailscale e a suspensão (apps marcados vão direto sem filtro).
- Experimento posterior (não é a fase 1): lockdown + Pi como exit node para "abre, mas sem internet" (?).

## Play Store e apps de contorno
- Play Store liberada (atualizações). Fontes desconhecidas e lojas alternativas bloqueadas.
- **Negar por padrão**: base aprovada = apps instalados na configuração, revisados com a pessoa de confiança.
  Todo pacote novo é **suspenso** logo após instalar (serviço em primeiro plano ouve a instalação; varredura de 1 min como garantia (?)).
- A pessoa de confiança aprova com o PIN, sem atraso. Atualizações de apps aprovados passam direto.
- Ferramentas de alto risco (Shizuku, aShell, Hail, Test DPC, VPNs, torrents) aparecem sinalizadas na tela de aprovação.

## Anti-impulso
- Mudanças que afrouxam esperam o atraso (padrão 24 h, definido na criação do PIN); as que endurecem valem na hora.
- Relógio: monotônico no mesmo boot; parede só com hora automática + `no_config_date_time`.
- Janela de manutenção completa (30 min, com atraso): levanta depuração, instalação, fontes desconhecidas, USB e suspensão das lojas.
- Remoção do Device Owner: pedido com atraso, tela final, `clearDeviceOwnerApp`.
- PIN: PBKDF2, 5 tentativas livres, depois espera crescente.

## Janela de desenvolvimento (ADB curto)
- Um **PIN de desenvolvedor** (do usuário) abre só o ADB: **3 janelas por 24 h, 60 s cada** (valores ajustáveis só com o PIN da
  pessoa de confiança; afrouxar espera o atraso). Contagem persistente, que não zera com reinício.
- **Ao fim de cada janela o DPC reinicia o aparelho** (`DevicePolicyManager.reboot`) para matar processos de shell (estilo Shizuku) (?).
  Desligar o reinício é afrouxar (atraso).
- Só vale se o `tamper-test` mostrar que o shell não desfaz proteções. Se mostrar, vira a janela de manutenção (PIN da pessoa, 24 h).
- Autorize a chave RSA do Android Studio no aparelho **antes** de fechar a depuração.
- Primeira instalação de um app seu = pacote novo (fica suspenso até a aprovação); atualizações seguintes entram direto.
- Estado final nos aparelhos protegidos: `DISALLOW_DEBUGGING_FEATURES` ligado (último passo, depois do tamper-test).

## Fluxo de desenvolvimento
1. Dia a dia: emulador do Android Studio (também pode ser Device Owner de teste; sem conta).
2. Levar versões aos aparelhos: Play Console, teste interno (única via com fontes desconhecidas bloqueadas).
3. Depurar em aparelho real: janela de desenvolvimento.
4. Atualizar o próprio NotToday: janela de manutenção + a pessoa de confiança assina o APK (keystore com ela, duas cópias).
   Depois de entregar, apagar toda cópia local (`keystore/`, `keystore.properties`, caches, nuvem).

## Proteção do app
- Não `testOnly`; release não depurável. Sem o keystore, nada instala por cima.
- `setUninstallBlocked`, `setUserControlDisabledPackages` (?), restrições reaplicadas no boot e a cada minuto.
- Fora de alcance: factory reset por recovery/Odin (mitigar com FRP/conta da pessoa de confiança), acesso físico ao Pi
  (trancado), WebViews e navegadores embutidos em apps não marcados, tráfego não-DNS.

## Mudanças em relação ao código atual
Remover: `DnsFilterVpnService`, `DnsPacket`, `DomainRules`, `ListStore` (listas, importação, log), modo estrito, upstreams,
modo local. Manter: restrições, `Gate`, PIN, janela de manutenção, `Watchdog`, `VpnWatch`.
Adicionar: teste de saúde, seleção de apps, base aprovada + suspensão de pacotes novos, janela de desenvolvimento com reinício,
política do Tailscale sem `ForceEnabled` e com `IncludedPackageNames`.
Primeiro marco: rodar `scripts/tamper-test.ps1` num emulador com o build release como Device Owner.

## Nome e identificadores
Nome: **NotToday**. `applicationId` e namespace: `com.allan.nottoday`. Componente do Device Owner:
`com.allan.nottoday/.AdminReceiver`.
