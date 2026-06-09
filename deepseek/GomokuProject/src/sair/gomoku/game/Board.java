package sair.gomoku.game;

/**
 * 五子棋棋盘模型（15×15 标准棋盘）
 */
public class Board {

    /** 棋盘大小 */
    public static final int SIZE = 15;

    /** 空位 */
    public static final int EMPTY = 0;
    /** 黑棋 */
    public static final int BLACK = 1;
    /** 白棋 */
    public static final int WHITE = 2;

    private int[][] grid;
    private int moveCount;
    private int lastMoveRow = -1;
    private int lastMoveCol = -1;

    public Board() {
        grid = new int[SIZE][SIZE];
        moveCount = 0;
    }

    /**
     * 判断指定位置是否有棋子
     */
    public boolean isEmpty(int row, int col) {
        return grid[row][col] == EMPTY;
    }

    /**
     * 落子
     * @return 是否落子成功
     */
    public boolean placeStone(int row, int col, int stone) {
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return false;
        if (grid[row][col] != EMPTY) return false;
        if (stone != BLACK && stone != WHITE) return false;
        grid[row][col] = stone;
        moveCount++;
        lastMoveRow = row;
        lastMoveCol = col;
        return true;
    }

    /**
     * 悔棋（移除最后一步棋子）
     */
    public boolean undoMove(int row, int col) {
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE) return false;
        if (grid[row][col] == EMPTY) return false;
        grid[row][col] = EMPTY;
        moveCount--;
        return true;
    }

    /**
     * 检查指定位置落子后是否获胜
     */
    public boolean checkWin(int row, int col) {
        int stone = grid[row][col];
        if (stone == EMPTY) return false;

        // 四个方向检查
        return countDirection(row, col, 1, 0) + countDirection(row, col, -1, 0) - 1 >= 5
            || countDirection(row, col, 0, 1) + countDirection(row, col, 0, -1) - 1 >= 5
            || countDirection(row, col, 1, 1) + countDirection(row, col, -1, -1) - 1 >= 5
            || countDirection(row, col, 1, -1) + countDirection(row, col, -1, 1) - 1 >= 5;
    }

    /**
     * 在指定方向上计算连续相同棋子的数量
     */
    private int countDirection(int row, int col, int dr, int dc) {
        int stone = grid[row][col];
        int count = 0;
        int r = row;
        int c = col;
        while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && grid[r][c] == stone) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }

    /**
     * 判断是否平局（棋盘满）
     */
    public boolean isFull() {
        return moveCount >= SIZE * SIZE;
    }

    public int getStone(int row, int col) {
        return grid[row][col];
    }

    public int getMoveCount() {
        return moveCount;
    }

    public int getLastMoveRow() {
        return lastMoveRow;
    }

    public int getLastMoveCol() {
        return lastMoveCol;
    }

    /**
     * 获取当前轮到谁下（黑先黑后交替）
     */
    public int currentPlayer() {
        return moveCount % 2 == 0 ? BLACK : WHITE;
    }

    /**
     * 重置棋盘
     */
    public void reset() {
        grid = new int[SIZE][SIZE];
        moveCount = 0;
        lastMoveRow = -1;
        lastMoveCol = -1;
    }

    /**
     * 深拷贝棋盘
     */
    public Board copy() {
        Board b = new Board();
        for (int i = 0; i < SIZE; i++) {
            System.arraycopy(this.grid[i], 0, b.grid[i], 0, SIZE);
        }
        b.moveCount = this.moveCount;
        b.lastMoveRow = this.lastMoveRow;
        b.lastMoveCol = this.lastMoveCol;
        return b;
    }
}
