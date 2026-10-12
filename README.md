# NotToday

DPC (Device Policy Controller) próprio para usar como **Device Owner** em Android 14+ (alvo:
Android 16 / One UI 8 no Galaxy A56 e no Tab S8; **ainda não testado em aparelho**). Objetivo: guardrails pessoais contra
conteúdo adulto, com PIN, Pi-hole e conta do Tailscale guardados por uma pessoa de confiança.
A especificação completa e as decisões estão em [docs/SPEC.md](docs/SPEC.md).

> Antes de ativar num aparelho real, leia [docs/LOCKOUT.md](docs/LOCKOUT.md).

## Como funciona

- **O filtro é o seu servidor DNS** (o exemplo de referência é um Pi-hole num Raspberry, 24/7; serve também AdGuard Home ou
  qualquer DNS que devolva bloqueio como NXDOMAIN/`0.0.0.0`), alcançado pelo **Tailscale**. O app não gerencia domínios.
- O DPC faz o Tailscale ser a VPN **sempre ativa** (`setAlwaysOnVpnPackage`, sem diálogo e sem lockdown) e envia a
  configuração gerenciada `UseTailscaleDNSSettings=always` e `IncludedPackageNames` = **apps marcados** + o próprio NotToday.
  Só esses apps passam pelo Pi-hole; os demais usam a conexão direta. Não há `ForceEnabled`: você liga e desliga o
  Tailscale pelo app dele.
- **Apps marcados**: os navegadores (todos, exceto os que você isentar) e os que você escolher na tela.
- **Falha fechada**: os apps marcados ficam **suspensos** (`setPackagesSuspended`) sempre que o filtro não está confirmado.
  Confirmado = existe uma VPN de pé **e** o teste de saúde passa.
- **Teste de saúde**: o app resolve o **domínio-canário** pela rede da VPN (`DnsResolver.rawQuery`, o mesmo DNS que os apps
  marcados usam). O canário é um domínio **real** que a pessoa de confiança mantém bloqueado no Pi-hole; NXDOMAIN, resposta
  vazia ou só `0.0.0.0`/`::` = Pi-hole filtrando. IP de verdade, tempo esgotado ou erro = falha. Duas falhas seguidas
  suspendem; o "ok" expira em 150 s sem novas verificações. Roda a cada ~20 s (serviço de guarda), a cada evento de VPN
  (callback com PendingIntent) e a cada 1 min (alarme).
- **Play Store liberada, app novo bloqueado**: depois de definir a **base** (apps de usuário instalados e aprovados), todo
  app de usuário novo é suspenso logo após instalar (serviço em primeiro plano ouve `PACKAGE_ADDED`; o alarme é a garantia).
  A pessoa de confiança aprova com o PIN, sem atraso; a tela marca riscos (Shizuku, aShell, Test DPC, VPN, torrent,
  acessibilidade, navegador). Apps do sistema não entram; atualizações de apps aprovados passam direto.

## Estrutura

```
settings.gradle.kts, build.gradle.kts, gradle.properties   Gradle (AGP 9.4.1, Gradle 9.6)
scripts/build-install.ps1      build release + confere + instala (+ set-device-owner)
scripts/tamper-test.ps1        tenta burlar a proteção pelo ADB e mostra o que funcionou
docs/SPEC.md, docs/LOCKOUT.md  especificação e riscos de ficar preso
app/src/main/kotlin/com/allan/nottoday/
  AdminReceiver.kt   onEnabled → aplica o estado padrão logo após o set-device-owner
  BootReceiver.kt    BOOT_COMPLETED / MY_PACKAGE_REPLACED → reaplica
  PolicyManager.kt   apply(), heartbeat(), quickSync(), suspensões, Tailscale, janela de desenvolvimento
  GuardService.kt    serviço em primeiro plano: pacotes novos, saúde a cada ~20 s, fim da janela de dev
  Health.kt/DnsProbe.kt   teste do canário e interpretação da resposta DNS (testado)
  PackageGuard.kt    base aprovada, apps pendentes, riscos, apps enviados ao Pi-hole
  Gate.kt/Guard.kt   anti-impulso (atraso), janelas de manutenção e de desenvolvimento
  VpnWatch.kt/Watchdog.kt   eventos de VPN e alarme de 1 min
  Config.kt, PinStore.kt, Browsers.kt, MainActivity.kt
```

