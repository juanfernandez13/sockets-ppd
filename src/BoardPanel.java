import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class BoardPanel extends JPanel {

    static final Color P1_COLOR = new Color(215, 55, 55);    // vermelho
    static final Color P2_COLOR = new Color(55, 110, 215);   // azul

    private static final int CELL   = 92;
    private static final int MARGIN = 88;
    private static final int R      = 23;   // raio base da peça
    private static final int R_HOV  = 29;   // raio com hover (scale up)

    private static final Color BOARD_BG    = new Color(185, 148, 82);
    private static final Color BOARD_DARK  = new Color(155, 118, 52);
    private static final Color GRID_COLOR  = new Color(90,  62, 22);
    private static final Color DOT_COLOR   = new Color(70,  45, 12);
    private static final Color LABEL_COLOR = new Color(50,  30,  5);

    private final GameWindow game;
    private int[] hoveredCell = null;   // célula atualmente sob o cursor

    public BoardPanel(GameWindow game) {
        this.game = game;
        int w = (GameLogic.COLS - 1) * CELL + 2 * MARGIN;
        int h = (GameLogic.ROWS - 1) * CELL + 2 * MARGIN;
        setPreferredSize(new Dimension(w, h));
        setBackground(GameWindow.BG);

        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int col = Math.round((e.getX() - MARGIN) / (float) CELL);
                int row = Math.round((e.getY() - MARGIN) / (float) CELL);
                if (inBounds(row, col)) game.onBoardClick(row, col);
            }
            @Override public void mouseExited(MouseEvent e) {
                if (hoveredCell != null) { hoveredCell = null; repaint(); }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                updateCursorAndHover(e.getX(), e.getY());
            }
        });
    }

    // ── Cursor e hover ───────────────────────────────────────────────────────
    private void updateCursorAndHover(int mx, int my) {
        int col = Math.round((mx - MARGIN) / (float) CELL);
        int row = Math.round((my - MARGIN) / (float) CELL);

        if (!inBounds(row, col)) {
            setCursor(Cursor.getDefaultCursor());
            if (hoveredCell != null) { hoveredCell = null; repaint(); }
            return;
        }

        GameLogic logic = game.getLogic();
        int myP  = game.getMyPlayer();
        int curr = logic.getCurrentPlayer();
        GameLogic.Phase ph = logic.getPhase();
        int[] sel = game.selectedCell;
        int[][] board = logic.getBoard();

        boolean interactive = false;

        if (curr == myP && ph != GameLogic.Phase.GAMEOVER) {
            if (ph == GameLogic.Phase.CAPTURE && logic.canCapture(row, col)) {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                interactive = board[row][col] != 0;
            } else if (ph == GameLogic.Phase.PLACEMENT && logic.canPlace(row, col)) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else if (ph == GameLogic.Phase.MOVEMENT) {
                boolean canMove = sel != null && logic.canMoveTo(sel[0], sel[1], row, col);
                boolean canSel  = sel == null && logic.canSelect(row, col, myP);
                if (canMove || canSel) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    interactive = board[row][col] != 0;  // só escala peças, não células vazias
                } else {
                    setCursor(Cursor.getDefaultCursor());
                }
            } else {
                setCursor(Cursor.getDefaultCursor());
            }
        } else {
            setCursor(Cursor.getDefaultCursor());
        }

        // Atualiza a célula em hover (somente peças interativas recebem scale)
        int[] newHover = interactive ? new int[]{row, col} : null;
        boolean changed = !java.util.Arrays.equals(hoveredCell, newHover);
        hoveredCell = newHover;
        if (changed) repaint();
    }

    // ── Renderização principal ────────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);

        GameLogic logic = game.getLogic();
        int[][]   board = logic.getBoard();
        int[]     sel   = game.selectedCell;
        GameLogic.Phase phase = logic.getPhase();

        drawBoard(g2);
        drawMoveHints(g2, logic, board, sel, phase);
        drawPieces(g2, logic, board, sel, phase);
        drawWaitingOverlay(g2, logic, phase);
    }

    // ── Grade e fundo ────────────────────────────────────────────────────────
    private void drawBoard(Graphics2D g2) {
        int bx = MARGIN - 62, by = MARGIN - 62;
        int bw = (GameLogic.COLS - 1) * CELL + 124;
        int bh = (GameLogic.ROWS - 1) * CELL + 124;

        g2.setColor(new Color(0, 0, 0, 60));
        g2.fillRoundRect(bx + 4, by + 4, bw, bh, 14, 14);

        GradientPaint wood = new GradientPaint(bx, by, BOARD_BG, bx + bw, by + bh, BOARD_DARK);
        g2.setPaint(wood);
        g2.fillRoundRect(bx, by, bw, bh, 14, 14);

        g2.setColor(GRID_COLOR.darker());
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(bx, by, bw, bh, 14, 14);

        g2.setColor(GRID_COLOR);
        g2.setStroke(new BasicStroke(2f));
        for (int r = 0; r < GameLogic.ROWS; r++)
            g2.drawLine(px(0), py(r), px(GameLogic.COLS - 1), py(r));
        for (int c = 0; c < GameLogic.COLS; c++)
            g2.drawLine(px(c), py(0), px(c), py(GameLogic.ROWS - 1));

        g2.setColor(LABEL_COLOR);
        g2.setFont(new Font("SansSerif", Font.BOLD, 13));
        FontMetrics fm = g2.getFontMetrics();
        for (int c = 0; c < GameLogic.COLS; c++) {
            String s = String.valueOf((char) ('A' + c));
            g2.drawString(s, px(c) - fm.stringWidth(s) / 2, py(0) - 34);
        }
        for (int r = 0; r < GameLogic.ROWS; r++) {
            String s = String.valueOf(r + 1);
            g2.drawString(s, px(0) - 38, py(r) + fm.getAscent() / 2 - 1);
        }

        for (int r = 0; r < GameLogic.ROWS; r++)
            for (int c = 0; c < GameLogic.COLS; c++) {
                g2.setColor(DOT_COLOR);
                g2.fillOval(px(c) - 4, py(r) - 4, 8, 8);
            }
    }

    // ── Dicas de movimento ───────────────────────────────────────────────────
    private void drawMoveHints(Graphics2D g2, GameLogic logic, int[][] board,
                                int[] sel, GameLogic.Phase phase) {
        int myP  = game.getMyPlayer();
        int curr = logic.getCurrentPlayer();

        if (sel != null && phase == GameLogic.Phase.MOVEMENT && curr == myP) {
            int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
            for (int[] d : dirs) {
                int nr = sel[0] + d[0], nc = sel[1] + d[1];
                // canMoveTo já valida adjacência, célula vazia E limite de 4 em linha
                if (logic.canMoveTo(sel[0], sel[1], nr, nc)) {
                    g2.setColor(new Color(60, 220, 100, 140));
                    g2.fillOval(px(nc) - R, py(nr) - R, 2*R, 2*R);
                    g2.setColor(new Color(40, 180, 80, 200));
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawOval(px(nc) - R, py(nr) - R, 2*R, 2*R);
                }
            }
        }

        if (phase == GameLogic.Phase.PLACEMENT && curr == myP) {
            for (int r = 0; r < GameLogic.ROWS; r++)
                for (int c = 0; c < GameLogic.COLS; c++)
                    if (logic.canPlace(r, c)) {
                        g2.setColor(new Color(100, 220, 130, 55));
                        g2.fillOval(px(c) - R + 4, py(r) - R + 4, 2*R - 8, 2*R - 8);
                    }
        }
    }

    // ── Peças ────────────────────────────────────────────────────────────────
    private void drawPieces(Graphics2D g2, GameLogic logic, int[][] board,
                             int[] sel, GameLogic.Phase phase) {
        for (int r = 0; r < GameLogic.ROWS; r++) {
            for (int c = 0; c < GameLogic.COLS; c++) {
                if (board[r][c] == 0) continue;

                int x = px(c), y = py(r);
                boolean isSelected   = sel != null && sel[0] == r && sel[1] == c;
                boolean isTargetable = phase == GameLogic.Phase.CAPTURE && logic.canCapture(r, c);
                boolean isHovered    = hoveredCell != null && hoveredCell[0] == r && hoveredCell[1] == c;

                // Raio efetivo: scale up no hover
                int rad = isHovered ? R_HOV : R;

                // Anel de destaque (abaixo do scale)
                if (isSelected) {
                    g2.setColor(new Color(255, 235, 0, 220));
                    g2.fillOval(x - rad - 6, y - rad - 6, 2*(rad+6), 2*(rad+6));
                } else if (isTargetable) {
                    g2.setColor(new Color(255, 60, 60, 180));
                    g2.fillOval(x - rad - 6, y - rad - 6, 2*(rad+6), 2*(rad+6));
                    g2.setColor(new Color(255, 160, 160, 80));
                    g2.fillOval(x - rad - 11, y - rad - 11, 2*(rad+11), 2*(rad+11));
                }

                Color base = board[r][c] == 1 ? P1_COLOR : P2_COLOR;

                // Sombra
                g2.setColor(new Color(0, 0, 0, 65));
                g2.fillOval(x - rad + 3, y - rad + 4, 2*rad, 2*rad);

                // Corpo com gradiente
                GradientPaint gp = new GradientPaint(
                    x - rad * 0.4f, y - rad * 0.4f, base.brighter().brighter(),
                    x + rad * 0.5f, y + rad * 0.6f, base.darker()
                );
                g2.setPaint(gp);
                g2.fillOval(x - rad, y - rad, 2*rad, 2*rad);

                // Brilho
                g2.setColor(new Color(255, 255, 255, isHovered ? 80 : 50));
                g2.fillOval(x - rad/2, y - rad + 3, rad, rad / 2);

                // Borda
                g2.setColor(base.darker().darker());
                g2.setStroke(new BasicStroke(isHovered ? 2f : 1.5f));
                g2.drawOval(x - rad, y - rad, 2*rad, 2*rad);
            }
        }
    }

    // ── Overlay "Aguardando oponente" ────────────────────────────────────────
    private void drawWaitingOverlay(Graphics2D g2, GameLogic logic, GameLogic.Phase phase) {
        int myP  = game.getMyPlayer();
        int curr = logic.getCurrentPlayer();
        if (myP == 0) return;
        if (phase == GameLogic.Phase.GAMEOVER) return;
        if (curr == myP) return;   // é minha vez — sem overlay

        // Centro do tabuleiro
        int cx = (px(0) + px(GameLogic.COLS - 1)) / 2;
        int cy = (py(0) + py(GameLogic.ROWS - 1)) / 2;

        String line1 = phase == GameLogic.Phase.CAPTURE
                ? "Oponente capturando..."
                : "Aguardando oponente...";

        int boxW = 270, boxH = 58;
        int bx = cx - boxW / 2, by = cy - boxH / 2;

        // Fundo semi-transparente
        g2.setColor(new Color(8, 12, 26, 195));
        g2.fillRoundRect(bx, by, boxW, boxH, 18, 18);

        // Borda sutil
        g2.setColor(new Color(80, 100, 160, 180));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(bx, by, boxW, boxH, 18, 18);

        // Texto
        g2.setFont(new Font("SansSerif", Font.BOLD, 15));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(new Color(180, 200, 255));
        g2.drawString(line1, cx - fm.stringWidth(line1) / 2, cy + fm.getAscent() / 2 - 2);
    }

    // ── Utilitários ──────────────────────────────────────────────────────────
    private int px(int col) { return MARGIN + col * CELL; }
    private int py(int row) { return MARGIN + row * CELL; }
    private boolean inBounds(int r, int c) {
        return r >= 0 && r < GameLogic.ROWS && c >= 0 && c < GameLogic.COLS;
    }
}
