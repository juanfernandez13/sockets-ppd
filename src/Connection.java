import java.net.*;
import java.io.*;
import java.util.function.Consumer;

/**
 * Segue o padrão SrvThread/CliThread dos exemplos da disciplina.
 *
 * Duas fases:
 *  1. connectTCP()  — estabelece o TCP (bloqueante; chamar em background thread).
 *  2. startGame()   — vincula ao GameWindow e inicia o loop de recepção.
 *
 * O servidor mantém o ServerSocket aberto após aceitar o primeiro cliente e
 * envia "ROOM_FULL" para qualquer conexão extra.
 */
public class Connection extends Thread {

    private Socket           socket;
    private ServerSocket     serverSocket;
    private DataOutputStream ostream;
    private DataInputStream  istream;
    private boolean          isServer;   // determinado em connectTCP()
    private final String     host;
    private final int        port;
    private GameWindow       game;
    private volatile boolean cancelled = false;

    /** host é usado somente se a porta já estiver ocupada (modo cliente). */
    public Connection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public boolean isServer() { return isServer; }

    // ── Fase 1 ───────────────────────────────────────────────────────────────

    /**
     * Bloqueia até a conexão TCP ser estabelecida (ou lançar exceção).
     * statusCb é chamado no mesmo thread (use invokeLater no caller se necessário).
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
            socket = serverSocket.accept();
            ostream = new DataOutputStream(socket.getOutputStream());
            istream  = new DataInputStream(socket.getInputStream());
            // Handshake: anuncia que este é um servidor Dara
            ostream.writeUTF("DARA_SERVER");
            ostream.flush();
            startRejector();
        } else {
            statusCb.accept("Porta " + port + " em uso — verificando se é um servidor Dara...");
            int attempt = 0;
            while (!cancelled) {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 2000);
                    break;
                } catch (ConnectException | java.net.SocketTimeoutException e) {
                    if (++attempt >= 60) throw new Exception(
                        "A porta " + port + " está em uso por outro processo.\n"
                        + "Escolha uma porta diferente.");
                    Thread.sleep(1000);
                }
            }
            if (cancelled) throw new InterruptedException("cancelado");

            ostream = new DataOutputStream(socket.getOutputStream());
            istream  = new DataInputStream(socket.getInputStream());

            // Handshake: confirma que do outro lado é realmente um servidor Dara
            socket.setSoTimeout(800);
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
            socket.setSoTimeout(0);
            if (!"DARA_SERVER".equals(hello)) {
                socket.close();
                throw new Exception(
                    "A porta " + port + " está em uso por outro processo, não pelo Dara.\n"
                    + "Escolha uma porta diferente.");
            }
            statusCb.accept("Servidor Dara encontrado — conectando como Jogador 2...");
        }
    }

    /** Continua aceitando conexões extras; envia handshake + ROOM_FULL para cada uma. */
    private void startRejector() {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Socket extra = serverSocket.accept();
                    try {
                        DataOutputStream dos = new DataOutputStream(extra.getOutputStream());
                        dos.writeUTF("DARA_SERVER"); // handshake antes do ROOM_FULL
                        dos.writeUTF("ROOM_FULL");
                        dos.flush();
                    } finally {
                        extra.close();
                    }
                }
            } catch (Exception ignored) {}
        }, "room-rejector");
        t.setDaemon(true);
        t.start();
    }

    // ── Fase 2 ───────────────────────────────────────────────────────────────

    /** Vincula ao GameWindow e inicia o loop de recepção de mensagens. */
    public void startGame(GameWindow g) {
        this.game = g;
        this.start();
    }

    // ── API pública ──────────────────────────────────────────────────────────

    public void cancel() {
        cancelled = true;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (socket       != null) socket.close();       } catch (Exception ignored) {}
    }

    public boolean isCancelled() { return cancelled; }

    public void send(String msg) {
        try {
            ostream.writeUTF(msg);
            ostream.flush();
        } catch (Exception e) {
            System.err.println("Erro ao enviar: " + e.getMessage());
        }
    }

    // ── Loop de recepção ─────────────────────────────────────────────────────

    @Override
    public void run() {
        while (true) {
            try {
                game.onMessage(istream.readUTF());
            } catch (Exception e) {
                if (game != null) game.onDisconnected();
                return;
            }
        }
    }
}
