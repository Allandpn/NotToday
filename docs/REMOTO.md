# Guardião remoto (lado do aparelho)

O NotToday pode ser controlado pelas pessoas de confiança por um **site** (repositório
[guardian](https://github.com/Allandpn/guardian)) em vez de só pelo PIN no aparelho. O protocolo está em
`protocol/SPEC.md` daquele repositório; o desenho e as ameaças, em `docs/CONTROLE.md` dele.
Esta página descreve o que o aparelho faz. Código: `app/src/main/kotlin/com/allan/nottoday/remote/`.

## Princípio

**O canal remoto nunca é mais poderoso que o PIN.** Cada comando chega assinado por um passkey do guardião (WebAuthn),
é verificado no aparelho e executado pelos mesmos caminhos da tela:

| Comando | Efeito |
|---|---|
| `approve_app` / `revoke_app` | `Approved`, na hora (como aprovar com o PIN) |
| `set_config` / `set_dev_policy` | `Gate.split`: o que endurece vale na hora; o que afrouxa vira `Pending(SETTINGS)` e espera o atraso, **contado pelo relógio do aparelho** |
| `confirm` / `cancel` | confirma (só depois do atraso) ou cancela um pedido (`SETTINGS`, `MAINTENANCE`, `RELEASE`, `GUARDIAN`) |
| `request_maintenance` | cria `Pending(MAINTENANCE)` (ou abre a janela se o atraso é 0) |
| `set_pin` / `set_dev_pin` | o PIN chega **cifrado** para a chave do aparelho (ECDH + AES-GCM); o gateway e o usuário não o veem |
| `add_guardian` / `add_credential` | acrescenta quem o aparelho aceita |
| `remove_guardian` | exige a assinatura de **dois** guardiões e espera o atraso (`Pending(GUARDIAN)`); o outro guardião pode cancelar |
| `activate` | o guardião conferiu o fingerprint |

Valores de `set_config` passam por `Config.normalize` (booleanos só `true`/`false`, números em faixa, listas validadas):
`Config.applySnapshot` grava qualquer texto desconhecido como `false`, o que afrouxaria sem atraso.

## Fluxo de pareamento (remoto)

1. O guardião, no site: **Guardiões → Parear aparelho** e envia a string de pareamento.
2. No aparelho (antes de definir PIN e **antes do `set-device-owner`**): tela de PIN → **Parear com guardião remoto** → cola a string.
   O app mostra antes **quem poderá controlar o aparelho** (nomes dos guardiões e o gateway): confira com o guardião por voz, porque essa
   lista veio do gateway. Ao confirmar, fixa a URL, o `rpId` e os guardiões, registra a chave do aparelho (Android Keystore, P-256) e mostra o **fingerprint**.
3. O guardião vê o aparelho aparecer no site **verdadeiro** e confere o fingerprint **por voz**. Depois define o PIN pelo site
   (`set_pin`), ajusta o atraso e envia a ativação.
4. Só então: `adb shell dpm set-device-owner com.allan.nottoday/.AdminReceiver`.

Pareado e sem PIN, o aparelho mostra "Aguardando o guardião": **nenhum PIN pode ser criado localmente**, e a janela de
primeiro PIN do Device Owner não abre (senão o usuário criaria um PIN próprio). O PIN local continua existindo como
fallback presencial; trocar exige o PIN atual.

Na tela de bloqueio há **Pedir ao guardião** (domínio, app ou outro): vira um pedido no site, sem conceder nada.

## Canal

`RemoteChannel` (no `GuardService`; antes do Device Owner, só com a tela aberta) faz long-poll de 25 s no gateway e relata o
estado a cada 60 s e depois de cada comando. Toda requisição é assinada com a chave do aparelho (timestamp + nonce). O gateway
tem de ser **HTTPS** (a URL vem fixada na string de pareamento). Se o Tailscale cair, o app sai do `IncludedPackageNames`
e usa a internet direta; o Funnel é público, então o canal continua.

## Testes

```sh
./gradlew :app:testDebugUnitTest                                   # 49 testes; os 3 de integração são ignorados sem GUARDIAN_REPO
GUARDIAN_REPO=../guardian ./gradlew :app:testDebugUnitTest         # 52: + integração contra o gateway Python real (precisa de python3 + cryptography)
```

- `RemoteProtocolTest`: os casos de `vectors.json` (cópia de `protocol/vectors.json` do repositório guardian; inclui um envelope
  assinado por um passkey real do Chromium). Ao mudar o protocolo, copie o arquivo para `app/src/test/resources/`.
- `RemoteCommandsTest` (Robolectric): cada comando com SharedPreferences reais: `Gate`, atraso e validade, PINs, remover guardião, replay.
- `GatewayIntegrationTest`: pareamento, requisições assinadas, long-poll, comandos, PIN selado em Python e relatório, contra o gateway real.

**Não validado em aparelho** (sem emulador na nuvem): chave do Keystore com `PURPOSE_SIGN | PURPOSE_AGREE_KEY` e ECDH na One UI 8
(se recusar, separe em duas chaves e registre a de ECDH no relatório), as telas de pareamento, e o canal em segundo plano com o serviço de guarda.

## Relatório: rótulos e apps aprovados

O relatório enviado ao gateway inclui `labels`, `groups` e `hints` (nomes amigáveis das chaves de configuração, de `ConfigLabels`) e `approvedApps` (até 150 apps, ordenados por rótulo). O site usa isso na aba Regras.

## Capturas de tela (JVM)

`NOTTODAY_SHOTS=<dir> ./gradlew testDebugUnitTest --tests '*ScreenshotTest*'` gera PNGs das telas com Robolectric (tema "Mar e terra"). Sem a variável, o teste não roda.

## Pedir liberação de domínios

Na tela de bloqueio, **Algo foi bloqueado? Pedir liberação** abre a lista do que o Pi-hole bloqueou para este aparelho nas últimas 24 h (por padrão só as regras próprias; um interruptor mostra também anúncios). A pessoa marca os domínios e toca em **Pedir liberação**, depois em **Enviar link ao guardião** (folha de compartilhar do Android). Não há texto livre: o gateway só aceita domínios que o executor viu bloqueados. O pedido manual (app ou outro assunto) fica escondido atrás de um botão.

O canal consulta os pedidos abertos a cada ciclo e mostra uma notificação local quando o guardião responde (liberado, liberado por tempo ou negado). O pedido pode ser cancelado antes da resposta. O app pede a permissão de notificações ao abrir essa tela (Android 13 ou mais).
