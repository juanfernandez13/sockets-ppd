import java.net.*;
import java.io.*;
import java.util.function.Consumer;

/**
 * Gerencia a conexão TCP entre os dois jogadores.
 *
 * Segue o padrão SrvThread/CliThread dos exemplos da disciplina.
 *
 * DUAS FASES:
 *   Fase 1 — connectTCP(): estabelece o socket TCP (bloqueante, chamar em background thread).
 *   Fase 2 — startGame(): vincula ao GameWindow e inicia o loop de recepção de mensagens.
 *
 * DETECÇÃO AUTOMÁTICA DE PAPEL:
 *   - Tenta abrir um ServerSocket na porta informada.
 *   - Se der certo → este processo é o SERVIDOR (Jogador 1).
 *   - Se a porta já estiver ocupada (BindException) → este processo é o CLIENTE (Jogador 2).
 *
 * ROOM REJECTOR:
 *   Após aceitar o primeiro cliente, o ServerSocket permanece aberto.
 *   Uma daemon thread rejeita qualquer 3ª conexão enviando ROOM_FULL.
 */
public class Connection extends Thread {

    private Socket           socket;       // canal de comunicação com o outro jogador
    private ServerSocket     serverSocket; // escuta na porta (somente servidor)
    private DataOutputStream ostream;      // stream de saída (envio de mensagens)
    private DataInputStream  istream;      // stream de entrada (recepção de mensagens)
    private boolean          isServer;     // true = servidor, false = cliente
    private final String     host;         // endereço do servidor (usado somente no cliente)
    private final int        port;         // porta TCP
    private GameWindow       game;         // referência à janela do jogo (definida na fase 2)
    private volatile boolean cancelled = false; // volatile: lido por múltiplas threads

    /** host é usado somente se a porta já estiver ocupada (modo cliente). */
    public Connection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public boolean isServer() { return isServer; }

    // ── Fase 1 ───────────────────────────────────────────────────────────────

    /**
     * Estabelece a conexão TCP. Método BLOQUEANTE — deve ser chamado em uma thread de background
     * (ex: SwingWorker) para não congelar a interface gráfica.
     *
     * statusCb é um callback chamado para atualizar o label de status na tela de espera.
     *
     * Fluxo servidor:
     *   1. Abre ServerSocket na porta
     *   2. Aguarda cliente com accept() (bloqueia aqui)
     *   3. Cria streams de I/O
     *   4. Envia handshake "DARA_SERVER"
     *   5. Inicia room-rejector
     *
     * Fluxo cliente:
     *   1. Tenta conectar ao host:porta a cada 1 segundo (até 60 tentativas)
     *   2. Cria streams de I/O
     *   3. Lê handshake e valida que é realmente um servidor Dara
     */
    public void connectTCP(Consumer<String> statusCb) throws Exception {
        // Tenta abrir servidor na porta; se já estiver em uso, vira cliente
        try {
            serverSocket = new ServerSocket(port);
            isServer = true;
        } catch (java.net.BindException e) {
            isServer = false;
        }

        if (isServer) {
            statusCb.accept("Porta livre — você é o Servidor. Aguardando Jogador 2 na porta " + port + "...");
            // accept() bloqueia até o cliente conectar — retorna um Socket dedicado
            socket = serverSocket.accept();
            ostream = new DataOutputStream(socket.getOutputStream());
            istream  = new DataInputStream(socket.getInputStream());
            // Handshake: informa ao cliente que este é um servidor Dara legítimo
            ostream.writeUTF("DARA_SERVER");
            ostream.flush();
            // Mantém o ServerSocket aberto para rejeitar 3ª+ conexões
            startRejector();
        } else {
            statusCb.accept("Porta " + port + " em uso — verificando se é um servidor Dara...");
            int attempt = 0;
            // Tenta conectar repetidamente — o servidor pode ainda não ter aberto
            while (!cancelled) {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 2000);
                    break; // conectou com sucesso
                } catch (ConnectException | java.net.SocketTimeoutException e) {
                    if (++attempt >= 60) throw new Exception(
                        "A porta " + port + " está em uso por outro processo.\n"
                        + "Escolha uma porta diferente.");
                    Thread.sleep(1000); // aguarda 1 segundo antes de tentar novamente
                }
            }
            if (cancelled) throw new InterruptedException("cancelado");

