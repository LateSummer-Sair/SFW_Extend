package sair.gomoku.ai;

import java.util.Random;
import sair.gomoku.game.Board;

/**
 * 五子棋AI，支持三种难度：初级（随机）、中级（评分）、高级（Minimax+Alpha-Beta剪枝）
 */
public class GomokuAI {

    public static final int EASY = 0;
    public static final int MEDIUM = 1;
    public static final int HARD = 2;

    private int difficulty;
    private Random random;

    // 棋型评分权重
    private static final int FIVE = 1000000;
    private static final int LIVE_FOUR = 100000;
    private static final int RUSH_FOUR = 10000;
    private static final int LIVE_THREE = 1000;
    private static final int SLEEP_THREE = 100;
    private static final int LIVE_TWO = 10;
    private static final int SLEEP_TWO = 1;

    // 搜索范围（围绕已有棋子）
    private static final int SEARCH_RANGE = 2;

    public GomokuAI(int difficulty) {
        this.difficulty = difficulty;
        this.random = new Random();
    }

    /**
     * 获取AI的最佳落子位置
     * @return int[]{row, col}
     */
    public int[] getBestMove(Board board, int aiStone) {
        switch (difficulty) {
            case EASY:
                return getEasyMove(board);
            case MEDIUM:
                return getMediumMove(board, aiStone);
            case HARD:
                return getHardMove(board, aiStone);
            default:
                return getEasyMove(board);
        }
    }

    /**
     * 初级：在空位中随机选择
     */
    private int[] getEasyMove(Board board) {
        // 优先占据中心附近
        if (board.isEmpty(Board.SIZE / 2, Board.SIZE / 2)) {
            return new int[]{Board.SIZE / 2, Board.SIZE / 2};
        }

        // 收集有相邻棋子的空位
        java.util.ArrayList<int[]> candidates = new java.util.ArrayList<>();
        for (int r = 0; r < Board.SIZE; r++) {
            for (int c = 0; c < Board.SIZE; c++) {
                if (board.isEmpty(r, c) && hasNeighbor(board, r, c)) {
                    candidates.add(new int[]{r, c});
                }
            }
        }

        if (!candidates.isEmpty()) {
            return candidates.get(random.nextInt(candidates.size()));
        }

        // 全空时下中心
        return new int[]{Board.SIZE / 2, Board.SIZE / 2};
    }

    /**
     * 中级：评分法 - 对每个可落子位置打分，选最高分
     */
    private int[] getMediumMove(Board board, int aiStone) {
        int humanStone = (aiStone == Board.BLACK) ? Board.WHITE : Board.BLACK;
        return evaluateAllMoves(board, aiStone, humanStone, 0);
    }

    /**
     * 高级：Minimax + Alpha-Beta 剪枝，搜索深度4
     */
    private int[] getHardMove(Board board, int aiStone) {
        int humanStone = (aiStone == Board.BLACK) ? Board.WHITE : Board.BLACK;
        int bestScore = Integer.MIN_VALUE;
        int bestRow = -1;
        int bestCol = -1;

        // 如果棋盘为空，下中心
        if (board.getMoveCount() == 0) {
            return new int[]{Board.SIZE / 2, Board.SIZE / 2};
        }

        java.util.ArrayList<int[]> moves = getCandidateMoves(board);
        for (int[] move : moves) {
            int r = move[0], c = move[1];
            board.placeStone(r, c, aiStone);
            int score = minimax(board, 3, Integer.MIN_VALUE, Integer.MAX_VALUE, false, aiStone, humanStone);
            board.undoMove(r, c);
            if (score > bestScore) {
                bestScore = score;
                bestRow = r;
                bestCol = c;
            }
        }

        if (bestRow == -1) {
            return getMediumMove(board, aiStone);
        }
        return new int[]{bestRow, bestCol};
    }

