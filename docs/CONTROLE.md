# NotToday Controle — especificação (proposta)

Estado: **proposta discutida, nada implementado.** Pontos que dependem de teste ou de conferência na documentação estão marcados com (?).

## Objetivo

Permitir que as pessoas de confiança (guardiões), em outras cidades, administrem os aparelhos protegidos e liberem domínios no Pi-hole **por um site (PWA)**, sem instalar nada e sem Tailscale. Hoje qualquer ação exige o PIN digitado no aparelho.

Escopo inicial: **2 guardiões, 2 aparelhos**. Pi-hole **v5** (migração para a v6 planejada; o desenho serve às duas).

## Modelo de ameaça

| Ameaça | Quem | Defesa principal |
|---|---|---|
| A. Burla pelo dono do aparelho | Usuário, que é desenvolvedor | Já tratada (README/SPEC): atraso, Device Owner, falha fechada. O canal remoto **não pode ser mais poderoso que o PIN**. |
| B. Invasão da rede doméstica pelo serviço exposto | Hackers e DDoS da internet | Gateway isolado (seção 9). Nada exposto roda no Pi principal nem alcança a LAN. |
| C. Gateway comprometido | Quem tomar o gateway | Ele não tem chaves nem acesso ao Pi-hole. Não forja comandos: aparelho e executor reverificam as assinaturas. |

Fora de alcance: guardião com a conta Google comprometida (passkeys sincronizados); JavaScript do site adulterado em um gateway tomado (mitigações na seção 11).

## Componentes

```
Navegador do guardião (PWA, passkey) ──HTTPS──┐
                                              ▼
NotToday (aparelho) ──long-poll──►  GATEWAY (container isolado, nó Tailscale próprio, Funnel)
                                              ▲
                          EXECUTOR (Pi, interno) ──puxa a fila (conexão de saída)──┘
                                 │
                                 └── Pi-hole v5 (domínios) 
```

- **Gateway**: serve o site e guarda a fila de comandos e o último relatório de cada aparelho (blobs). Valida estrutura, limites e assinaturas na entrada para barrar abuso barato. Não tem segredo algum.
- **Executor**: serviço no Pi que *puxa* os comandos de liberação de domínio, reverifica a assinatura do guardião e só então altera o Pi-hole.
- **NotToday**: consulta o gateway pela URL do Funnel (funciona com ou sem a VPN de pé), verifica cada comando e executa.

## Princípios

1. **O canal remoto nunca é mais poderoso que o PIN.** Comandos de configuração entram pelo mesmo caminho da tela: `Config.snapshot()` + `Gate.split()`. O que endurece vale na hora; o que afrouxa vira `Pending` com o `unlockDelayMin` do aparelho.
2. O atraso é contado pelo relógio do **aparelho** (`Clocks`, `Pending.status`). O relógio do gateway e do Pi não conta.
3. A "janela" por aparelho é o próprio `unlock_delay_min`. Reduzi-la é afrouxar e passa pelo atraso atual.
4. Imediatas, como hoje com o PIN: **aprovar app novo** e **endurecer**.
5. Qualquer um dos guardiões basta para afrouxar (que ainda passa pelo atraso). **Remover um guardião exige os dois.**

## Identidade e assinaturas

**Guardião: passkey (WebAuthn).**
- Registro com `userVerification: required`. Ao menos **2 passkeys por guardião** (celular + chave física).
- O `rpId` é o hostname do Funnel. Precisa ser estável: se mudar, os passkeys deixam de valer.
- **Cada comando é assinado pelo passkey**, com `challenge = SHA-256(bytes do comando)`. O toque biométrico autoriza aquela ação.
- O aparelho verifica a asserção: `rpIdHash`, flags UP+UV, `origin` e `challenge` em `clientDataJSON`, e a assinatura ES256 sobre `authData ‖ SHA-256(clientDataJSON)` (`SHA256withECDSA` do Java aceita a assinatura DER). A chave pública (COSE) é convertida para X.509 no pareamento.
- O `signCount` do WebAuthn **não é usado** (passkeys sincronizados costumam mandar 0).

**Aparelho: par P-256 no Android Keystore**, não exportável. Assina relatórios, pedidos e a autenticação das requisições ao gateway. Serve também para ECDH (cifrar o PIN, seção 8).

**Comando** (os bytes exatos são assinados, sem canonicalizar JSON):

```
{ "v":1, "dev":"<id do aparelho>", "guardian":"<id>", "ctr":N, "type":"...", "args":{...} }
```

- Por guardião, o aparelho guarda o maior `ctr` aceito e rejeita `ctr` menor ou igual (anti-replay). Persistido em armazenamento protegido por dispositivo, sobrevive a reinício.
- `dev` amarra o comando a um aparelho. Para dois aparelhos, o site assina N comandos (lote assinado: v2).
- Comando com duas assinaturas (remover guardião) leva `sigs:[...]` de guardiões distintos.