            ostream = new DataOutputStream(socket.getOutputStream());
            istream  = new DataInputStream(socket.getInputStream());

            // Valida o handshake: garante que do outro lado é o Dara, não outro programa
            socket.setSoTimeout(800); // não espera mais que 800ms pela resposta
            String hello;
            try {
                hello = istream.readUTF();
            } catch (java.net.SocketTimeoutException e) {
                socket.close();
                throw new Exception(
                    "A porta " + port + " está em uso por outro processo, não pelo Dara.\n"
                    + "Escolha uma porta diferente.");
            } catch (java.io.IOException e) {
                socket.close();
                throw new Exception(
                    "A porta " + port + " está em uso por outro processo, não pelo Dara.\n"
                    + "Escolha uma porta diferente.");
            }
            socket.setSoTimeout(0); // remove o timeout após validação
            if (!"DARA_SERVER".equals(hello)) {
                socket.close();
                throw new Exception(
                    "A porta " + port + " está em uso por outro processo, não pelo Dara.\n"
                    + "Escolha uma porta diferente.");
            }
            statusCb.accept("Servidor Dara encontrado — conectando como Jogador 2...");
        }
    }

    /**
     * Sobe a daemon thread "room-rejector".
     *
     * Fica em loop aceitando conexões extras e enviando ROOM_FULL para cada uma.
     * Precisa enviar "DARA_SERVER" primeiro porque o cliente valida esse handshake
     * antes de ler qualquer outra mensagem.
     *
     * É daemon para morrer automaticamente quando o processo principal terminar.
     */
    private void startRejector() {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Socket extra = serverSocket.accept(); // bloqueia até chegar 3º jogador
                    try {
                        DataOutputStream dos = new DataOutputStream(extra.getOutputStream());
                        dos.writeUTF("DARA_SERVER"); // handshake obrigatório antes do ROOM_FULL
                        dos.writeUTF("ROOM_FULL");
                        dos.flush();
                    } finally {
                        extra.close(); // fecha a conexão do 3º jogador
                    }
                }
            } catch (Exception ignored) {}
        }, "room-rejector");
        t.setDaemon(true); // morre junto com o processo principal
        t.start();
    }

    // ── Fase 2 ───────────────────────────────────────────────────────────────

    /**
     * Vincula esta Connection ao GameWindow e inicia o loop de recepção.
     * Chama this.start() que executa o método run() em uma nova thread.
     */
    public void startGame(GameWindow g) {
        this.game = g;
        this.start(); // inicia a Connection Thread (chama run())
    }

    // ── API pública ──────────────────────────────────────────────────────────

    /** Cancela a conexão fechando os sockets. Sinaliza via flag volatile. */
    public void cancel() {
        cancelled = true;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (socket       != null) socket.close();       } catch (Exception ignored) {}
    }

    public boolean isCancelled() { return cancelled; }

    /**
     * Envia uma mensagem de texto para o outro jogador via TCP.
     * Chamado sempre pela EDT (thread da UI).
     *
     * Exemplos de mensagem: "PLACE:2,3", "MOVE:0,1,0,2", "CHAT:Olá!", "RESIGN"
     */
    public void send(String msg) {
        try {
            ostream.writeUTF(msg);
            ostream.flush();
        } catch (Exception e) {
            System.err.println("Erro ao enviar: " + e.getMessage());
        }
    }

    // ── Loop de recepção ─────────────────────────────────────────────────────

    /**
     * Loop principal desta thread — fica bloqueado em readUTF() esperando mensagens.
     *
     * readUTF() bloqueia a thread até chegar dados pela rede.
     * Quando chega uma mensagem, repassa para game.onMessage() que usa
     * SwingUtilities.invokeLater() para processar na EDT sem risco de race condition.
     *
     * Se o socket fechar (oponente desconectou), lança exceção e encerra a thread.
     */
    @Override
    public void run() {
        while (true) {
            try {
                game.onMessage(istream.readUTF()); // bloqueia aqui até chegar mensagem
            } catch (Exception e) {
                // Socket fechado — oponente desconectou ou partida encerrada
                if (game != null) game.onDisconnected();
                return;
            }
        }
    }
}
