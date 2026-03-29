/**
 * Estado do tabuleiro e regras do jogo Dara.
 *
 * Esta classe não tem nenhuma dependência de UI (sem Swing, sem sockets).
 * Cada jogador mantém sua própria instância localmente.
 *
 * ESTADO DETERMINÍSTICO:
 *   Ambos os jogadores aplicam as mesmas operações na mesma ordem.
 *   Como o TCP garante entrega em ordem, os dois tabuleiros ficam sempre idênticos
 *   sem precisar enviar o estado completo pela rede — apenas a ação realizada.
 *
 * FASES DO JOGO:
 *   PLACEMENT → MOVEMENT → CAPTURE → (volta para MOVEMENT) → ... → GAMEOVER
 */
public class GameLogic {

    public static final int ROWS         = 5;
    public static final int COLS         = 6;
    public static final int TOTAL_PIECES = 12; // peças por jogador

    public enum Phase { PLACEMENT, MOVEMENT, CAPTURE, GAMEOVER }

    // Tabuleiro: 0 = vazio, 1 = Jogador 1, 2 = Jogador 2
    private int[][] board;
    private Phase phase;
    private int currentPlayer; // 1 ou 2
    private int p1Placed, p2Placed; // peças já colocadas na fase PLACEMENT
    private int p1Count, p2Count;   // peças restantes na fase MOVEMENT/CAPTURE

    /** Inicializa o tabuleiro vazio com o Jogador 1 começando. */
    public GameLogic() {
        board = new int[ROWS][COLS];
        phase = Phase.PLACEMENT;
        currentPlayer = 1;
        p1Placed = p2Placed = 0;
        p1Count = p2Count = 0;
    }

    public int[][] getBoard()          { return board; }
    public Phase  getPhase()           { return phase; }
    public void   setPhase(Phase p)    { phase = p; }
    public int    getCurrentPlayer()   { return currentPlayer; }
    public int    getP1Count()         { return p1Count; }
    public int    getP2Count()         { return p2Count; }
    public int    getP1Placed()        { return p1Placed; }
    public int    getP2Placed()        { return p2Placed; }

    // ------------------------------------------------------------------ PLACEMENT

    /**
     * Verifica se o jogador atual pode colocar uma peça na posição (row, col).
     * Regra: a célula deve estar vazia E a peça não pode formar 3 em linha.
     *
     * Usa simulação: coloca a peça temporariamente, verifica a regra, desfaz.
     */
    public boolean canPlace(int row, int col) {
        if (phase != Phase.PLACEMENT) return false;
        if (board[row][col] != 0) return false;
        // Simula a jogada para verificar se formaria trio
        board[row][col] = currentPlayer;
        boolean forms3 = hasThreeInRow(row, col, currentPlayer);
        board[row][col] = 0; // desfaz a simulação
        return !forms3;
    }

    /**
     * Efetua a colocação de uma peça na posição (row, col).
     * Alterna o jogador atual e, se todos os 24 peças foram colocadas,
     * avança para a fase MOVEMENT.
     */
    public void place(int row, int col) {
        board[row][col] = currentPlayer;
        if (currentPlayer == 1) { p1Placed++; p1Count++; }
        else                    { p2Placed++; p2Count++; }
        currentPlayer = 3 - currentPlayer; // alterna entre 1 e 2: 3-1=2, 3-2=1
        if (p1Placed == TOTAL_PIECES && p2Placed == TOTAL_PIECES) {
            phase = Phase.MOVEMENT; // todos colocaram suas 12 peças
        }
    }

    // ------------------------------------------------------------------ MOVEMENT

    /**
     * Verifica se o jogador pode selecionar a peça em (row, col).
     * Só é possível selecionar a própria peça na fase MOVEMENT.
     */
    public boolean canSelect(int row, int col, int player) {
        return phase == Phase.MOVEMENT && board[row][col] == player;
    }

