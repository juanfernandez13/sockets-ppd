# Dara — Jogo de Tabuleiro via Sockets TCP

Implementação do jogo **Dara** (estratégia africana de origem nigeriana) para dois jogadores em máquinas diferentes, comunicando-se via **sockets TCP** em Java.

---

## Sobre o Jogo

| Propriedade       | Valor                                   |
|-------------------|-----------------------------------------|
| Tabuleiro         | 5 linhas × 6 colunas (interseções)      |
| Peças por jogador | 12                                      |
| Condição de vitória | Oponente fica com apenas 2 peças      |
| Fases             | Colocação → Movimentação → Captura      |

### Fases

1. **Colocação** — jogadores alternam, colocando 1 peça por turno nas interseções. **Proibido** formar trio (3 em linha) nessa fase.
2. **Movimentação** — cada turno move 1 peça para casa adjacente (horizontal ou vertical). Movimento que formaria **4 ou mais** em linha é bloqueado.
3. **Captura** — ao formar exatamente 3 em linha na fase de movimentação, o jogador remove 1 peça adversária do tabuleiro.
4. **Fim de jogo** — quando um jogador chega a 2 peças, o outro vence.

---

## Arquitetura do Projeto

```
dara/
├── src/
│   ├── Dara.java         — Ponto de entrada, tela de configuração e espera
│   ├── Connection.java   — Comunicação TCP (padrão SrvThread/CliThread)
│   ├── GameLogic.java    — Regras e estado do jogo (puro, sem UI)
│   ├── GameWindow.java   — Interface gráfica principal (Swing)
│   └── BoardPanel.java   — Renderização do tabuleiro
├── Makefile
└── README.md
```

---

## Comunicação via Sockets TCP

### Padrão utilizado (SrvThread/CliThread)

A classe `Connection` estende `Thread` e segue o padrão visto nos exemplos da disciplina:

- **Servidor**: cria `ServerSocket`, chama `accept()` (bloqueante) e aguarda o cliente.
- **Cliente**: tenta `new Socket(host, port)` com retentativas automáticas a cada 1 segundo (até 60 tentativas).
- Ambos criam `DataInputStream` e `DataOutputStream` sobre os streams do socket.
- A **thread de recepção** (`run()`) fica em loop lendo mensagens com `istream.readUTF()`.
- O **envio** é feito pelo thread da UI chamando `ostream.writeUTF()` + `flush()`.

```java
// Loop de recepção — Connection.java
@Override
public void run() {
    while (true) {
        try {
            game.onMessage(istream.readUTF()); // bloqueante
        } catch (Exception e) {
            game.onDisconnected();
            return;
        }
    }
}
```

### Duas fases de conexão

A conexão foi dividida em duas fases para melhorar a UX:

| Fase | Método | Descrição |
|------|--------|-----------|
| 1 | `connectTCP(statusCb)` | Estabelece o TCP (bloqueante, roda em SwingWorker). A tela de espera é exibida **na tela inicial**, antes de abrir o jogo. |
| 2 | `startGame(gameWindow)` | Vincula ao GameWindow e chama `this.start()` para iniciar o loop de recepção. |

### Protocolo de mensagens (texto puro via `writeUTF`/`readUTF`)

| Mensagem            | Direção              | Significado                                      |
|---------------------|----------------------|--------------------------------------------------|
| `INIT:Nome`         | Servidor → Cliente   | Handshake inicial; informa nome do servidor      |
| `PLACE:r,c`         | Ambos                | Colocar peça na interseção (linha r, coluna c)   |
| `MOVE:fr,fc,tr,tc`  | Ambos                | Mover peça de (fr,fc) para (tr,tc)               |
| `CAPTURE:r,c`       | Ambos                | Capturar peça adversária em (r,c)                |
| `CHAT:Texto`        | Ambos                | Mensagem de chat                                 |
| `RESIGN`            | Ambos                | Desistência                                      |
| `ROOM_FULL`         | Servidor → 3º cliente | Sala já tem 2 jogadores                         |

### Controle de sala (máximo 2 jogadores)

Após aceitar o primeiro cliente, o servidor mantém o `ServerSocket` aberto e inicia uma **daemon thread** ("room-rejector") que aceita conexões extras e envia `ROOM_FULL` antes de fechar o socket:

```java
private void startRejector() {
    Thread t = new Thread(() -> {
        try {
            while (true) {
                Socket extra = serverSocket.accept();
                new DataOutputStream(extra.getOutputStream()).writeUTF("ROOM_FULL");
                extra.close();
            }
        } catch (Exception ignored) {}
    }, "room-rejector");
    t.setDaemon(true);
    t.start();
}
```

### Sincronização do estado do jogo

Não existe um servidor central de lógica — **cada máquina mantém sua própria instância de `GameLogic`** e as aplica deterministicamente com as mesmas entradas:

- Jogador A faz uma jogada → valida localmente → aplica em `GameLogic` → envia a mensagem.
- Jogador B recebe a mensagem → aplica a mesma operação em seu `GameLogic` → ambos ficam em sincronia.
- Como o protocolo TCP garante **entrega em ordem e sem duplicatas**, os estados nunca divergem.

---

## Thread Safety (Swing + Sockets)

| Thread | Responsabilidade |
|--------|-----------------|
| EDT (Event Dispatch Thread) | Toda atualização de UI e lógica de jogo |
| SwingWorker (background) | `connectTCP()` — bloqueante, não pode rodar no EDT |
| room-rejector (daemon) | Rejeita conexões extras com ROOM_FULL |
| Connection (extends Thread) | Loop `readUTF()` → `invokeLater(dispatch)` |

A thread de recepção (`Connection.run()`) nunca modifica a UI diretamente: chama `game.onMessage()` que usa `SwingUtilities.invokeLater()` para despachar no EDT.

```java
// GameWindow.java
public void onMessage(String msg) {
    SwingUtilities.invokeLater(() -> dispatch(msg));
}
```

---

## Como Executar

### Pré-requisito
Java 14 ou superior (usa switch expressions).

### Compilar e gerar JAR
```bash
make jar
```

### Executar direto
```bash
make run
```

### Executar manualmente
```bash
# Jogador 1 (Servidor) — abre e aguarda na porta 9090
java -cp build Dara

# Jogador 2 (Cliente) — informa o host onde o Servidor está rodando
java -cp build Dara
```

### Rodando em máquinas diferentes
O Jogador 2 deve informar o **IP da máquina do Jogador 1** no campo "Host do servidor".

---

## Funcionalidades Implementadas

- [x] Controle de turno com definição de quem inicia (Jogador 1 = Servidor)
- [x] Fase de colocação com bloqueio de trio durante a colocação
- [x] Fase de movimentação com bloqueio de quarteto ou mais
- [x] Sistema de captura ao formar trio na movimentação
- [x] Chat em tempo real entre os jogadores
- [x] Desistência com aviso explicativo ao oponente
- [x] Indicação de vencedor com motivo (peças insuficientes ou desistência)
- [x] Limite de 2 jogadores por sala (`ROOM_FULL`)
- [x] Opção de voltar ao menu após o fim da partida
- [x] Retentativa automática de conexão (cliente)
- [x] Interface gráfica Swing com indicadores visuais de turno e fase
