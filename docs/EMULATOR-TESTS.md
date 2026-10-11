# Testes no emulador (Android 16 / API 36, AVD Google APIs x86_64, sem contas)

Build **release** (não `testOnly`, não depurável), 11/10/2026. Nada disto vale como prova para o One UI 8 da Samsung:
é o AOSP. O que depende do Tailscale/Pi-hole não foi testado (não há Tailscale no emulador).

## Resistência do Device Owner ao ADB (`scripts/tamper-test.ps1` + sondas extras)

| Tentativa pelo ADB (shell) | Resultado |
|---|---|
| `dpm remove-active-admin` | recusado: "Attempt to remove non-test admin" |
| `pm uninstall` / `cmd package uninstall -k` | `DELETE_FAILED_DEVICE_POLICY_MANAGER` |
| `pm disable-user` | recusado: "Cannot disable a protected package" |
| `am force-stop` | sem efeito (mesmo pid depois) |
| `pm clear` | `SecurityException` (sem `CLEAR_APP_USER_DATA`) |
| `adb install -r` / `-r -d` com outra chave | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| `run-as` | recusado: app não depurável |
| ler `/data/user_de/0/<pkg>` | `Permission denied` |
| `pm hide` | exige `MANAGE_USERS` |
| `pm suspend` / `pm unsuspend` | não mexe nas suspensões do DPC |
| `pm set-user-restriction` | exige `MANAGE_USERS` |
| `settings put global` `auto_time`, `private_dns_mode`, `always_on_vpn_app` | não pegou (restrições valem para o shell) |
| `settings put global adb_enabled` | só vale com `no_debugging_features` desligado |

Com `no_debugging_features` ligado pelo app, o ADB caiu em ~1 s.

## Mecânicas do app

- Device Owner ativado; restrições aplicadas; serviço de guarda sobe em primeiro plano; nenhuma falha ao abrir.
- **App novo** (outro pacote instalado por ADB): suspenso em < 1 s; `pm unsuspend` do shell não o libera; aprovar na tela o libera.
- **Janela de desenvolvimento** (PIN de dev): abre o ADB; fecha em 59–60 s; reinicia o aparelho (`dpm.reboot` funciona; `boot_count`
  sobe); `ADB_ENABLED` via `setGlobalSetting` funciona (sobe a 1 na janela, 0 antes do reinício). Limite de 3 por 24 h
  respeitado e persistente entre reinícios (a 4ª foi recusada: "Sem janelas disponíveis agora").
- **Remoção do Device Owner** pela tela (atraso 0): sem owners, sem restrições, nada suspenso, serviço de guarda parado.

## Pontos abertos

- **Exposição no reinício**: depois do reinício da janela de dev, o adbd respondeu por ~8–10 s antes do sistema aplicar
  `no_debugging_features`, mesmo com `adb_enabled=0`. Provável característica da imagem do emulador (adbd sobe cedo);
  **precisa ser medido no Tab S8** (`adb devices` em laço durante um reinício).
- Filtro, Tailscale (`IncludedPackageNames`, VPN sempre ativa), teste do canário, `setUserControlDisabledPackages` no One UI:
  só no Tab S8.
- `pm clear` com a janela de dev aberta foi recusado, mas o estado "PIN ausente" (fail-closed) segue sem teste de fumaça.

## Como repetir

`.\scripts\build-install.ps1 -Serial emulator-5554`; defina o PIN na tela; `adb shell dpm set-device-owner
com.allan.nottoday/.AdminReceiver`; `.\scripts\tamper-test.ps1 -Serial emulator-5554`. Para testar "app novo suspenso":
`.\gradlew.bat :app:assembleDebug -PtestApp` e instale `app\build\outputs\apk\debug\app-debug.apk` depois de definir a base.
