package sair.sacoms.until;

public class LrcLine {

	private long time = 0;
	private String lyric = "";

	public long getTime() {
		return time;
	}

	public void setTime(String time) {
		String str[] = time.split(":|\\.");
		int timeMin = Integer.parseInt(str[0]) * 60 * 1000;
		int timeS = Integer.parseInt(str[1]) * 1000;
		int TimeMs = Integer.parseInt(str[2]) * 10;
		this.time = timeMin + timeS + TimeMs;
	}

	public String getLyric() {
		return lyric;
	}

	public void setLyric(String lyric) {
		this.lyric = lyric;
	}
}
