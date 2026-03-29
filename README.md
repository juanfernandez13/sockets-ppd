# Dara — Jogo de Tabuleiro via Sockets TCP

Implementação do jogo **Dara** (estratégia africana de origem nigeriana) para dois jogadores em máquinas diferentes, comunicando-se via **sockets TCP** em Java.

---

## Sobre o Jogo

| Propriedade         | Valor                              |
|---------------------|------------------------------------|
| Tabuleiro           | 5 linhas × 6 colunas (interseções) |
| Peças por jogador   | 12                                 |
| Condição de vitória | Oponente fica com apenas 2 peças   |
| Fases               | Colocação → Movimentação → Captura |

### Fases

1. **Colocação** — jogadores alternam colocando 1 peça por turno. **Proibido** formar trio (3 em linha) nessa fase.
2. **Movimentação** — cada turno move 1 peça para casa adjacente (horizontal ou vertical). Movimento que formaria **4 ou mais** em linha é bloqueado.
3. **Captura** — ao formar exatamente 3 em linha na movimentação, o jogador remove 1 peça adversária.
4. **Fim de jogo** — quando um jogador chega a 2 peças, o outro vence.

---

## Arquitetura do Projeto

```text
sockets-ppd/
├── src/
│   ├── Dara.java        — Ponto de entrada, tela de conexão (host/porta/nome)
│   ├── Connection.java  — Socket TCP: detecção servidor/cliente, handshake, I/O
│   ├── GameLogic.java   — Estado do tabuleiro e regras (sem UI)
│   ├── GameWindow.java  — Janela principal: UI, despacho de mensagens, cliques
│   └── BoardPanel.java  — Renderização gráfica do tabuleiro e peças
├── Makefile
├── executar.sh          — Script de execução (Linux/macOS)
├── executar.bat         — Script de execução (Windows)
└── README.md
```

### Responsabilidade de cada arquivo

| Arquivo           | Papel                                                                 |
|-------------------|-----------------------------------------------------------------------|
| `Dara.java`       | Exibe o formulário de conexão e inicia o processo                     |
| `Connection.java` | Gerencia o socket TCP, detecta servidor/cliente, envia e recebe       |
| `GameLogic.java`  | Armazena o estado do tabuleiro e valida as regras do jogo             |
| `GameWindow.java` | Janela principal: interpreta mensagens de rede e eventos de clique    |
| `BoardPanel.java` | Desenha o tabuleiro, peças, dicas visuais e overlay de espera         |

---

## O que é um Socket?

Um socket é um **canal de comunicação bidirecional** entre dois processos via rede. Funciona como um telefone:

- Uma parte abre um número (porta) e aguarda — é o **ServerSocket**
- A outra parte liga para esse número — é o **Socket cliente**
- Após conectar, os dois podem falar nos dois sentidos simultaneamente (full-duplex)

```text
Jogador A (Servidor)               Jogador B (Cliente)
ServerSocket(9090) <————————————— new Socket("192.168.x.x", 9090)
     accept() retorna Socket <————————————————————————————
          |                                  |
     ostream ══════════════════════════ istream
     istream ══════════════════════════ ostream
          (canal aberto — comunicação bidirecional)
```

Cada socket possui dois **streams**:

- `DataOutputStream` — fila de bytes de saída (enviar)
- `DataInputStream` — fila de bytes de entrada (receber)

O `readUTF()` **bloqueia** — fica parado esperando chegar dado, como um `await`. Por isso a leitura fica em uma thread separada (Connection Thread), para não congelar a interface.

---

## Detecção Automática Servidor/Cliente

Não há configuração manual de quem é servidor. O código tenta abrir um `ServerSocket` na porta informada:

```java
try {
    serverSocket = new ServerSocket(port); // tenta ocupar a porta
    isServer = true;                       // porta livre -> sou o servidor
} catch (BindException e) {
    isServer = false;                      // porta ocupada -> sou o cliente
}
```

**Quem abrir o programa primeiro vira servidor. Quem abrir depois vira cliente.**

---

## Comunicação via Sockets TCP

### Padrão SrvThread/CliThread

A classe `Connection` estende `Thread` e segue o padrão visto nos exemplos da disciplina:

- **Servidor**: cria `ServerSocket`, chama `accept()` (bloqueante) e aguarda o cliente.
- **Cliente**: tenta `new Socket(host, port)` com retentativas a cada 1 segundo (até 60x).
- Ambos criam `DataInputStream` e `DataOutputStream` sobre os streams do socket.
- A **Connection Thread** (`run()`) fica em loop lendo mensagens com `readUTF()`.
- O **envio** é feito pela EDT chamando `ostream.writeUTF()` + `flush()`.

```java
// Loop de recepção — Connection.java
@Override
public void run() {
    while (true) {
        game.onMessage(istream.readUTF()); // bloqueia aqui até chegar mensagem
    }
}
```

### Duas Fases de Conexão

| Fase | Método           | Descrição                                              |
|------|------------------|--------------------------------------------------------|
| 1    | `connectTCP(cb)` | Estabelece o TCP (bloqueante, roda em SwingWorker)     |
| 2    | `startGame(gw)`  | Vincula ao GameWindow e inicia o loop de recepção      |

### Handshake

Após conectar, o servidor envia `"DARA_SERVER"` e o cliente valida que é realmente o Dara (não outro programa na mesma porta):

```text
Servidor --> "DARA_SERVER"    --> Cliente valida
Servidor --> "INIT:NomeA"     --> Cliente recebe nome
Cliente  --> "NAME:NomeB"     --> Servidor recebe nome
```

### Protocolo de Mensagens

Todas as mensagens trafegam pelo **mesmo socket**, diferenciadas por prefixo:

| Mensagem            | Direção               | Significado                        |
|---------------------|-----------------------|------------------------------------|
| `DARA_SERVER`       | Servidor → Cliente    | Handshake de identificação         |
| `INIT:Nome`         | Servidor → Cliente    | Apresenta o nome do servidor       |
| `NAME:Nome`         | Cliente → Servidor    | Responde com o nome do cliente     |
| `PLACE:r,c`         | Ambos                 | Colocar peça na interseção (r, c)  |
| `MOVE:fr,fc,tr,tc`  | Ambos                 | Mover peça de (fr,fc) para (tr,tc) |
| `CAPTURE:r,c`       | Ambos                 | Capturar peça adversária em (r,c)  |
| `CHAT:texto`        | Ambos                 | Mensagem de chat                   |
| `RESIGN`            | Ambos                 | Desistência da partida             |
| `ROOM_FULL`         | Servidor → 3º cliente | Sala cheia, conexão recusada       |

O `dispatch()` no `GameWindow` lê o prefixo e decide o que fazer — como um roteador de eventos.

### Room Rejector (Limite de 2 Jogadores)

Após aceitar o primeiro cliente, o `ServerSocket` permanece aberto. Uma **daemon thread** fica aceitando e rejeitando qualquer 3ª conexão:

```text
ServerSocket(9090) — nunca fecha
    |
    +-- accept() -> Socket Jogador 2 ---- partida acontece aqui
    |
    +-- room-rejector (daemon thread)
            +-- while(true)
                  +-- accept() -> 3° jogador
                  +-- envia "DARA_SERVER" (handshake obrigatorio)
                  +-- envia "ROOM_FULL"
                  +-- fecha o socket
```

A thread é **daemon** — morre automaticamente quando o processo principal termina.

---

## Estado Determinístico (Sincronização sem servidor central)

Cada jogador mantém sua própria instância de `GameLogic` localmente. Nenhum envia o tabuleiro inteiro — apenas **a ação realizada**:

```text
Jogador A                              Jogador B
board[5][6] = { estado atual }         board[5][6] = { estado identico }
     |                                      |
logic.place(2,3)   --"PLACE:2,3"-->   logic.place(2,3)
logic.move(...)    --"MOVE:..."-->     logic.move(...)
     |                                      |
board[5][6] = { novo estado }          board[5][6] = { novo estado identico }
```

**Funciona porque:** o TCP garante entrega em ordem e sem duplicatas, e o jogo é turn-based (só um age por vez). Ambos aplicam as mesmas operações na mesma sequência → mesmo resultado.

Isso contrasta com jogos em tempo real (FPS), onde dois jogadores podem agir simultaneamente e é necessário um servidor autoritativo enviando o estado completo.