    private int minimax(Board board, int depth, int alpha, int beta, boolean isMax, int aiStone, int humanStone) {
        // 检查是否有人获胜
        int lastR = board.getLastMoveRow();
        int lastC = board.getLastMoveCol();
        if (lastR >= 0 && board.checkWin(lastR, lastC)) {
            return isMax ? -(FIVE / (4 - depth + 1)) : (FIVE / (4 - depth + 1));
        }

        if (depth == 0 || board.isFull()) {
            return evaluateBoard(board, aiStone) - evaluateBoard(board, humanStone);
        }

        java.util.ArrayList<int[]> moves = getCandidateMoves(board);

        if (isMax) {
            int maxEval = Integer.MIN_VALUE;
            for (int[] move : moves) {
                board.placeStone(move[0], move[1], aiStone);
                int eval = minimax(board, depth - 1, alpha, beta, false, aiStone, humanStone);
                board.undoMove(move[0], move[1]);
                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) break;
            }
            return maxEval;
        } else {
            int minEval = Integer.MAX_VALUE;
            for (int[] move : moves) {
                board.placeStone(move[0], move[1], humanStone);
                int eval = minimax(board, depth - 1, alpha, beta, true, aiStone, humanStone);
                board.undoMove(move[0], move[1]);
                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);
                if (beta <= alpha) break;
            }
            return minEval;
        }
    }

    /**
     * 评估所有可落子位置，返回最佳位置
     */
    private int[] evaluateAllMoves(Board board, int aiStone, int humanStone, int depth) {
        int bestScore = Integer.MIN_VALUE;
        int bestRow = -1;
        int bestCol = -1;

        if (board.getMoveCount() == 0) {
            return new int[]{Board.SIZE / 2, Board.SIZE / 2};
        }

        java.util.ArrayList<int[]> moves = getCandidateMoves(board);
        for (int[] move : moves) {
            int r = move[0], c = move[1];
            // AI的得分 + 阻挡对手的得分
            int aiScore = getPositionScore(board, r, c, aiStone);
            int blockScore = getPositionScore(board, r, c, humanStone);
            int totalScore = aiScore + (int)(blockScore * 0.9);

            if (totalScore > bestScore) {
                bestScore = totalScore;
                bestRow = r;
                bestCol = c;
            }
        }

        if (bestRow == -1) {
            return new int[]{Board.SIZE / 2, Board.SIZE / 2};
        }
        return new int[]{bestRow, bestCol};
    }

    /**
     * 获取候选落子位置（已有棋子周围SEARCH_RANGE范围内的空位）
     */
    private java.util.ArrayList<int[]> getCandidateMoves(Board board) {
        java.util.ArrayList<int[]> moves = new java.util.ArrayList<>();
        boolean[][] visited = new boolean[Board.SIZE][Board.SIZE];

        for (int r = 0; r < Board.SIZE; r++) {
            for (int c = 0; c < Board.SIZE; c++) {
                if (board.getStone(r, c) != Board.EMPTY) {
                    for (int dr = -SEARCH_RANGE; dr <= SEARCH_RANGE; dr++) {
                        for (int dc = -SEARCH_RANGE; dc <= SEARCH_RANGE; dc++) {
                            int nr = r + dr;
                            int nc = c + dc;
                            if (nr >= 0 && nr < Board.SIZE && nc >= 0 && nc < Board.SIZE
                                && board.isEmpty(nr, nc) && !visited[nr][nc]) {
                                visited[nr][nc] = true;
                                moves.add(new int[]{nr, nc});
                            }
                        }
                    }
                }
            }
        }
        return moves;
    }

    /**
     * 评估整个棋盘对指定玩家的得分
     */
    private int evaluateBoard(Board board, int stone) {
        int score = 0;
        int[][] directions = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

        for (int r = 0; r < Board.SIZE; r++) {
            for (int c = 0; c < Board.SIZE; c++) {
                if (board.getStone(r, c) == stone) {
                    for (int[] dir : directions) {
                        score += evaluateLine(board, r, c, dir[0], dir[1], stone);
                    }
                }
            }
        }
        return score;
    }

    /**
     * 评估指定位置对指定棋手的得分
     */
    private int getPositionScore(Board board, int row, int col, int stone) {
        if (!board.isEmpty(row, col)) return 0;

        board.placeStone(row, col, stone);
        int score = evaluatePosition(board, row, col, stone);
        board.undoMove(row, col);
        return score;
    }

    private int evaluatePosition(Board board, int row, int col, int stone) {
        int score = 0;
        int[][] directions = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int[] dir : directions) {
            score += evaluateLineAt(board, row, col, dir[0], dir[1], stone);
        }
        return score;
    }

    private int evaluateLineAt(Board board, int row, int col, int dr, int dc, int stone) {
        int count = 1;
        int openLeft = 0;
        int openRight = 0;

        // 正向
        int r = row + dr;
        int c = col + dc;
        while (r >= 0 && r < Board.SIZE && c >= 0 && c < Board.SIZE && board.getStone(r, c) == stone) {
            count++;
            r += dr;
            c += dc;
        }
        if (r >= 0 && r < Board.SIZE && c >= 0 && c < Board.SIZE && board.isEmpty(r, c)) {
            openRight = 1;
        }

        // 反向
        r = row - dr;
        c = col - dc;
        while (r >= 0 && r < Board.SIZE && c >= 0 && c < Board.SIZE && board.getStone(r, c) == stone) {
            count++;
            r -= dr;
            c -= dc;
        }
        if (r >= 0 && r < Board.SIZE && c >= 0 && c < Board.SIZE && board.isEmpty(r, c)) {
            openLeft = 1;
        }

        return scorePattern(count, openLeft + openRight);
    }

    private int evaluateLine(Board board, int row, int col, int dr, int dc, int stone) {
        // 只计算每段连子的第一个位置（避免重复计算）
        int prevR = row - dr;
        int prevC = col - dc;
        if (prevR >= 0 && prevR < Board.SIZE && prevC >= 0 && prevC < Board.SIZE
            && board.getStone(prevR, prevC) == stone) {
            return 0;
        }

        int count = 1;
        int openEnds = 0;

        int r = row + dr;
        int c = col + dc;
        while (r >= 0 && r < Board.SIZE && c >= 0 && c < Board.SIZE && board.getStone(r, c) == stone) {
            count++;
            r += dr;
            c += dc;
        }
        if (r >= 0 && r < Board.SIZE && c >= 0 && c < Board.SIZE && board.isEmpty(r, c)) {
            openEnds++;
        }

        if (prevR >= 0 && prevR < Board.SIZE && prevC >= 0 && prevC < Board.SIZE && board.isEmpty(prevR, prevC)) {
            openEnds++;
        }

        return scorePattern(count, openEnds);
    }

    private int scorePattern(int count, int openEnds) {
        if (count >= 5) return FIVE;
        if (count == 4) {
            if (openEnds == 2) return LIVE_FOUR;
            if (openEnds == 1) return RUSH_FOUR;
        }
        if (count == 3) {
            if (openEnds == 2) return LIVE_THREE;
            if (openEnds == 1) return SLEEP_THREE;
        }
        if (count == 2) {
            if (openEnds == 2) return LIVE_TWO;
            if (openEnds == 1) return SLEEP_TWO;
        }
        return 0;
    }

    /**
     * 检查指定位置周围是否有棋子
     */
    private boolean hasNeighbor(Board board, int row, int col) {
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int nr = row + dr;
                int nc = col + dc;
                if (nr >= 0 && nr < Board.SIZE && nc >= 0 && nc < Board.SIZE
                    && board.getStone(nr, nc) != Board.EMPTY) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(int difficulty) {
        this.difficulty = difficulty;
    }
}
