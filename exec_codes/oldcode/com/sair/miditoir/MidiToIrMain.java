package com.sair.miditoir;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import sair.Main;
import sair.ext.midi.MidiFile;
import sair.ext.midi.TrackChunk;
import sair.sacoms.FileMana;
import sair.sacoms.MathCast;
import sair.sacoms.SairLists;
import sair.sacoms.Urler;
import sair.sys.SairCons;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

public class MidiToIrMain extends Activity {

	public static void main(String[] args) {

		MidiToIrMain mti = new MidiToIrMain();
		Main.toTest(mti, "inpath", "\"E:\\下载\\WindSong-Lyre-Genshin-Impact-master\\midi\"");
		Main.toTest(mti, "outpath", "\"E:\\Test\\midTest\"");
		Main.toTest(mti, "keyset", "50");
		Main.toTest(mti, "keyoff", "0");
		Main.toTest(mti, "keytime", "100");
		Main.toTest(mti, "timeoff", "15");
		Main.toTest(mti, "start", "");

		SairCons.runner(false, "/show");
		// SairCons.runner(true, "/newthread /ir E:\\Test\\midTest\\起风了\\0.ir");
	}

	// /newthread /ir

	private void make(String inputPath, int timeOffset, int keyOffset, String outDirPath) throws IOException {
		int localKey = -1;
		File inputFile = new File(inputPath);
		Urler urler = new Urler(inputPath);
		SairCons.println("--------------------------------------------------");
		SairCons.println("正在转化文件：" + inputPath);
		if (!inputFile.exists())
			throw new IOException();
		MidiFile mf = new MidiFile(inputFile);
		List<TrackChunk> list = mf.getTrackChunklist();
		SairLists<String> rlist = new SairLists<String>();
		int ri = 0;
		for (TrackChunk tc : list) {
			if (tc.getCount() > 0) {
				rlist = new SairLists<String>();
				List<?> rslist = tc.getSound();
				List<?> rtlist = tc.get_times();
				rlist.add("jj/resetline");
				rlist.add("jj/unstop");
				for (int i = 0; i < rslist.size(); i++) {
					int time = Integer.valueOf(String.valueOf(rtlist.get(i)));
					int key = Integer.valueOf(String.valueOf(rslist.get(i))) - keyOffset;

					localKey = key;
					if (localKey == key && time <= keySet)
						continue;

					if (time != 0)
						time += timeOffset;
					if (key < MidiTable.midis.length && key >= 0)
						rlist.add("jj/at " + MidiTable.midis[key] + "/" + keyTime + "\r\n/sleep " + time);
				}
				FileMana.memorriesToIO(outDirPath + File.separator + urler.getFileName() + File.separator + ri + ".ir",
						rlist, true);
				ri++;
			}
		}
		mf.close();
	}

	private String inDirPath;
	private String outDirPath;
	private int keyOff;
	private int timeOff;
	private boolean isCon;
	private int keyTime;
	private int keySet;

	@Override
	public Object main(String funcName, String args) {
		switch (funcName) {
		case "inpath":
			return setinpath(args);
		case "outpath":
			return setoutpath(args);
		case "keyoff":
			return keyoff(args);
		case "keyset":
			return keyset(args);
		case "timeoff":
			return timeoff(args);
		case "keytime":
			return keytime(args);
		case "start":
			return start();
		case "ptinfo":
			return ptinfo();
		default:
			return false;
		}
	}

	private Object keyset(String args) {
		int time = MathCast.StringsIntToInt(args);
		keySet = time;
		return time;
	}

	private Object keytime(String args) {
		int time = MathCast.StringsIntToInt(args);
		keyTime = time;
		return time;
	}

	private Object timeoff(String args) {
		int time = MathCast.StringsIntToInt(args);
		timeOff = time;
		return time;
	}

	private Object keyoff(String args) {
		int key = MathCast.StringsIntToInt(args);
		keyOff = key;
		return key;
	}

	private Object start() {
		if (null == inDirPath)
			return ptinfo();
		if (null == outDirPath)
			outDirPath = this.getDataDir();
		ArrayList<String> fileList = ToolPack.getAllFilesPath(new File(inDirPath), true);
		ArrayList<String> midList = new ArrayList<String>();
		for (String path : fileList) {
			Urler urler = new Urler(path);
			if (urler.getEques(".mid"))
				midList.add(urler.getUrl());
			else
				continue;
		}
		isCon = true;
		start0(midList);
		return midList;
	}

	private void start0(ArrayList<String> fileList) {
		int fileC = 0;
		for (int i = 0; i < fileList.size() && isCon; i++) {
			try {
				make(fileList.get(i), timeOff, keyOff, outDirPath);
				fileC++;
			} catch (IOException e) {
				SairCons.println(Color.RED, "发生异常！");
			}
		}
		SairCons.println("--------------------------------------------------");
		SairCons.println("转化结束,一共转化" + fileC + "个mid文件");
		SairCons.println("--------------------------------------------------");
	}

	private Object setoutpath(String args) {
		Urler url = new Urler(args);
		if (!url.getUrlFound())
			return false;
		return (outDirPath = url.getUrl());
	}

	private Object setinpath(String args) {
		Urler url = new Urler(args);
		if (!url.getUrlFound())
			return false;
		return (inDirPath = url.getUrl());
	}

	private String ptinfo() {
		StringBuffer sbf = new StringBuffer();
		sbf.append("inPath: " + inDirPath).append("\r\n");
		sbf.append("outPath: " + outDirPath).append("\r\n");
		sbf.append("timeOffset: " + timeOff + "ms").append("\r\n");
		sbf.append("keyTime: " + keyTime + "ms").append("\r\n");
		sbf.append("keySetTime: " + keySet + "ms").append("\r\n");
		sbf.append("keyOffset: " + keyOff + "pcs");
		String info = sbf.toString();
		SairCons.println(info);
		return info;
	}

	@Override
	public String[] help() {
		return new String[] { "mid2ir V1.0", "", //
				this.getName() + "/inpath [DIR path] 设置mid文件夹所在路径", //
				this.getName() + "/outpath [DIR path] 设置ir文件夹输出路径", //
				this.getName() + "/timeoff [offset] 设置命令时间延迟偏移", //
				this.getName() + "/keyoff [offset] 设置命令音符偏移", //
				this.getName() + "/keytime [time] 设置音符持续时间", //
				this.getName() + "/keyset [time] 设置音符筛选间隔时间", //
				this.getName() + "/ptinfo 输出查看已设置的信息", //
				this.getName() + "/start 开始转换"//
		};
	}

	@Override
	public void exit() {
		isCon = false;
		inDirPath = null;
		outDirPath = null;
		keyOff = 0;
		timeOff = 0;
	}

}
