package sair.sacoms;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.Player;

/**
 *
 * <p>
 * 标准MP3文件播放实例(implements Runnable)<br>
 * 
 * @author _Sair(解码器来自Apache)
 * @version aplayer_1.2
 * 
 **/
public class Aplayer implements Runnable {
	private Player player;
	private InputStream ibuffer;

	/**
	 * 初始化
	 * <p>
	 * 初始化播放文件流<br>
	 * 
	 * @param buffer
	 *            MP3文件流
	 **/
	public void setBuffer(InputStream buffer) {
		ibuffer = buffer;
	}

	/**
	 * 初始化
	 * <p>
	 * 设定播放文件<br>
	 * 
	 * @param music
	 *            File类型，MP3文件
	 **/
	public void setMusic(File music) {
		try {
			ibuffer = new FileInputStream(music);
		} catch (FileNotFoundException e) {
			//SaLogger.outLogger(e);
		}
	}

	/**
	 * 初始化
	 * <p>
	 * 初始化播放解码器<br>
	 * 
	 * @param Url
	 *            文件路径处理类型（new Urler(文件路径-String)）
	 **/
	public Aplayer(Urler Url) {
		setMusic(new File(Url.getUrl()));
	}

	/**
	 * 初始化
	 * <p>
	 * 初始化播放解码器<br>
	 * 
	 * @param path
	 *            MP3文件路径
	 **/
	public Aplayer(String path) {
		setMusic(new File(path));
	}

	/**
	 * 初始化
	 * <p>
	 * 初始化播放解码器<br>
	 * 
	 **/
	public Aplayer() {
	}

	/**
	 * 初始化
	 * <p>
	 * 初始化播放解码器与播放文件流<br>
	 * 
	 * @param buffer
	 *            MP3文件流
	 **/
	public Aplayer(InputStream buffer) {
		setBuffer(buffer);
	}

	/**
	 * 开始线程（背景播放）
	 * <p>
	 * 开始线程，线程开始方法在用户手动开始<br>
	 **/
	@Override
	public void run() {
		try {
			play();
		} catch (JavaLayerException e) {
			//SaLogger.outLogger(SaLogger.SERIOUS, e);
		}
	}

	private void play() throws JavaLayerException {
		BufferedInputStream buffer = new BufferedInputStream(ibuffer);
		player = new Player(buffer);
		player.play();
	}

	/**
	 * 停止播放
	 * <p>
	 * 停止播放MP3文件<br>
	 **/
	public synchronized void toStop() {
		if (player != null)
			player.close();
		player = null;
	}

	/**
	 * 判断播放
	 * <p>
	 * 判断播放MP3文件是否播放完成<br>
	 * 
	 * @return boolean类型
	 **/
	public synchronized boolean isEnd() {
		if (player != null)
			return player.isComplete();
		return true;
	}
}