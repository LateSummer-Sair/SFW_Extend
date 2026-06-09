package sair.sacoms.until;

import java.io.Serializable;

/**
 * °üº¬String idºÍint times
 **/
public class SearchPage implements Serializable {
	private static final long serialVersionUID = -6537878337405262973L;
	private String id;
	private int times;

	public SearchPage(String id, int times) {
		setId(id);
		setTimes(times);
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public int getTimes() {
		return times;
	}

	public void setTimes(int times) {
		this.times = times;
	}
}
