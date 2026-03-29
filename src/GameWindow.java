import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;

/**
 * Janela principal do jogo.
 *
 * Responsabilidades:
 *   - Construir e exibir a interface gráfica (tabuleiro, chat, cartões de jogador)
 *   - Receber mensagens da rede (via Connection) e despachar para a lógica correta
 *   - Tratar cliques no tabuleiro e enviá-los ao oponente
 *
 * THREAD SAFETY:
 *   A Connection Thread chama onMessage() de fora da EDT.
 *   onMessage() usa SwingUtilities.invokeLater() para garantir que
 *   toda atualização de UI e de GameLogic aconteça na EDT.
 *
 * SINCRONIZAÇÃO DE ESTADO:
 *   Tanto o jogador local quanto o oponente aplicam os mesmos movimentos
 *   em suas instâncias locais de GameLogic → estados sempre idênticos.
 */
public class GameWindow extends JFrame {

    // ── Paleta de cores ──────────────────────────────────────────────────────
    static final Color BG          = new Color(22,  27,  42);
    static final Color PANEL       = new Color(30,  37,  58);
    static final Color CARD        = new Color(40,  48,  72);
    static final Color BORDER_CLR  = new Color(55,  68, 100);
    static final Color TEXT        = Color.WHITE;
    static final Color SUBTEXT     = new Color(140, 155, 195);

    // Cores da barra de status (cada fase/turno tem uma cor diferente)
    private static final Color ST_MY_TURN   = new Color(35, 130,  70); // verde: minha vez
    private static final Color ST_OPP_TURN  = new Color(45,  65, 115); // azul: vez do oponente
    private static final Color ST_CAPTURE   = new Color(170,  45,  45); // vermelho: capturar
    private static final Color ST_OPP_CAP   = new Color(160,  95,  20); // laranja: oponente capturando
    private static final Color ST_GAMEOVER  = new Color(55,  55,  65); // cinza: fim de jogo
    private static final Color ST_WAITING   = new Color(50,  55,  75); // aguardando conexão

    // ── Estado ───────────────────────────────────────────────────────────────
    private final GameLogic logic;       // estado local do tabuleiro
    private Connection      connection;
    private int    myPlayer     = 0;     // 0 = ainda não definido, 1 = servidor, 2 = cliente
    private String myName;
    private String opponentName = "Oponente";
    private final boolean isServer;

    // ── Componentes de UI ────────────────────────────────────────────────────
    private BoardPanel boardPanel;
    private JLabel     statusLabel;
    private JTextArea  chatArea;
    private JTextField chatInput;
    private JPanel     p1Card, p2Card;
    private JLabel     p1PiecesLbl, p2PiecesLbl;
    private JLabel     p1TurnBadge, p2TurnBadge;
    private JLabel     p1NameLbl,   p2NameLbl;

    // Célula selecionada na fase MOVEMENT (null se nenhuma selecionada)
    int[] selectedCell = null;

    // ── Construtor ───────────────────────────────────────────────────────────

