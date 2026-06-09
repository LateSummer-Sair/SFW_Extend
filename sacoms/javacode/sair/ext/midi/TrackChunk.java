package sair.ext.midi;

import java.util.ArrayList;
import java.util.List;

/**
 * midi音节解析
 * 
 * @author Gitee@xingkong13
 */
// https://gitee.com/xingkong13/Midi_decoder.git
@SuppressWarnings("rawtypes")
public class TrackChunk {
	private byte[] midiId;
	// 长度
	private int length;
	private byte[] midiData;
	private ArrayList times; // 按下琴键的时间点
	private ArrayList sound; // 按下的音符
	private ArrayList power; // 按下的力度
	private int count; // 总共操作数
	private int count_up; // 抬起次数
	private int chunk; //
	private int temp; // 单音节延时
	private int tot; // 存储总延时

	private static String bytes2HexString(byte b) {
		String ret = "";
		String hex = Integer.toHexString(b & 0xFF);
		if (hex.length() == 1) {
			hex = '0' + hex;
		}
		ret += hex.toUpperCase();
		return ret;
	}

	public TrackChunk() {
		this.midiId = new byte[4];
		this.times = new ArrayList();
		this.sound = new ArrayList();
		this.power = new ArrayList();
		this.count = 0;
		this.count_up = 0;
	}

	public int getCount() {
		return count;
	}

	public ArrayList getTimes() {
		return times;
	}

	public ArrayList getSound() {
		return sound;
	}

	public ArrayList getPower() {
		return power;
	}

	public int getTemp() {
		return temp;
	}

	public ArrayList get_times() {
		return times;
	}

	public ArrayList get_sound() {
		return sound;
	}

	public ArrayList get_power() {
		return power;
	}

	public int getLength() {
		return length;
	}

	public byte[] getMidiId() {
		return midiId;
	}

	public List<String> getAllResult() {
		List<String> result = new ArrayList<String>();
		for (int i = 0; i < count; i++)
			result.add(times.get(i) + "_" + sound.get(i) + "_" + power.get(i));
		return result;
	}

	public List<String> getData() {
		List<String> list = new ArrayList<String>();
		for (int i = 0; i < midiData.length; i++)
			list.add(midiData[i] + "==" + bytes2HexString(midiData[i]));
		return list;
	}

	@SuppressWarnings("unchecked")
	String transfer() {
		int i = 3;
		chunk = 0;
		temp = 0;

		for (; i < midiData.length - 2;) {
			if (midiData[i] == 0 && (midiData[i + 1] & 0xf0) == 0x90) {
				chunk = midiData[i + 1] & 0x000f;
				// System.out.println("chunk="+chunk);
				break;
			}
			i++;
		}

		for (; i < midiData.length;) {

			if ((midiData[i] & 0xff) == (0x90 | chunk) && i < midiData.length - 4) { // 按下按键的情况

				times.add(tot);
				tot = 0;
				sound.add(midiData[i + 1] & 0xff);
				power.add(midiData[i + 2] & 0xff);

				if ((midiData[i + 2] & 0xff) == 0x00)
					count_up++;
				count++;

				i = i + 3;
				while ((midiData[i] & 0xff) > 0x80 && i < midiData.length - 4) {
					temp = (temp + (midiData[i] & 0x7f)) * 128;
					i++;
				}
				tot = tot + temp + (midiData[i] & 0x7f); // 存时间
				temp = 0;
				i++;

				while ((midiData[i] & 0xff) != (0x90 | chunk) && (midiData[i] & 0xff) != (0x80 | chunk)
						&& (midiData[i] & 0xff) != (0xB0 | chunk) && (midiData[i] & 0xf0) != (0xA0 | chunk)
						&& (midiData[i] & 0xf0) != (0xC0 | chunk) && (midiData[i] & 0xf0) != (0xD0 | chunk)
						&& (midiData[i] & 0xf0) != (0xE0 | chunk) && (midiData[i] & 0xf0) != (0xF0 | chunk)
						&& i < midiData.length - 4) { // 判断有无相邻的相同命令

					times.add(tot); // 取出总时间
					tot = 0; // 清空总时间
					sound.add(midiData[i] & 0xff);
					power.add(midiData[i + 1] & 0xff);

					if ((midiData[i + 1] & 0xff) == 0x00)
						count_up++;
					count++;

					i = i + 2;
					while ((midiData[i] & 0xff) > 0x80 && i < midiData.length - 4) {
						temp = (temp + (midiData[i] & 0x7f)) * 128;
						i++;
					}
					tot = tot + temp + (midiData[i] & 0x7f);
					temp = 0;
					i++;
				}
				temp = 0;
			} else if ((midiData[i] & 0xff) == (0x80 | chunk) && i < midiData.length - 4) { // 抬起按键的情况

				times.add(tot);
				tot = 0;
				sound.add(midiData[i + 1] & 0xff);
				power.add(0);

				count_up++;
				count++;

				i = i + 3;

				while ((midiData[i] & 0xff) > 0x80 && i < midiData.length - 4) {
					temp = (temp + (midiData[i] & 0x7f)) * 128;
					i++;
				}
				tot = tot + temp + (midiData[i] & 0x7f); // 存时间
				temp = 0;
				i++;

				while ((midiData[i] & 0xff) != (0x90 | chunk) && (midiData[i] & 0xff) != (0x80 | chunk)
						&& (midiData[i] & 0xff) != (0xB0 | chunk) && (midiData[i] & 0xf0) != (0xA0 | chunk)
						&& (midiData[i] & 0xf0) != (0xC0 | chunk) && (midiData[i] & 0xf0) != (0xD0 | chunk)
						&& (midiData[i] & 0xf0) != (0xE0 | chunk) && (midiData[i] & 0xf0) != (0xF0 | chunk)
						&& i < midiData.length - 4) { // 判断有无相邻的相同命令

					times.add(tot); // 取出总时间
					tot = 0; // 清空总时间
					sound.add(midiData[i] & 0xff);
					power.add(0);

					count_up++;
					count++;

					i = i + 2;
					while ((midiData[i] & 0xff) > 0x80 && i < midiData.length - 4) {
						temp = (temp + (midiData[i] & 0x7f)) * 128;
						i++;
					}
					tot = tot + temp + (midiData[i] & 0x7f);
					temp = 0;
					i++;
				}
				temp = 0;
			} else if ((midiData[i] & 0xff) == (0xB0 | chunk) && i < midiData.length - 4) { // B控制字的时候
				i = i + 3;
				while ((midiData[i] & 0xff) > 0x80 && i < midiData.length - 4) {
					temp = (temp + (midiData[i] & 0x7f)) * 128;
					i++;
				}
				tot = tot + temp + (midiData[i] & 0x7f); // 存时间
				temp = 0;
				i++;
			} else {
				i++;
			}
		}
		return "[all:" + count + "][pressOn:" + (count - count_up) + "][pressOff:" + count_up + "]";

	}

	void setMidiId(byte[] midiId) {
		this.midiId = midiId;
	}

	void setLength(int length) {
		this.length = length;
	}

	void setData(byte[] data) {
		this.midiData = data;
	}
}
