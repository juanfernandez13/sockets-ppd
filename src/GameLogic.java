public class GameLogic {

    public static final int ROWS = 5;
    public static final int COLS = 6;
    public static final int TOTAL_PIECES = 12;

    public enum Phase { PLACEMENT, MOVEMENT, CAPTURE, GAMEOVER }

    private int[][] board;
    private Phase phase;
    private int currentPlayer; // 1 or 2
    private int p1Placed, p2Placed;
    private int p1Count, p2Count;

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

    public boolean canPlace(int row, int col) {
        if (phase != Phase.PLACEMENT) return false;
        if (board[row][col] != 0) return false;
        board[row][col] = currentPlayer;
        boolean forms3 = hasThreeInRow(row, col, currentPlayer);
        board[row][col] = 0;
        return !forms3;
    }

    public void place(int row, int col) {
        board[row][col] = currentPlayer;
        if (currentPlayer == 1) { p1Placed++; p1Count++; }
        else                    { p2Placed++; p2Count++; }
        currentPlayer = 3 - currentPlayer;
        if (p1Placed == TOTAL_PIECES && p2Placed == TOTAL_PIECES) {
            phase = Phase.MOVEMENT;
        }
    }

    // ------------------------------------------------------------------ MOVEMENT

    public boolean canSelect(int row, int col, int player) {
        return phase == Phase.MOVEMENT && board[row][col] == player;
    }

    public boolean canMoveTo(int fromRow, int fromCol, int toRow, int toCol) {
        if (toRow < 0 || toRow >= ROWS || toCol < 0 || toCol >= COLS) return false;
        if (board[toRow][toCol] != 0) return false;
        int dr = Math.abs(toRow - fromRow);
        int dc = Math.abs(toCol - fromCol);
        if (!((dr == 1 && dc == 0) || (dr == 0 && dc == 1))) return false;
        // Simula o movimento: proíbe se forma 4+ em linha (apenas trio é permitido)
        int player = board[fromRow][fromCol];
        board[fromRow][fromCol] = 0;
        board[toRow][toCol] = player;
        boolean forms4 = countLine(toRow, toCol, player, 0, 1) >= 4
                      || countLine(toRow, toCol, player, 1, 0) >= 4;
        board[toRow][toCol] = 0;
        board[fromRow][fromCol] = player;
        return !forms4;
    }

    /** Returns true if the move forms a 3-in-a-row (capture required). */
    public boolean move(int fromRow, int fromCol, int toRow, int toCol) {
        int player = board[fromRow][fromCol];
        board[fromRow][fromCol] = 0;
        board[toRow][toCol] = player;
        boolean formed = hasThreeInRow(toRow, toCol, player);
        if (formed) {
            phase = Phase.CAPTURE;
        } else {
            currentPlayer = 3 - currentPlayer;
        }
        return formed;
    }

    // ------------------------------------------------------------------ CAPTURE

    public boolean canCapture(int row, int col) {
        if (phase != Phase.CAPTURE) return false;
        return board[row][col] == (3 - currentPlayer);
    }

    /** Returns true if the game ends after this capture. */
    public boolean capture(int row, int col) {
        board[row][col] = 0;
        if (currentPlayer == 1) p2Count--;
        else                    p1Count--;
        currentPlayer = 3 - currentPlayer;
        phase = Phase.MOVEMENT;
        if (p1Count <= 2 || p2Count <= 2) {
            phase = Phase.GAMEOVER;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ UTILITY

    public int getWinner() {
        if (p2Count <= 2) return 1;
        if (p1Count <= 2) return 2;
        return 0;
    }

    public boolean hasThreeInRow(int row, int col, int player) {
        return countLine(row, col, player, 0, 1) >= 3
            || countLine(row, col, player, 1, 0) >= 3;
    }

    private int countLine(int row, int col, int player, int dr, int dc) {
        int count = 1;
        int r = row + dr, c = col + dc;
        while (r >= 0 && r < ROWS && c >= 0 && c < COLS && board[r][c] == player) {
            count++; r += dr; c += dc;
        }
        r = row - dr; c = col - dc;
        while (r >= 0 && r < ROWS && c >= 0 && c < COLS && board[r][c] == player) {
            count++; r -= dr; c -= dc;
        }
        return count;
    }
}