    /**
     * Recebe uma Connection já estabelecida (TCP pronto).
     * Constrói a UI, vincula a Connection e envia INIT se for servidor.
     */
    public GameWindow(Connection conn, boolean isServer, String name) {
        this.connection = conn;
        this.isServer   = isServer;
        this.myName     = name;
        this.logic      = new GameLogic();

        buildUI();
        setTitle("Dara");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
        setVisible(true);

        // Fase 2: vincula ao GameWindow e inicia a Connection Thread
        connection.startGame(this);
        onConnected();

        // Cancela a conexão ao fechar a janela
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                connection.cancel();
            }
        });
    }

    // ── Construção da UI ─────────────────────────────────────────────────────

    /** Monta o layout principal: status (norte), tabuleiro (centro), painel lateral (leste). */
    private void buildUI() {
        setLayout(new BorderLayout());
        getContentPane().setBackground(BG);

        add(buildStatusBar(), BorderLayout.NORTH);
        add(boardPanel = new BoardPanel(this), BorderLayout.CENTER);
        add(buildSidePanel(), BorderLayout.EAST);
    }

    /** Barra de status no topo — muda cor e texto conforme a fase/turno. */
    private JLabel buildStatusBar() {
        statusLabel = new JLabel("Aguardando conexão...", SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        statusLabel.setForeground(TEXT);
        statusLabel.setOpaque(true);
        statusLabel.setBackground(ST_WAITING);
        statusLabel.setBorder(new EmptyBorder(10, 16, 10, 16));
        return statusLabel;
    }

    /** Painel lateral direito: cartões dos jogadores + chat + botão desistir. */
    private JPanel buildSidePanel() {
        JPanel side = new JPanel(new BorderLayout(0, 10));
        side.setBackground(BG);
        side.setBorder(new EmptyBorder(10, 6, 10, 10));
        side.setPreferredSize(new Dimension(270, 0));

        side.add(buildPlayersPanel(), BorderLayout.NORTH);
        side.add(buildChatPanel(),    BorderLayout.CENTER);
        side.add(buildResignButton(), BorderLayout.SOUTH);
        return side;
    }

    /** Cria os dois cartões de jogador (um para cada). */
    private JPanel buildPlayersPanel() {
        JPanel wrap = new JPanel(new GridLayout(2, 1, 0, 6));
        wrap.setBackground(BG);
        wrap.setBorder(new EmptyBorder(0, 0, 4, 0));

        p1Card = buildPlayerCard(1);
        p2Card = buildPlayerCard(2);
        wrap.add(p1Card);
        wrap.add(p2Card);
        return wrap;
    }

    /**
     * Constrói um cartão de jogador com: bolinha colorida, nome, contagem de peças e badge "SUA VEZ".
     * Salva referências nos campos p1/p2 para atualização posterior.
     */
    private JPanel buildPlayerCard(int playerNum) {
        JPanel card = new JPanel(new BorderLayout(8, 0));
        card.setBackground(CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(BORDER_CLR, 1),
            new EmptyBorder(10, 12, 10, 12)
        ));

        // Bolinha colorida representando a cor das peças do jogador
        Color col = playerNum == 1 ? BoardPanel.P1_COLOR : BoardPanel.P2_COLOR;
        JPanel dot = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(col);
                g2.fillOval(0, 2, 18, 18);
                g2.setColor(col.darker());
                g2.drawOval(0, 2, 18, 18);
            }
        };
        dot.setPreferredSize(new Dimension(22, 22));
        dot.setOpaque(false);

        JLabel nameLbl  = new JLabel("Jogador " + playerNum);
        nameLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        nameLbl.setForeground(TEXT);

        JLabel piecesLbl = new JLabel("0 / 12 colocadas");
        piecesLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        piecesLbl.setForeground(SUBTEXT);

        JPanel info = new JPanel(new GridLayout(2, 1, 0, 2));
        info.setOpaque(false);
        info.add(nameLbl);
        info.add(piecesLbl);

        // Badge de turno — inicialmente oculto, exibido por refreshAll()
        JLabel badge = new JLabel("SUA VEZ");
        badge.setFont(new Font("SansSerif", Font.BOLD, 10));
        badge.setForeground(new Color(20, 25, 40));
        badge.setBackground(new Color(80, 200, 120));
        badge.setOpaque(true);
        badge.setBorder(new EmptyBorder(2, 6, 2, 6));
        badge.setVisible(false);

        card.add(dot,   BorderLayout.WEST);
        card.add(info,  BorderLayout.CENTER);
        card.add(badge, BorderLayout.EAST);

        // Guarda referências para atualização pelo refreshAll()
        if (playerNum == 1) { p1NameLbl = nameLbl; p1PiecesLbl = piecesLbl; p1TurnBadge = badge; }
        else                { p2NameLbl = nameLbl; p2PiecesLbl = piecesLbl; p2TurnBadge = badge; }
        return card;
    }

    /** Painel de chat: área de texto (somente leitura) + campo de entrada + botão Enviar. */
    private JPanel buildChatPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBackground(BG);

        JLabel header = new JLabel("  Chat");
        header.setFont(new Font("SansSerif", Font.BOLD, 12));
        header.setForeground(SUBTEXT);
        header.setBorder(new EmptyBorder(0, 0, 2, 0));
        panel.add(header, BorderLayout.NORTH);

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatArea.setBackground(CARD);
        chatArea.setForeground(TEXT);
        chatArea.setBorder(new EmptyBorder(6, 8, 6, 8));

        JScrollPane scroll = new JScrollPane(chatArea);
        scroll.setBorder(new LineBorder(BORDER_CLR));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setBackground(BG);
        inputRow.setBorder(new EmptyBorder(4, 0, 0, 0));

        chatInput = new JTextField();
        chatInput.setBackground(CARD);
        chatInput.setForeground(TEXT);
        chatInput.setCaretColor(TEXT);
        chatInput.setFont(new Font("SansSerif", Font.PLAIN, 13));
        chatInput.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(BORDER_CLR), new EmptyBorder(5, 8, 5, 8)
        ));

        JButton sendBtn = darkButton("Enviar", Dara.ACCENT, new Color(15, 20, 38));
        sendBtn.addActionListener(e -> sendChat());
        chatInput.addActionListener(e -> sendChat()); // Enter no campo também envia

        inputRow.add(chatInput, BorderLayout.CENTER);
        inputRow.add(sendBtn,   BorderLayout.EAST);
        panel.add(inputRow, BorderLayout.SOUTH);
        return panel;
    }

    private JButton buildResignButton() {
        JButton btn = darkButton("Desistir da Partida", new Color(180, 50, 50), TEXT);
        btn.addActionListener(e -> handleResign());
        return btn;
    }

    /** Cria um botão com fundo escuro e cor de texto/fundo configuráveis. */
    private JButton darkButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.BOLD, 13));
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(9, 16, 9, 16));
        return btn;
    }

    // ── Acessores ─────────────────────────────────────────────────────────
    public GameLogic getLogic()        { return logic; }
    public int[]     getSelectedCell() { return selectedCell; }
    public int       getMyPlayer()     { return myPlayer; }

    // ── Callbacks de rede ─────────────────────────────────────────────────

    /**
     * Chamado após a conexão ser estabelecida.
     * O servidor envia INIT com seu nome; o cliente aguarda receber o INIT.
     */
    public void onConnected() {
        if (isServer) {
            myPlayer = 1;
            connection.send("INIT:" + myName); // apresenta o nome ao cliente
            setTitle("Dara  |  Jogador 1  —  " + myName);
            refreshAll();
        }
        // cliente aguarda mensagem INIT para descobrir que é Jogador 2
    }

    /**
     * Chamado pela Connection Thread ao receber uma mensagem.
     * Usa invokeLater para transferir o processamento para a EDT,
     * garantindo que a UI só seja modificada pela thread correta.
     */
    public void onMessage(String msg) {
        SwingUtilities.invokeLater(() -> dispatch(msg));
    }

    /** Chamado quando o socket fecha inesperadamente (oponente desconectou). */
    public void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            if (logic.getPhase() != GameLogic.Phase.GAMEOVER) {
                if (myPlayer > 0) {
                    // Declara o jogador local como vencedor e volta ao menu
                    triggerGameOver(myPlayer, "O oponente fechou a conexão.");
                } else {
                    // Papel ainda não definido (desconectou antes do handshake)
                    dispose();
                    Dara.showLaunchDialog();
                }
            }
        });
    }

    // ── Despacho de mensagens ─────────────────────────────────────────────

    /**
     * Roteador de mensagens recebidas da rede.
     * Lê o prefixo da mensagem e executa a ação correspondente.
     *
     * Todas as mensagens usam o mesmo socket TCP — o prefixo diferencia o tipo:
     *   INIT, NAME → identificação dos jogadores
     *   PLACE, MOVE, CAPTURE → ações de jogo (aplica no GameLogic local)
     *   CHAT → exibe no painel de chat
     *   RESIGN, ROOM_FULL → eventos especiais
     */
    private void dispatch(String msg) {
        if (msg.equals("ROOM_FULL")) {
            // Sala já tem 2 jogadores — volta para a tela inicial
            JOptionPane.showMessageDialog(this,
                "<html><b>Sala cheia!</b><br>Já há 2 jogadores conectados nesta partida.</html>",
                "Sala Cheia", JOptionPane.ERROR_MESSAGE);
            dispose();
            Dara.showLaunchDialog();
            return;

        } else if (msg.startsWith("INIT:")) {
            // Servidor se apresentou — este processo é o Jogador 2
            opponentName = msg.substring(5);
            myPlayer = 2;
            connection.send("NAME:" + myName); // responde com o próprio nome
            setTitle("Dara  |  Jogador 2  —  " + myName);
            refreshAll();

        } else if (msg.startsWith("NAME:")) {
            // Cliente respondeu com seu nome
            opponentName = msg.substring(5);
            refreshNames();

        } else if (msg.startsWith("CHAT:")) {
            // Mensagem de chat — apenas exibe, não afeta o jogo
            appendChat(msg.substring(5));

        } else if (msg.startsWith("PLACE:")) {
            // Oponente colocou uma peça — aplica no tabuleiro local
            String[] p = msg.substring(6).split(",");
            logic.place(int_(p[0]), int_(p[1]));
            refreshAll();

        } else if (msg.startsWith("MOVE:")) {
            // Oponente moveu uma peça — aplica no tabuleiro local
            String[] p = msg.substring(5).split(",");
            logic.move(int_(p[0]), int_(p[1]), int_(p[2]), int_(p[3]));
            selectedCell = null;
            refreshAll();

        } else if (msg.startsWith("CAPTURE:")) {
            // Oponente capturou uma peça — aplica e verifica fim de jogo
            String[] p = msg.substring(8).split(",");
            boolean over = logic.capture(int_(p[0]), int_(p[1]));
            selectedCell = null;
            refreshAll();
            if (over) {
                int w = logic.getWinner();
                String reason = w == myPlayer
                    ? "O oponente ficou com apenas 2 peças restantes."
                    : "Você ficou com apenas 2 peças restantes.";
                triggerGameOver(w, reason);
            }

        } else if (msg.equals("RESIGN")) {
            // Oponente desistiu — eu venci
            triggerGameOver(myPlayer, "O oponente desistiu da partida.");
        }
    }

    /** Converte String para int — atalho para Integer.parseInt(). */
    private int int_(String s) { return Integer.parseInt(s); }

    // ── Interação com o tabuleiro ─────────────────────────────────────────

    /**
     * Chamado pelo BoardPanel quando o jogador clica em uma célula.
     * Ignora cliques fora do turno ou após fim de jogo.
     */
    public void onBoardClick(int row, int col) {
        if (myPlayer == 0) return;                                      // ainda sem papel definido
        if (logic.getPhase() == GameLogic.Phase.GAMEOVER) return;      // jogo encerrado
        if (logic.getCurrentPlayer() != myPlayer) return;              // não é minha vez

        switch (logic.getPhase()) {
            case PLACEMENT -> clickPlacement(row, col);
            case MOVEMENT  -> clickMovement(row, col);
            case CAPTURE   -> clickCapture(row, col);
        }
    }

    /**
     * Trata clique na fase PLACEMENT.
     * Valida, coloca a peça localmente e envia ao oponente.
     */
    private void clickPlacement(int r, int c) {
        if (!logic.canPlace(r, c)) return;
        logic.place(r, c);
        connection.send("PLACE:" + r + "," + c);
        refreshAll();
    }

    /**
     * Trata clique na fase MOVEMENT.
     * Primeiro clique: seleciona a peça.
     * Segundo clique: move para o destino (se válido) ou reseleciona outra peça.
     */
    private void clickMovement(int r, int c) {
        if (selectedCell == null) {
            // Nenhuma peça selecionada — tenta selecionar
            if (logic.canSelect(r, c, myPlayer)) {
                selectedCell = new int[]{r, c};
                boardPanel.repaint();
            }
        } else {
            if (selectedCell[0] == r && selectedCell[1] == c) {
                // Clicou na mesma peça — deseleciona
                selectedCell = null;
                boardPanel.repaint();
            } else if (logic.canMoveTo(selectedCell[0], selectedCell[1], r, c)) {
                // Destino válido — realiza o movimento
                int fr = selectedCell[0], fc = selectedCell[1];
                logic.move(fr, fc, r, c);
                connection.send("MOVE:" + fr + "," + fc + "," + r + "," + c);
                selectedCell = null;
                refreshAll();
            } else if (logic.canSelect(r, c, myPlayer)) {
                // Clicou em outra peça própria — reseleciona
                selectedCell = new int[]{r, c};
                boardPanel.repaint();
            }
        }
    }

    /**
     * Trata clique na fase CAPTURE.
     * Valida, remove a peça adversária e verifica fim de jogo.
     */
    private void clickCapture(int r, int c) {
        if (!logic.canCapture(r, c)) return;
        boolean over = logic.capture(r, c);
        connection.send("CAPTURE:" + r + "," + c);
        selectedCell = null;
        refreshAll();
        if (over) {
            int w = logic.getWinner();
            String reason = w == myPlayer
                ? "O oponente ficou com apenas 2 peças restantes."
                : "Você ficou com apenas 2 peças restantes.";
            triggerGameOver(w, reason);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Envia mensagem de chat ao oponente e exibe localmente.
     * O prefixo "CHAT:" é usado pelo dispatch() do oponente para identificar o tipo.
     */
    private void sendChat() {
        String text = chatInput.getText().trim();
        if (text.isEmpty() || connection == null) return;
        connection.send("CHAT:" + myName + ": " + text);
        appendChat(myName + ": " + text); // exibe imediatamente para o remetente
        chatInput.setText("");
    }

    /** Exibe diálogo de confirmação e envia RESIGN ao oponente se confirmado. */
    private void handleResign() {
        if (myPlayer == 0 || logic.getPhase() == GameLogic.Phase.GAMEOVER) return;
        if (JOptionPane.showConfirmDialog(this, "Tem certeza que deseja desistir?",
                "Desistir", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
            connection.send("RESIGN");
            triggerGameOver(3 - myPlayer, "Você desistiu da partida.");
        }
    }

    /**
     * Encerra o jogo: muda a fase, exibe o resultado e volta ao menu inicial.
     * winner = número do jogador vencedor (1 ou 2), ou 0 para empate.
     */
    private void triggerGameOver(int winner, String reason) {
        connection.cancel();
        logic.setPhase(GameLogic.Phase.GAMEOVER);
        String result = (winner == myPlayer) ? "Você venceu!" :
                        (winner == 0)        ? "Empate!"      : "Você perdeu!";
        setStatus(ST_GAMEOVER, "Fim de jogo  —  " + result);
        p1TurnBadge.setVisible(false);
        p2TurnBadge.setVisible(false);
        boardPanel.repaint();
        String body = "<html><center><font size='+1'><b>" + result + "</b></font>"
                    + "<br><br><font color='#aaaaaa'>" + reason + "</font></center></html>";
        JOptionPane.showMessageDialog(this, body, "Fim de Jogo",
            winner == myPlayer ? JOptionPane.INFORMATION_MESSAGE
                               : JOptionPane.WARNING_MESSAGE);
        dispose();             // fecha a janela do jogo
        Dara.showLaunchDialog(); // volta para a tela inicial
    }

    public void setStatus(String s) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(s));
    }

    private void setStatus(Color bg, String text) {
        statusLabel.setBackground(bg);
        statusLabel.setText(text);
    }

    /** Adiciona uma linha ao chat e rola para o final automaticamente. */
    private void appendChat(String s) {
        chatArea.append(s + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    /** Atualiza os labels de nome exibindo "(você)" ao lado do próprio jogador. */
    private void refreshNames() {
        if (myPlayer == 1) {
            p1NameLbl.setText(myName + "  (você)");
            p2NameLbl.setText(opponentName);
        } else if (myPlayer == 2) {
            p1NameLbl.setText(opponentName);
            p2NameLbl.setText(myName + "  (você)");
        }
    }

    /**
     * Atualiza toda a interface após um movimento:
     *   - Nomes e contagem de peças
     *   - Badge "SUA VEZ" / "VEZ DELE"
     *   - Destaque do cartão ativo
     *   - Cor e texto da barra de status
     *   - Redesenho do tabuleiro
     */
    private void refreshAll() {
        refreshNames();

        // Contagem de peças: exibe colocadas na fase PLACEMENT, restantes nas demais
        GameLogic.Phase ph = logic.getPhase();
        if (ph == GameLogic.Phase.PLACEMENT) {
            p1PiecesLbl.setText(logic.getP1Placed() + " / 12 colocadas");
            p2PiecesLbl.setText(logic.getP2Placed() + " / 12 colocadas");
        } else {
            p1PiecesLbl.setText(logic.getP1Count() + " peças restantes");
            p2PiecesLbl.setText(logic.getP2Count() + " peças restantes");
        }

        // Badge de turno
        int curr = logic.getCurrentPlayer();
        boolean active1 = ph != GameLogic.Phase.GAMEOVER && curr == 1;
        boolean active2 = ph != GameLogic.Phase.GAMEOVER && curr == 2;
        p1TurnBadge.setVisible(active1);
        p2TurnBadge.setVisible(active2);
        if (myPlayer > 0) {
            p1TurnBadge.setText(myPlayer == 1 ? "SUA VEZ" : "VEZ DELE");
            p2TurnBadge.setText(myPlayer == 2 ? "SUA VEZ" : "VEZ DELE");
        }

        // Destaque do cartão do jogador ativo (fundo levemente esverdeado)
        p1Card.setBackground(curr == 1 && ph != GameLogic.Phase.GAMEOVER
                             ? new Color(45, 65, 55) : CARD);
        p2Card.setBackground(curr == 2 && ph != GameLogic.Phase.GAMEOVER
                             ? new Color(45, 65, 55) : CARD);

        // Barra de status: cor e mensagem contextuais
        if (myPlayer == 0) return;
        Color bg;
        String txt;

        if (ph == GameLogic.Phase.GAMEOVER) {
            bg  = ST_GAMEOVER;
            txt = "Fim de Jogo";
        } else if (ph == GameLogic.Phase.CAPTURE) {
            if (curr == myPlayer) {
                bg  = ST_CAPTURE;
                txt = "Você formou uma linha! Clique em uma peça adversária para capturar.";
            } else {
                bg  = ST_OPP_CAP;
                txt = "Oponente formou uma linha e está capturando...";
            }
        } else if (curr == myPlayer) {
            bg  = ST_MY_TURN;
            txt = ph == GameLogic.Phase.PLACEMENT
                ? "Sua vez  —  clique em uma interseção para colocar uma peça"
                : (selectedCell == null
                    ? "Sua vez  —  clique em uma de suas peças para selecioná-la"
                    : "Peça selecionada  —  clique em uma casa adjacente vazia");
        } else {
            bg  = ST_OPP_TURN;
            txt = "Aguardando o oponente...";
        }

        setStatus(bg, txt);
        boardPanel.repaint();
    }
}
