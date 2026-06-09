package sair.scsys.abstr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class ServerManager {
	private ServerSocket serverSocket = null;

	private ExecutorService exec = null;

	private boolean iscon = false;

	final List<Socket> socketList = new ArrayList<>();

	private final List<Integer> isRemoveSocketList = new LinkedList<>();

	public ServerManager(int port) {
		try {
			this.serverSocket = new ServerSocket(port);
			this.exec = new ThreadPoolExecutor(// 自定义一个线程池

					20, // coreSize

					120, // maxSize

					60, // 60s

					TimeUnit.SECONDS, new ArrayBlockingQueue<>(360) // 有界队列

					, Executors.defaultThreadFactory()

					, new ThreadPoolExecutor.AbortPolicy()// 拒绝策略

			);
		} catch (IOException e) {
			this.iscon = true;
			// SaLogger.outLogger(e);
		}
	}

	public synchronized void startServer() {
		if (!this.iscon) {
			this.iscon = true;
			(new Thread() {
				public void run() {
					while (ServerManager.this.iscon) {
						Socket s = null;
						try {
							s = ServerManager.this.serverSocket.accept();
							try {
								Thread.sleep(1L);
							} catch (InterruptedException e) {
								
							}
						} catch (IOException e) {
							// SaLogger.outLogger(e);
						}
						ServerManager.this.exec
								.execute(ServerManager.this.creatSingleServer(ServerManager.this.getSocketID(s), s));
					}
				}
			}).start();
		}
	}

	public abstract LinkedFact creatSingleServer(int socketID, Socket s);

	private int getSocketID(Socket s) {
		int linkID = -1;
		if (this.isRemoveSocketList.size() <= 0) {
			this.socketList.add(s);
			linkID = this.socketList.size() - 1;
		} else if (this.isRemoveSocketList.size() > 0) {
			linkID = ((Integer) this.isRemoveSocketList.get(0)).intValue();
			this.isRemoveSocketList.remove(0);
			this.socketList.set(linkID, s);
		}
		return linkID;
	}

	public synchronized boolean isStartServer() {
		return this.iscon;
	}

	public synchronized Socket getLinkedSocket(int id) {
		if (id < 0 || id >= this.socketList.size())
			return null;
		return this.socketList.get(id);
	}

	public synchronized int getSocketListSize() {
		return this.socketList.size();
	}

	public synchronized void close() {
		this.iscon = false;
		for (Socket s : this.socketList) {
			if (s != null)
				try {
					s.close();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
		} /*
			 * this.exec.shutdown(); this.exec.shutdownNow();
			 */
		try {
			this.serverSocket.close();
		} catch (IOException e) {
			// SaLogger.outLogger(e);
		}
	}

	public void setLinkedClientClose(int id) {
		if (id < 0 || id >= this.socketList.size())
			return;
		Socket s = this.socketList.get(id);
		if (s != null) {
			try {
				InputStream iss = s.getInputStream();
				if (iss != null)
					iss.close();
			} catch (IOException e) {
			}
			try {
				s.close();
			} catch (IOException e) {
			}

			try {
				OutputStream oss = s.getOutputStream();
				if (oss != null)
					oss.close();
			} catch (IOException e) {
			}
			try {
				s.close();
			} catch (IOException e) {
			}

			this.socketList.set(id, null);
			this.isRemoveSocketList.add(Integer.valueOf(id));
		}
	}
}
