# Riscos de ficar preso (lockout) e mitigação

Regra de ouro: **só ative no aparelho real depois de ter ativado e removido o Device Owner com sucesso
num emulador** (AVD "Google APIs", sem contas, `adb install` do mesmo APK de release).

| # | Risco | Mitigação |
|---|-------|-----------|
| 1 | PIN perdido / pessoa de confiança inalcançável. A única saída vira reset de fábrica (recovery) e perda de dados. | Dois guardiões ou PIN em papel num cofre. Backup (Smart Switch/Google) **antes** de ativar. Não ative `no_factory_reset` até confiar no processo. |
| 2 | Bug no app (crash ao abrir, remoção que falha) e você não alcança a tela de remover. | Teste o ciclo completo no emulador com o APK de release. Mantenha o ADB liberado (`no_debugging_features` desligado) para poder reinstalar com `adb install -r`. |
| 3 | Keystore perdido: não dá para atualizar o app (assinatura diferente) e ele não pode ser desinstalado. | O script cria `keystore/` e `keystore.properties`; copie os dois para fora do repo (nunca versionar). |
| 4 | `no_debugging_features` ligado + app quebrado = sem ADB e sem UI. | Deixe por último. Ligue só com a versão final instalada e validada. |
| 5 | Filtro (Tailscale/Pi-hole) fora do ar: os apps marcados ficam suspensos (navegadores e os que você escolheu). | É o comportamento pedido; os demais apps seguem com internet. Para liberar com o Pi fora do ar, desligue o filtro (pede o atraso) ou conserte o Pi. |
| 6 | `no_config_vpn`, `no_config_wifi`, `no_modify_accounts`, `no_install_apps` impedem usos legítimos (VPN corporativa, Wi-Fi novo, login). | Os dois últimos e o Wi-Fi vêm desligados. Ligue só se aceitar o custo. |
| 7 | Instaladores de pacote e lojas alternativas suspensos. A Play Store continua liberada. | Libere pela janela de manutenção se precisar instalar um APK. |
| 8 | `no_config_date_time` com hora automática desligada: relógio errado preso. | O app liga hora e fuso automáticos ao aplicar. |
| 9 | `set-device-owner` recusado (contas/usuários extras, outro DO). | Remova o Test DPC, contas (Samsung, Google, Authenticator) e Pasta Segura/Mensageiro duplo antes. Depois do Device Owner ativo, contas podem voltar (se `no_modify_accounts` estiver desligado). |
| 10 | `clearDeviceOwnerApp` está `@Deprecated` no SDK 37 e o comportamento pode variar na One UI 8. | Validar a remoção no emulador **e** num aparelho de teste; o log da tela mostra cada passo. |
| 11 | O Samsung/Knox pode tratar algo de forma diferente do AOSP (ex.: perfil clone, Pasta Segura). | Aplicar e ler o log na tela; ajustar o catálogo. |
| 12 | Dados do app apagados (PIN some). | Falha fechada: com Device Owner ativo e sem PIN o app **não** deixa criar PIN novo nem reaplica o padrão; só reset de fábrica sai. Defina o PIN **antes** de ativar (há uma janela única se o DO for ativado sem PIN). Rode `scripts/tamper-test.ps1` para ver o que o ADB consegue. |
| 13 | Uma versão nova do Android quebra o app (VPN, DPM, UI). | Device Owner e restrições vivem no sistema e sobrevivem ao update e a crashes do app; o que se perde é a capacidade de *mudar* algo. Mitigue: ligue **Adiar atualizações do Android** (até 30 dias) e teste a versão nova antes; mantenha ADB ligado para `adb install -r` de um build corrigido (mesma assinatura); mantenha o targetSdk antigo até validar. Sem ADB e sem UI funcionando, só reset de fábrica. |
| 14 | Tailscale deslogado/chave expirada: sem VPN, apps marcados suspensos e o login por SSO precisa de navegador. | Faça o login antes de ativar o Device Owner e desative a expiração da chave. Se deslogar, só a pessoa dona da conta loga de novo. |
| 15 | Domínio-canário errado (não bloqueado no Pi, ou inexistente) = "filtro NÃO confirmado" para sempre ou falso "confirmado". | Use um domínio real, bloqueado como exato no Pi-hole, modo de bloqueio NULL/NXDOMAIN/NODATA. A tela mostra o motivo da última verificação. |
| 16 | Janela de segundos entre a queda do Tailscale e a suspensão (processo morto: até o próximo alarme). | Pequena; nela os apps marcados usam o DNS normal. O lockdown nativo exigiria o Pi como exit node (experimento futuro). |
| 17 | O atraso (24 h) vale também para você: bug que exija remover o Device Owner demora um dia. | É o objetivo; durante a validação defina atraso 0 e só aumente na versão final. Mantenha o ADB (sem `no_debugging_features`) até validar. |
| 18 | `no_debugging_features` pode não desligar uma depuração sem fio já autorizada, e o comportamento varia na One UI. | Depois de ativar, confira com `adb devices` e `scripts/tamper-test.ps1`. A janela de manutenção reabre o ADB (PIN + espera). |
| 19 | Keystore com você: dá para instalar uma versão alterada do DPC. | Entregue à pessoa de confiança depois do build final (ver README). |
| 20 | `no_modify_accounts` ficou **desligado** por padrão porque impede login de contas novas. | Ligue só se aceitar não adicionar contas depois. Os apps com conta já existente continuam. |
| 21 | Base de apps aprovada vazia/errada: apps legítimos suspensos (ou apps ruins aprovados). | Revise a lista com a pessoa de confiança antes de "Aprovar apps instalados agora"; o aprovar/revogar vale só com o PIN. |
| 22 | O serviço em primeiro plano não inicia (restrição de segundo plano) e o app novo demora até 1 min para ser suspenso. | O alarme de 1 min é a garantia; a tela mostra o último erro. Teste instalando um app no aparelho. |
| 23 | Janela de desenvolvimento: ADB aberto 60 s com um script pronto. | Reinício ao fim; só vale se o `tamper-test` mostrar que o shell não desfaz proteções. Reduza janelas/duração se necessário. |
| 24 | `reboot()` e `ADB_ENABLED` via `setGlobalSetting` funcionam no emulador (AOSP); na One UI 8 não foram testados. No emulador o adbd respondeu ~8–10 s durante o reinício da janela de dev. | Meça no Tab S8 (`adb devices` em laço durante o reinício). Veja docs/EMULATOR-TESTS.md. |

