package com.sair.jj;

import java.util.HashSet;

import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.Player;

public class SingleLinePlayer implements Runnable {

	public final static SingleLinePlayer getSingleLinePlayer(boolean isPool, LineData lineData,
			HashSet<Player> playingReady) {
		return new SingleLinePlayer(isPool, lineData, playingReady);
	}

	private LineData lineData;
	private Player[] players;
	private boolean isPool;

	private SingleLinePlayer(boolean isPool, LineData ld, HashSet<Player> playingReady) {
		this.lineData = ld;
		if (this.lineData == null || (this.lineData != null && this.lineData.isErroLine())) {
			players = new Player[0];
			this.isPool = isPool;
			return;
		}
		UnitData[] uds = lineData.getUitDatas();
		players = new Player[uds.length];
		for (int i = 0; i < players.length; i++) {
			try {
				players[i] = new Player(uds[i].getInputStream());
			} catch (JavaLayerException e) {
				players[i] = null;
			}
		}
	}

	public String getLineDataInfo() {
		if (lineData != null)
			return lineData.toString();
		else
			return "null";
	}

	public void run() {
		if (this.lineData != null) {
			UnitData[] uds = lineData.getUitDatas();
			for (int i = 0; i < players.length; i++) {
				boolean flag = EXEC.exec != null;
				if (!flag)
					break;
				if (players[i] != null)
					if (isPool) {
						EXEC.exec.execute(SingleUnitDataPlayer.creat(uds[i], players[i]));
					} else
						new Thread(SingleUnitDataPlayer.creat(uds[i], players[i])).start();
			}
		}
	}
}
