package com.sair.jj;

import javazoom.jl.player.Player;
import sair.FCM;
import sair.sys.SairCons;

class SingleUnitDataPlayer implements Runnable {

	private Player player;
	private UnitData ud;

	SingleUnitDataPlayer(Player player, UnitData ud) {
		this.ud = ud;
		this.player = player;
	}

	@Override
	public void run() {
		if (player == null)
			return;
		try {
			player.play(ud.getTime());
			player.close();
			player = null;
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, "ERROR!");
		}
	}

	static Runnable creat(UnitData ud, Player player) {
		return new SingleUnitDataPlayer(player, ud);
	}

}