**Leitura pelo guardião**: login por passkey no gateway, sessão curta em memória. Os dados de estado não são caminho de controle, então o gateway pode vê-los.

## Comandos (v1)

| Comando | Efeito no aparelho |
|---|---|
| `set_config {patch}` | Passa por `Gate.split`. O que afrouxa vira `Pending(SETTINGS)`. |
| `confirm {kind}` | Confirma um `Pending` já `Ready` (settings, manutenção, remoção do DO). |
| `cancel {kind}` | Cancela um pedido pendente (qualquer guardião). |
| `approve_app {pkg}` / `revoke_app {pkg}` | `Approved.addAll` / `remove`. Na hora. |
| `request_maintenance` | Cria `Pending(MAINTENANCE)`. |
| `set_dev_policy {janelas, segundos, reboot}` | Ajusta a janela de desenvolvimento (afrouxar passa pelo `Gate`). |
| `set_pin {pin cifrado}` / `set_dev_pin {pin cifrado}` | Seção 8. |
| `add_guardian` / `remove_guardian` | Remover exige duas assinaturas e passa pelo atraso. Nunca remove o último. |
| `activate` | Fim do pareamento (seção 7). |

Cada comando executado gera um **relatório de auditoria assinado pelo aparelho**, mostrado no site e no push.

## Relatório do aparelho e alertas

Periódico e a cada evento: `filterActive` e motivo da última verificação (`Health`), apps pendentes (rótulo + `PackageGuard.risks`), `Pending` e janelas ativas (`Maintenance`, `DevWindow`), `Config.snapshot()`, Tailscale instalado, Device Owner, versão, fingerprint da chave.

Alertas (Web Push): **aparelho sem relatar há X min** (melhor sinal de burla), filtro caiu ou voltou, app novo pendente, pedido de afrouxar criado, remoção do DO solicitada, comando executado.

O NotToday também pode enviar um **pedido do usuário** ("quero acessar X", "quero instalar Y") assinado pela chave do aparelho. Não concede nada: só aparece para o guardião decidir.

## Pareamento remoto