| Tipo de jogo     | Quem decide               | O que trafega           | Risco                      |
|------------------|---------------------------|-------------------------|----------------------------|
| Dara (turn-based)| Cada cliente localmente   | Só a ação (`PLACE:2,3`) | Nenhum (TCP + turn-based)  |
| FPS / tempo real | Servidor autoritativo     | Estado completo ou delta| Alto sem servidor central  |

---

## Threads

| Thread                          | Criada em         | Função                                          |
|---------------------------------|-------------------|-------------------------------------------------|
| **EDT** (Event Dispatch Thread) | Swing             | Toda UI, cliques, lógica do jogo                |
| **SwingWorker**                 | `Dara.java`       | `connectTCP()` bloqueante sem travar a UI       |
| **Connection** (extends Thread) | `Connection.java` | Loop `readUTF()` durante toda a partida         |
| **room-rejector** (daemon)      | `Connection.java` | Rejeita 3ª+ conexão com ROOM_FULL               |

A Connection Thread nunca toca na UI diretamente — repassa via `SwingUtilities.invokeLater()`:

```java
// GameWindow.java
public void onMessage(String msg) {
    SwingUtilities.invokeLater(() -> dispatch(msg)); // transfere para a EDT
}
```

### Fluxo Completo de um Movimento

```text
[Jogador A clica celula (2,3)]
        |
   BoardPanel.mouseClicked()  -- converte pixel -> (row, col)
        |
   GameWindow.onBoardClick()  -- valida: e minha vez? fase correta?
        |
   GameLogic.canPlace(2,3) OK
        |
   GameLogic.place(2,3)       -- atualiza tabuleiro local
   connection.send("PLACE:2,3") --------------------> [TCP]
   refreshAll() -> repaint()                             |
                                    Connection Thread do B acorda
                                    readUTF() -> "PLACE:2,3"
                                    game.onMessage("PLACE:2,3")
                                          |
                                    invokeLater -> EDT do B
                                          |
                                    dispatch("PLACE:2,3")
                                          |
                                    GameLogic.place(2,3)  <- mesmo estado
                                    refreshAll() -> repaint()
```

---

## TCP Socket vs WebSocket

| Característica     | TCP Socket (Dara)               | WebSocket                         |
|--------------------|---------------------------------|-----------------------------------|
| Quem usa           | Apps Java, jogos desktop        | Browsers, apps web                |
| Protocolo extra    | Nenhum — bytes puros            | HTTP inicial → upgrade            |
| Motivo de existir  | —                               | Browsers não usam TCP puro        |
| Após conectar      | Canal bidirecional aberto       | Canal bidirecional aberto         |

---

## Como Executar

### Pré-requisito

Java 21 (recomendado) ou Java 14+.

### Compilar e gerar JAR

```bash
# Linux/macOS
make jar

# Windows (sem make)
mkdir -p build
javac -encoding UTF-8 -d build src/*.java
echo "Main-Class: Dara" > manifest.txt
jar cfm dara.jar manifest.txt -C build .
```

### Executar

```bash
# Linux/macOS
./executar.sh

# Windows
executar.bat

# Ou direto
java -jar dara.jar
```

### Rodando em máquinas diferentes

O Jogador 2 deve informar o **IP da máquina do Jogador 1** no campo "Host do servidor". Ambos usam a mesma porta.

---

## Funcionalidades

- [x] Detecção automática servidor/cliente (quem abrir primeiro vira servidor)
- [x] Handshake com validação (rejeita porta ocupada por outro programa)
- [x] Fase de colocação com bloqueio de trio
- [x] Fase de movimentação com bloqueio de quarteto ou mais
- [x] Sistema de captura ao formar trio na movimentação
- [x] Estado determinístico — sem servidor central de lógica
- [x] Chat em tempo real entre os jogadores
- [x] Desistência com aviso ao oponente
- [x] Indicação de vencedor com motivo
- [x] Limite de 2 jogadores por sala (room-rejector daemon)
- [x] Retorno ao menu após fim de partida
- [x] Retentativa automática de conexão (cliente)
- [x] Interface Swing com dicas visuais, hover e overlay de espera
