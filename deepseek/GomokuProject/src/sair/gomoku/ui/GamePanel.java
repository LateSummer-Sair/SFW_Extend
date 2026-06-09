package sair.gomoku.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import sair.gomoku.game.Board;
import sair.gomoku.ai.GomokuAI;

/**
 * 五子棋棋盘面板（纯棋盘绘制 + 鼠标落子），内嵌于 GameBoardPanel 中使用
 */
public class GamePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private Board board;
    private GomokuAI ai;
    private int cellSize = 36;
    private int margin = 20;
    private int panelSize;

    public static final int MODE_SINGLE = 1;
    public static final int MODE_DUAL_LOCAL = 2;
    public static final int MODE_ONLINE = 3;

    private int gameMode;
    private int playerStone;
    private int currentStone;
    private int aiStone;
    private boolean gameOver = false;
    private boolean isPlayerTurn = true;
    private int winRow = -1, winCol = -1;

    private MoveListener moveListener;

    public interface MoveListener {
        void onPlayerMove(int row, int col);
    }

    public GamePanel() {
        this.board = new Board();
        panelSize = cellSize * (Board.SIZE - 1) + margin * 2;
        setPreferredSize(new Dimension(panelSize, panelSize));
        setMinimumSize(new Dimension(panelSize, panelSize));
        setBackground(new Color(220, 180, 120));
        setOpaque(true);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleClick(e.getX(), e.getY());
            }
        });
    }

    public void setMoveListener(MoveListener listener) { this.moveListener = listener; }

    public Board getBoard() { return board; }
    public void setGameMode(int mode) { this.gameMode = mode; }
    public int getGameMode() { return gameMode; }

    public void setPlayerStone(int stone) {
        this.playerStone = stone;
        this.currentStone = Board.BLACK;
        if (gameMode == MODE_SINGLE && playerStone == Board.WHITE) {
            isPlayerTurn = false;
        } else {
            isPlayerTurn = true;
        }
    }

    public int getPlayerStone() { return playerStone; }

    public void setAi(GomokuAI ai) {
        this.ai = ai;
        this.aiStone = (playerStone == Board.BLACK) ? Board.WHITE : Board.BLACK;
    }

    public GomokuAI getAi() { return ai; }

    public boolean isGameOver() { return gameOver; }

    public void resetGame() {
        board.reset();
        gameOver = false;
        winRow = -1;
        winCol = -1;
        currentStone = Board.BLACK;
        if (gameMode == MODE_SINGLE && playerStone == Board.WHITE) {
            isPlayerTurn = false;
        } else {
            isPlayerTurn = true;
        }
        repaint();
    }

    public boolean placeRemoteStone(int row, int col, int stone) {
        if (gameOver) return false;
        if (!board.placeStone(row, col, stone)) return false;
        repaint();

        currentStone = (stone == Board.BLACK) ? Board.WHITE : Board.BLACK;

        if (board.checkWin(row, col)) {
            gameOver = true;
            winRow = row;
            winCol = col;
        } else if (board.isFull()) {
            gameOver = true;
        }
        return true;
    }

    private void handleClick(int x, int y) {
        if (gameOver) return;
        if (!isPlayerTurn) return;

        int col = Math.round((float)(x - margin) / cellSize);
        int row = Math.round((float)(y - margin) / cellSize);

        if (row < 0 || row >= Board.SIZE || col < 0 || col >= Board.SIZE) return;
        if (!board.isEmpty(row, col)) return;

        board.placeStone(row, col, currentStone);
        repaint();

        if (moveListener != null) moveListener.onPlayerMove(row, col);

        if (board.checkWin(row, col)) {
            gameOver = true;
            winRow = row;
            winCol = col;
            return;
        }
        if (board.isFull()) {
            gameOver = true;
            return;
        }

        currentStone = (currentStone == Board.BLACK) ? Board.WHITE : Board.BLACK;

        // 单人模式触发AI
        if (gameMode == MODE_SINGLE && ai != null && !gameOver) {
            isPlayerTurn = false;
            repaint();
            new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                int[] aiMove = ai.getBestMove(board, aiStone);
                board.placeStone(aiMove[0], aiMove[1], aiStone);
                currentStone = playerStone;
                isPlayerTurn = true;
                if (board.checkWin(aiMove[0], aiMove[1])) {
                    gameOver = true;
                    winRow = aiMove[0];
                    winCol = aiMove[1];
                } else if (board.isFull()) {
                    gameOver = true;
                }
                repaint();
            }).start();
        }
    }

    public void setPlayerTurn(boolean turn) { this.isPlayerTurn = turn; }
    public void setCurrentStone(int stone) { this.currentStone = stone; }
    public int getCurrentStone() { return currentStone; }
    public int getWinRow() { return winRow; }
    public int getWinCol() { return winCol; }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1.5f));
        for (int i = 0; i < Board.SIZE; i++) {
            g2.drawLine(margin, margin + i * cellSize, margin + (Board.SIZE - 1) * cellSize, margin + i * cellSize);
            g2.drawLine(margin + i * cellSize, margin, margin + i * cellSize, margin + (Board.SIZE - 1) * cellSize);
        }

        int[][] starPoints = {{3, 3}, {3, 7}, {3, 11}, {7, 3}, {7, 7}, {7, 11}, {11, 3}, {11, 7}, {11, 11}};
        for (int[] sp : starPoints) {
            g2.fillOval(margin + sp[1] * cellSize - 4, margin + sp[0] * cellSize - 4, 8, 8);
        }

        for (int r = 0; r < Board.SIZE; r++) {
            for (int c = 0; c < Board.SIZE; c++) {
                int stone = board.getStone(r, c);
                if (stone != Board.EMPTY) {
                    int sx = margin + c * cellSize - cellSize / 2 + 2;
                    int sy = margin + r * cellSize - cellSize / 2 + 2;
                    int size = cellSize - 6;
                    g2.setColor(stone == Board.BLACK ? Color.BLACK : Color.WHITE);
                    g2.fillOval(sx, sy, size, size);
                    if (r == board.getLastMoveRow() && c == board.getLastMoveCol()) {
                        g2.setColor(Color.RED);
                        g2.drawOval(sx + 4, sy + 4, size - 8, size - 8);
                    }
                    if (r == winRow && c == winCol) {
                        g2.setColor(Color.RED);
                        g2.setStroke(new BasicStroke(2.5f));
                        g2.drawOval(sx, sy, size, size);
                    }
                }
            }
        }

        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("Arial", Font.PLAIN, 9));
        for (int i = 0; i < Board.SIZE; i++) {
            g2.drawString(String.valueOf((char)('A' + i)), margin + i * cellSize - 4, margin - 5);
            g2.drawString(String.valueOf(i + 1), margin - 16, margin + i * cellSize + 4);
        }
    }

    @Override
    public Dimension getPreferredSize() { return new Dimension(panelSize, panelSize); }
}