| 25 | **Pareado com Guardian remoto, sem PIN e o guardião nunca define o PIN** (ou o gateway fica fora do ar para sempre). Nenhum PIN local pode ser criado e, com o Device Owner ativo, só o reset de fábrica sai. | Pareie e deixe o guardião definir o PIN (`set_pin`) **antes** do `set-device-owner`. Antes do Device Owner dá para "Desfazer o pareamento" na própria tela. |
| 26 | O guardião-mor perde todas as passkeys (a conta Google/Apple dele também) ou some. | O guardião-mor cadastra 2+ passkeys (o site avisa até haver uma chave de reserva); o PIN local presencial continua valendo; sem ele, reset de fábrica. Guardiões de domínios não administram. |
| 27 | Gateway comprometido ou indisponível. | Não forja comandos (cada um é assinado por passkey) e o aparelho continua protegido; o que se perde é a capacidade de mudar algo remotamente. O PIN local segue funcionando. Veja `docs/REMOTO.md`. |
| 28 | Relógio do aparelho fora de ±5 min do gateway: as requisições assinadas são recusadas (401). | A política liga hora automática; o canal volta sozinho quando o relógio se corrige. |
| 29 | Guardião-mor só vale no trabalho (horário) e o computador do trabalho some ou a pessoa perde o emprego antes de trocar. | Troque a passkey ou crie outro guardião-mor antes de perder o acesso (veja docs/OPERACAO.md no guardian). Sem nenhuma passkey utilizável no horário, só o PIN local presencial e, sem ele, o reset de fábrica. |

## Ordem recomendada

0. Defina o atraso em 0 h durante a validação; suba para 24 h só na versão final.
1. Emulador: instalar → definir PIN → `-SetOwner` → verificar restrições → remover pelo app.
2. Tab S8 ou A56 (qual for menos crítico) com **depuração e reset ligados**, PIN já definido pela pessoa de confiança.
3. Uso real por alguns dias; só então considerar `no_debugging_features` e `no_factory_reset`.

## Observação sobre `testOnly`

Sem `testOnly`, o ADB **não** consegue remover o Device Owner (`dpm remove-active-admin` é recusado).
Isso é o objetivo, mas também significa que, durante o desenvolvimento, você só itera no emulador/aparelho
de teste, nunca no aparelho com restrições reais, a não ser via UI (PIN) ou `adb install -r` para atualizar.