Por padrão o pareamento acontece **antes** do `set-device-owner` (o `AdminReceiver` não tem como impedir um DO sem guardião: esse estado é o "travado" do LOCKOUT #12).

1. O guardião registra o passkey no **site verdadeiro** e gera uma **string de pareamento** longa: URL do gateway, chave pública do passkey e token único.
2. Envia por qualquer canal. O usuário cola no NotToday, que **fixa** a URL e a chave.
3. O aparelho se registra no gateway verdadeiro e mostra o **fingerprint** da própria chave.
4. Numa chamada de vídeo, o guardião vê o aparelho aparecer **no site verdadeiro** e confere o fingerprint lido na tela. Uma string adulterada deixa o aparelho fora do site verdadeiro, e o guardião percebe.
5. O guardião envia `activate`. **Só então** roda-se `set-device-owner`.
6. Depois de pareado, um novo pareamento só ocorre por comando assinado (como `firstPinAllowedAsOwner` hoje).

**Segundo guardião**: o primeiro assina `add_guardian` depois de conferir o fingerprint do segundo por voz. **Dois aparelhos**: cada um tem id e chave próprios, e o site os lista.

## PIN local

O PIN local continua, como fallback presencial. Dois caminhos para defini-lo:

- **No app (presencial)**: como hoje. Só é oferecido (a) enquanto o aparelho ainda não está pareado e o guardião está fisicamente presente, ou (b) para **trocar**, informando o PIN atual. Um PIN definido pelo usuário antes do pareamento remoto não vale: o pareamento remoto exige que o PIN seja definido pelo guardião (abaixo).
- **No site (remoto)**: comando `set_pin`. O navegador cifra o PIN para a chave do aparelho (ECDH P-256 efêmera + HKDF + AES-GCM) **dentro** do payload assinado. O gateway e o usuário nunca veem o PIN. O aparelho decifra com a chave do Keystore e grava com `PinStore.set` (PBKDF2 local). Tamanho mínimo: `PinStore.MIN_LENGTH`.
- O **PIN de desenvolvedor** ganha o caminho equivalente (`set_dev_pin`); o guardião o define e o comunica ao usuário por outro canal.
- Trocar o PIN é neutro (não afrouxa), mas exige o PIN atual ou uma assinatura de guardião.

## Gateway: isolamento e endurecimento

**Container**
- Imagem do Tailscale com `TS_USERSPACE=true` (sem `/dev/net/tun`, sem `NET_ADMIN`, sem `privileged`). Chave de autenticação efêmera com `tag:gateway`.
- Usuário não-root, `cap_drop: ALL`, `no-new-privileges`, rootfs somente leitura, `tmpfs` para temporários, sem socket do Docker, só um volume de dados.
- `cpus`, `mem_limit`, `pids_limit`. Logs com rotação e tamanho máximo, banco com teto, fila com tamanho máximo (evita encher o cartão SD).
- Imagem pinada por digest e reconstruída periodicamente. Poucas dependências.

**Rede**
- Rede Docker dedicada. Regras na cadeia `DOCKER-USER` bloqueiam o container para `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, o gateway do host e os serviços do Pi. Só sai para a internet. O Docker, por padrão, deixa o container alcançar a LAN: **esta regra é obrigatória**.
- ACL da tailnet: `tag:gateway` sem destino algum; atributo `funnel` **só** em `tag:gateway`. O Pi principal não consegue publicar nada.
- Funnel: portas 443/8443/10000, HTTPS ligado na tailnet; o hostname `*.ts.net` fica público nos logs de certificados.

**Aplicação**
- Corpos de poucos KB, timeouts curtos, nenhum upload, nenhuma desserialização dinâmica, nenhum template, estáticos em memória.
- Limite de taxa por IP e global **antes** de qualquer verificação criptográfica; teto de conexões de long-poll (uma por aparelho).
- Verifica assinaturas na entrada (rejeita barato); aparelho e executor reverificam.

**Funnel e DDoS**: o IP residencial não fica exposto e o tráfego volumétrico não o alcança; o limite real de taxa do Funnel (?) deve ser conferido na documentação.

**Risco residual**: container não é fronteira de VM. Reforços para depois: Docker/Podman rootless; outro hardware em VLAN de convidados.

## Executor e Pi-hole v5

O executor puxa os comandos do gateway, verifica a assinatura do passkey e aplica:

- **Liberar** domínio exato; **bloquear**; **liberação temporária** (prazo em SQLite próprio, removida por agendador que sobrevive a reinício).
- Regex ou curinga só com papel admin, e **só se não casar com o canário**. Um allow `.*` derrubaria o teste de saúde.
- **Recusa mexer no domínio-canário.**
- Validação estrita do domínio: ASCII (LDH) normalizado em minúsculas, até 253 caracteres, sem curinga, sem hífen inicial. Nada de shell: CLI com lista de argumentos (`pihole -w/-b`, regra de `sudoers` estreita) ou SQL parametrizado no `gravity.db` (tabela `domainlist`) seguido de `pihole restartdns reload-lists` (?).
- **Pedidos do usuário** e consultas bloqueadas recentes (do `pihole-FTL.db`, somente leitura) aparecem no site para o guardião liberar com um toque.
- A v5 não recebe mais atualizações: planejar a migração para a v6 (API REST + senha de aplicativo substituem o acesso ao banco).
- v2: grupos do Pi-hole para liberar um domínio só para um aparelho (cliente = IP do Tailscale).

## Transporte e notificações

- Aparelho → gateway: long-poll de ~25 s a `GET /v1/devices/{id}/commands`, no `GuardService`. Autenticação por requisição assinada com a chave do aparelho (timestamp + nonce).
- Se o Tailscale cair, o NotToday sai do `IncludedPackageNames` e usa a internet direta, mas o Funnel é público, então a recuperação continua possível.
- Guardião: Web Push (VAPID, `pywebpush`) na PWA instalada (Chrome Android; iOS 16.4+ instalada na tela inicial). Sem Firebase próprio. O push não carrega dados sensíveis; o site busca os detalhes pelo canal autenticado.

## Riscos e pontos em aberto

1. **JavaScript servido pelo gateway**: um gateway tomado poderia mostrar uma ação e fazer o passkey assinar outra. Camadas: CSP estrita, JS mínimo e sem dependências, **atraso ao afrouxar** (o pedido fica visível por 24 h e qualquer guardião cancela) e auditoria assinada pelo aparelho.
2. **Passkeys sincronizados**: a conta Google do guardião entra na cadeia de confiança. Exigir 2FA nela.
3. **Perda de passkey**: 2 passkeys por guardião; remover guardião exige os dois. Se um sumir, sobra o PIN local presencial.
4. Hostname do Funnel estável (o `rpId`).
5. Limite real de taxa do Funnel (?), comportamento do `reload-lists` (?), ECDH com chave do Keystore na One UI 8 (?).

## Estrutura e ordem de entrega

```
:protocol   Kotlin puro (JVM): modelo de comando, verificação ES256/WebAuthn, ECDH, testes e vetores fixos
:app        NotToday (depende de :protocol; RemoteChannel no GuardService)
pi/gateway  serviço Python + site PWA + Dockerfile/compose + exemplo de ACL
pi/executor serviço Python (Pi-hole) + unit systemd
```

1. `:protocol` e `pi/` com vetores de teste cruzados Kotlin↔Python (assinatura e ECDH).
2. Canal remoto no NotToday: pareamento, long-poll, relatório, `approve_app`, `set_config` pelo `Gate`, `set_pin`.
3. Site mínimo: estado, aprovar apps, pedidos pendentes, PIN.
4. Executor: liberar/bloquear, temporário, pedidos do usuário.
5. Web Push e alertas. Depois: lote assinado, grupos por aparelho, migração para o Pi-hole v6.
