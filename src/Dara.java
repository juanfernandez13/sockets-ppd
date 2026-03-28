import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

public class Dara {

    static final Color BG      = new Color(22,  27,  42);
    static final Color CARD    = new Color(32,  39,  60);
    static final Color BORDER  = new Color(55,  68, 100);
    static final Color ACCENT  = new Color(99, 162, 255);
    static final Color TEXT    = Color.WHITE;
    static final Color SUBTEXT = new Color(140, 155, 195);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Dara::showLaunchDialog);
    }

    // Estático para poder ser chamado do GameWindow (ROOM_FULL)
    public static void showLaunchDialog() {
        JFrame frame = new JFrame("Dara — Iniciar Partida");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);

        JPanel root = new JPanel(new BorderLayout(0, 20));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(32, 44, 32, 44));

        // ── Cabeçalho ────────────────────────────────────────────────────────
        JPanel header = new JPanel(new GridLayout(2, 1, 0, 4));
        header.setBackground(BG);
        JLabel title = new JLabel("DARA", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 52));
        title.setForeground(ACCENT);
        JLabel sub = new JLabel("Jogo de Tabuleiro Africano  •  5×6  •  2 Jogadores",
                                SwingConstants.CENTER);
        sub.setFont(new Font("SansSerif", Font.PLAIN, 13));
        sub.setForeground(SUBTEXT);
        header.add(title);
        header.add(sub);
        root.add(header, BorderLayout.NORTH);

        // ── Cards: FORM e WAIT ───────────────────────────────────────────────
        CardLayout cardLayout = new CardLayout();
        JPanel cards = new JPanel(cardLayout);
        cards.setBackground(BG);
        cards.add(buildForm(frame, cards, cardLayout), "FORM");
        root.add(cards, BorderLayout.CENTER);

        // ── Rodapé ───────────────────────────────────────────────────────────
        JLabel note = new JLabel(
            "<html><center><font color='#7a8ab0'>"
            + "O modo (Servidor / Cliente) é detectado automaticamente pela porta.<br>"
            + "Quem entrar primeiro na porta vira Servidor (Jogador 1)."
            + "</font></center></html>", SwingConstants.CENTER);
        note.setFont(new Font("SansSerif", Font.ITALIC, 11));
        root.add(note, BorderLayout.SOUTH);

        frame.add(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ── Painel do formulário ──────────────────────────────────────────────────
    private static JPanel buildForm(JFrame frame, JPanel cards, CardLayout cardLayout) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(CARD);
        form.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(BORDER, 1), new EmptyBorder(22, 28, 22, 28)));

        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;

        JTextField hostField = styledField("localhost");
        JTextField portField = styledField("9090");
        JTextField nameField = styledField("Jogador");

        addFormRow(form, g, 0, "Host do servidor:", hostField);
        addFormRow(form, g, 1, "Porta:",            portField);
        addFormRow(form, g, 2, "Seu nome (chat):",  nameField);

        JButton btn = accentButton("▶   ENTRAR NA PARTIDA");
        g.gridx = 0; g.gridy = 3; g.gridwidth = 2;
        g.insets = new Insets(20, 0, 0, 0);
        form.add(btn, g);

        btn.addActionListener(e -> {
            try {
                String host = hostField.getText().trim();
                int    port = Integer.parseInt(portField.getText().trim());
                String name = nameField.getText().trim().isEmpty()
                              ? "Jogador" : nameField.getText().trim();
                startConnection(frame, cards, cardLayout, host, port, name);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame,
                    "Porta inválida! Use um número (ex: 9090).",
                    "Erro", JOptionPane.ERROR_MESSAGE);
            }
        });

        frame.getRootPane().setDefaultButton(btn);
        return form;
    }

    // ── Inicia conexão e mostra painel de espera ──────────────────────────────
    private static void startConnection(JFrame frame, JPanel cards, CardLayout cardLayout,
                                        String host, int port, String name) {
        JLabel statusLbl = new JLabel("Verificando porta " + port + "...", SwingConstants.CENTER);
        statusLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusLbl.setForeground(SUBTEXT);

        Connection[] connRef = {null};

        JPanel waitPanel = buildWaitPanel(port, statusLbl, connRef, () -> {
            cardLayout.show(cards, "FORM");
            frame.pack();
        });
        cards.add(waitPanel, "WAIT");
        cardLayout.show(cards, "WAIT");
        frame.pack();

        Connection conn = new Connection(host, port);
        connRef[0] = conn;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                conn.connectTCP(s -> SwingUtilities.invokeLater(() -> statusLbl.setText(s)));
                return null;
            }
            @Override
            protected void done() {
                try {
                    get();
                    frame.dispose();
                    @SuppressWarnings("unused")
                    GameWindow gw = new GameWindow(conn, conn.isServer(), name);
                } catch (java.util.concurrent.ExecutionException | InterruptedException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (!conn.isCancelled() && !(cause instanceof InterruptedException)) {
                        String msg = cause.getMessage() != null
                                     ? cause.getMessage()
                                     : cause.getClass().getSimpleName();
                        JOptionPane.showMessageDialog(frame,
                            "Erro de conexão:\n" + msg,
                            "Erro", JOptionPane.ERROR_MESSAGE);
                    }
                    cards.remove(waitPanel);
                    cardLayout.show(cards, "FORM");
                    frame.pack();
                }
            }
        }.execute();
    }

    // ── Painel de aguardo ────────────────────────────────────────────────────
    private static JPanel buildWaitPanel(int port,
                                          JLabel statusLbl, Connection[] connRef,
                                          Runnable onCancel) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(BORDER, 1), new EmptyBorder(32, 44, 32, 44)));

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(280, 6));
        bar.setBorderPainted(false);

        JLabel icon = new JLabel("⏳", SwingConstants.CENTER);
        icon.setFont(new Font("SansSerif", Font.PLAIN, 36));

        JLabel titleLbl = new JLabel("Detectando modo...", SwingConstants.CENTER);
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 16));
        titleLbl.setForeground(TEXT);

        JButton cancelBtn = new JButton("Cancelar");
        cancelBtn.setBackground(new Color(70, 40, 40));
        cancelBtn.setForeground(TEXT);
        cancelBtn.setOpaque(true);
        cancelBtn.setBorderPainted(false);
        cancelBtn.setFocusPainted(false);
        cancelBtn.setBorder(new EmptyBorder(8, 24, 8, 24));
        cancelBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cancelBtn.addActionListener(e -> {
            if (connRef[0] != null) connRef[0].cancel();
            onCancel.run();
        });

        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridwidth = 1;

        JLabel portInfo = new JLabel("Porta: " + port, SwingConstants.CENTER);
        portInfo.setFont(new Font("SansSerif", Font.BOLD, 13));
        portInfo.setForeground(ACCENT);

        int row = 0;
        g.gridy = row++; g.insets = new Insets(0, 0, 12, 0); panel.add(icon, g);
        g.gridy = row++; g.insets = new Insets(0, 0, 8,  0); panel.add(titleLbl, g);
        g.gridy = row++; g.insets = new Insets(0, 0, 6,  0); panel.add(portInfo, g);
        g.gridy = row++; g.insets = new Insets(0, 0, 12, 0); panel.add(statusLbl, g);
        g.gridy = row++; g.insets = new Insets(0, 0, 20, 0); panel.add(bar, g);
        g.gridy = row;   g.insets = new Insets(0, 60, 0, 60); panel.add(cancelBtn, g);

        return panel;
    }

    // ── Helpers de UI ────────────────────────────────────────────────────────

    static JTextField styledField(String text) {
        JTextField f = new JTextField(text, 20);
        f.setBackground(new Color(22, 27, 42));
        f.setForeground(TEXT);
        f.setCaretColor(TEXT);
        f.setFont(new Font("SansSerif", Font.PLAIN, 13));
        f.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(BORDER), new EmptyBorder(5, 8, 5, 8)));
        return f;
    }

    static JButton accentButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.BOLD, 14));
        btn.setBackground(ACCENT);
        btn.setForeground(new Color(15, 20, 38));
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(11, 24, 11, 24));
        return btn;
    }

    private static void addFormRow(JPanel p, GridBagConstraints g,
                                   int row, String label, JComponent field) {
        JLabel lbl = new JLabel(label);
        lbl.setForeground(SUBTEXT);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.gridwidth = 1; g.weightx = 0;
        g.gridx = 0; g.gridy = row; g.insets = new Insets(7, 0, 7, 14);
        p.add(lbl, g);
        g.gridx = 1; g.weightx = 1; g.insets = new Insets(7, 0, 7, 0);
        p.add(field, g);
    }
}