    /**
     * Verifica se a peça em (fromRow, fromCol) pode se mover para (toRow, toCol).
     *
     * Regras verificadas:
     *   1. Destino dentro dos limites do tabuleiro
     *   2. Destino vazio
     *   3. Movimento adjacente (horizontal ou vertical, apenas 1 casa)
     *   4. O movimento não pode formar 4 ou mais em linha
     *
     * A regra 4 usa simulação: move temporariamente, verifica, desfaz.
     */
    public boolean canMoveTo(int fromRow, int fromCol, int toRow, int toCol) {
        if (toRow < 0 || toRow >= ROWS || toCol < 0 || toCol >= COLS) return false;
        if (board[toRow][toCol] != 0) return false;
        int dr = Math.abs(toRow - fromRow);
        int dc = Math.abs(toCol - fromCol);
        if (!((dr == 1 && dc == 0) || (dr == 0 && dc == 1))) return false;
        // Simula o movimento para verificar se formaria 4+ em linha
        int player = board[fromRow][fromCol];
        board[fromRow][fromCol] = 0;
        board[toRow][toCol] = player;
        boolean forms4 = countLine(toRow, toCol, player, 0, 1) >= 4
                      || countLine(toRow, toCol, player, 1, 0) >= 4;
        board[toRow][toCol] = 0;          // desfaz a simulação
        board[fromRow][fromCol] = player;
        return !forms4;
    }

    /**
     * Efetua o movimento da peça de (fromRow, fromCol) para (toRow, toCol).
     *
     * Retorna true se o movimento formou 3 em linha (captura obrigatória).
     * Nesse caso, a fase muda para CAPTURE sem alternar o jogador atual.
     * Se não formou trio, alterna o turno normalmente.
     */
    public boolean move(int fromRow, int fromCol, int toRow, int toCol) {
        int player = board[fromRow][fromCol];
        board[fromRow][fromCol] = 0;
        board[toRow][toCol] = player;
        boolean formed = hasThreeInRow(toRow, toCol, player);
        if (formed) {
            phase = Phase.CAPTURE; // jogador deve capturar uma peça adversária
        } else {
            currentPlayer = 3 - currentPlayer; // alterna turno normalmente
        }
        return formed;
    }

    // ------------------------------------------------------------------ CAPTURE

    /**
     * Verifica se o jogador atual pode capturar a peça em (row, col).
     * Só é possível capturar peças do adversário na fase CAPTURE.
     */
    public boolean canCapture(int row, int col) {
        if (phase != Phase.CAPTURE) return false;
        return board[row][col] == (3 - currentPlayer); // é peça do oponente?
    }

    /**
     * Remove a peça adversária em (row, col) do tabuleiro.
     * Alterna o turno e volta para a fase MOVEMENT.
     *
     * Retorna true se o jogo terminou (oponente ficou com 2 ou menos peças).
     */
    public boolean capture(int row, int col) {
        board[row][col] = 0;
        if (currentPlayer == 1) p2Count--; // remove uma peça do oponente
        else                    p1Count--;
        currentPlayer = 3 - currentPlayer;
        phase = Phase.MOVEMENT;
        if (p1Count <= 2 || p2Count <= 2) {
            phase = Phase.GAMEOVER; // um jogador ficou sem peças suficientes
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ UTILITY

    /** Retorna o número do jogador vencedor (1 ou 2), ou 0 em caso de empate. */
    public int getWinner() {
        if (p2Count <= 2) return 1;
        if (p1Count <= 2) return 2;
        return 0;
    }

    /**
     * Verifica se existe 3 ou mais peças consecutivas do jogador
     * passando pela posição (row, col), em qualquer direção (horizontal ou vertical).
     */
    public boolean hasThreeInRow(int row, int col, int player) {
        return countLine(row, col, player, 0, 1) >= 3  // horizontal
            || countLine(row, col, player, 1, 0) >= 3; // vertical
    }

    /**
     * Conta peças consecutivas do jogador a partir de (row, col) na direção (dr, dc),
     * somando nos dois sentidos (frente e trás).
     *
     * Exemplos de direção:
     *   (0, 1) = horizontal (direita/esquerda)
     *   (1, 0) = vertical (baixo/cima)
     */
    private int countLine(int row, int col, int player, int dr, int dc) {
        int count = 1; // conta a própria peça
        // Conta para frente
        int r = row + dr, c = col + dc;
        while (r >= 0 && r < ROWS && c >= 0 && c < COLS && board[r][c] == player) {
            count++; r += dr; c += dc;
        }
        // Conta para trás
        r = row - dr; c = col - dc;
        while (r >= 0 && r < ROWS && c >= 0 && c < COLS && board[r][c] == player) {
            count++; r -= dr; c -= dc;
        }
        return count;
    }
}