Sem dependências além do SDK. minSdk 34, targetSdk 36, compileSdk 37.

## Preparação (antes do Device Owner)

1. **Tailscale**: instale o app no aparelho e faça **login** (a pessoa de confiança é dona da conta/tailnet). Desative a
   expiração da chave do aparelho. Na ACL, limite o aparelho a falar só com o Pi (porta 53), sem exit node.
2. **DNS da tailnet**: nameserver global = IP Tailscale do Pi-hole, com *Override DNS servers*. **Sem** nameserver público de
   reserva: se o Pi cair, os apps marcados ficam sem resolver (e o teste suspende-os); os demais seguem direto.
3. **Pi-hole**: aceite consultas vindas de `tailscale0`; modo de bloqueio NULL, NXDOMAIN ou NODATA (não o modo "IP"). Cadastre
   o **domínio-canário** como bloqueio exato: um domínio real e permanente (que resolveria se não fosse bloqueado).
4. Remova o Test DPC (`adb shell dpm remove-active-admin com.afwsamples.testdpc/.DeviceAdminReceiver`, ou pela tela dele),
   contas extras e usuários extras (Pasta Segura, Mensageiro duplo) — exigência do `set-device-owner` via ADB.
5. Ative as Opções do desenvolvedor e **autorize a chave RSA do seu PC** ("Sempre permitir") antes de fechar a depuração.

## Fluxo de uso

1. `.\scripts\build-install.ps1` — gera o APK de release (cria o keystore na 1ª vez), confere que não é
   `testOnly`/`debuggable`, instala e confere no aparelho que não há `TEST_ONLY`.
2. **A pessoa de confiança abre o app e define o PIN** (e o atraso). Nas configurações: domínio-canário, apps enviados ao
   Pi-hole, **base de apps aprovados** e, se quiser, o PIN de desenvolvedor.
3. `.\scripts\build-install.ps1 -SetOwner` — checklist, confirmação e `dpm set-device-owner com.allan.nottoday/.AdminReceiver`.
4. Confirme na tela: "Filtro: CONFIRMADO". No app do Tailscale confira "gerenciado por NotToday".
5. Rode `scripts\tamper-test.ps1` e só então ligue `no_debugging_features` (por último).
6. Saída legítima: app → PIN → **Remover Device Owner** (com atraso; desfaz tudo e chama `clearDeviceOwnerApp`).

Conferir manualmente: `adb shell dumpsys device_policy` e
`adb shell dumpsys package com.allan.nottoday | findstr TEST_ONLY` (não deve aparecer nada).

## Restrições

Os padrões miram só o que tira o filtro com poucos toques; o resto fica livre para não gerar atrito.

- **Ligadas por padrão** (cada uma fecha um desvio do filtro): DNS privado, VPN, modo seguro, novos usuários, perfis
  gerenciados, Espaço Privado, perfil clone, fontes desconhecidas (+ global), reset de rede, data/hora e **reset de fábrica
  pelas Configurações** (desligue se quiser essa saída para PIN perdido).
- **Desligadas por padrão** (atrito sem ganho para o filtro): desinstalar/parar/limpar dados de qualquer app (o NotToday, o
  Tailscale e os "apps protegidos" já têm proteção própria via `setUninstallBlocked` e `setUserControlDisabledPackages`),
  hotspot/tethering/Wi-Fi Direct, USB de arquivos, instalar apps, contas, rede móvel/APN, Wi-Fi, troca de usuário e a
  **depuração USB** (ligue por último).
- **Lojas alternativas** (Galaxy Store, GetApps, AppGallery, F-Droid etc. + as suas, em "lojas extras"): suspensão opcional,
  desligada por padrão; com "suspender apps novos" ligado ela é redundante.
- Tudo alternável na tela; para acrescentar uma restrição, inclua uma linha em `Catalog.restrictions`.

## Anti-impulso

- **Atraso de desbloqueio** (definido ao criar o PIN; padrão 24 h; 0 só para testes). Tudo que *afrouxa* a proteção vira um
  **pedido**: desligar restrição ou filtro, trocar o canário, tirar app do filtro, isentar navegador, desligar a suspensão de
  apps novos, baixar o nível do YouTube/SafeSearch/DoH do Chrome, aumentar as janelas de desenvolvimento, diminuir o atraso, remover o Device Owner. Só confirma depois do atraso
  e vale mais 24 h. Endurecer vale na hora. **Aprovar app novo** só pede o PIN.
- Contagem: relógio monotônico no mesmo boot; relógio de parede só com hora automática + `no_config_date_time`.
- **Janela de manutenção** (30 min, com atraso): libera ADB, instalar apps, fontes desconhecidas, USB e lojas.
- **Janela de desenvolvimento**: o **PIN de desenvolvedor** (seu; só a pessoa de confiança o define) abre **apenas o ADB**
  na tela de bloqueio: 3 vezes por 24 h, 60 s cada (ajustável), e **reinicia o aparelho ao fim** (`reboot()`; mata processos
  de shell estilo Shizuku). A contagem não zera com reinício. Só vale com `no_debugging_features` ligado.
- **Vigia**: serviço de guarda (~20 s) + alarme (1 min) + reaplicação completa a cada 5 min, no boot e ao abrir o app.
- **O app não trava na abertura**: política em thread própria com `try/catch` por passo; erro de tela mostra a mensagem e volta ao PIN.
- **Chrome** (melhor esforço, configuração gerenciada, `chrome://policy`), cada item na tela: DoH desligado (recomendado:
  senão o Chrome ignora o DNS filtrante), SafeSearch, YouTube restrito (desligado/moderado/estrito; padrão moderado) e
  bloqueio do modo anônimo (padrão desligado: o anônimo não escapa do DNS).

## Desenvolvimento sem comprometer a proteção

1. Dia a dia: emulador do Android Studio (pode ser Device Owner de teste, sem conta).
2. Levar versões aos aparelhos: Play Console, teste interno (fontes desconhecidas ficam bloqueadas). O app é aprovado uma vez.
3. Depurar em aparelho real: janela de desenvolvimento.
4. Atualizar o próprio NotToday: janela de manutenção + a pessoa de confiança assina o APK (o keystore fica com ela).

## Keystore

Quem tem a chave instala por cima uma versão alterada do DPC. Depois do **build final**, entregue `keystore/` e
`keystore.properties` à pessoa de confiança (duas cópias com ela) e apague todas as suas (incluindo caches e nuvem).

## O que isto NÃO cobre

- Navegadores embutidos (WebView em apps, abas dentro de redes sociais) e apps não marcados: conexão direta.
- Janela de alguns segundos entre a queda do Tailscale e a suspensão dos apps marcados (eles vão direto, sem filtro).
- Reset pelo recovery/Odin (apaga os dados e remove o Device Owner); mitigar com a conta Google/Samsung da pessoa de confiança.
- O que o ADB consegue enquanto está aberto (janela de dev ou manutenção): meça com `scripts/tamper-test.ps1`.
- DoH embutido em navegadores que não sejam o Chrome: mantenha-os fora da base aprovada ou isentos.
- Nada disso foi validado num aparelho: pontos incertos estão marcados em docs/SPEC.md com (?).
