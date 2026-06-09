package sair.ext.midi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 获取midi信息
 * 
 * @author Gitee@xingkong13
 */
// https://gitee.com/xingkong13/Midi_decoder.git
public class MidiFile {

	private List<TrackChunk> trackChunklist;
	private HeaderChunks headerChunks;
	private ByteBuffer buffer;
	private InputStream localStream;

	/**
	 * 从流中取Midi的Buffer
	 * 
	 * @param stream
	 * @throws IOException
	 */
	public MidiFile(InputStream stream) throws IOException {
		localStream = stream;
		this.buffer = readBuffer(localStream);
		headerChunks = readHeaderChunks();
		trackChunklist = readTreackChunks();
	}

	/**
	 * 从文件取Midi的Buffer
	 * 
	 * @param stream
	 * @throws IOException
	 */
	public MidiFile(File file) throws IOException {
		this(new FileInputStream(file));
	}

	/**
	 * 从流中取Buffer
	 * 
	 * @param stream
	 * @return ByteBuffer
	 */
	private ByteBuffer readBuffer(InputStream stream) throws IOException {
		byte[] data = new byte[stream.available()];
		stream.read(data);
		ByteBuffer byteBuffer = ByteBuffer.wrap(data);
		return byteBuffer;
	}

	/**
	 * 获取头部信息MThd
	 * 
	 * @param buffer
	 * @return HeaderChunks
	 */
	private HeaderChunks readHeaderChunks() {
		headerChunks = new HeaderChunks();
		for (int i = 0; i < headerChunks.getMidiId().length; i++) {
			headerChunks.getMidiId()[i] = (byte) (buffer.get()); // 循环读取Length个字节
		}
		headerChunks.setLength(buffer.getInt()); // 连续读4个字节
		headerChunks.setFormat(buffer.getShort());
		headerChunks.setTrackNumber(buffer.getShort()); // 连续读两个字节
		headerChunks.setMidiTimeSet(buffer.getShort());
		return headerChunks;
	}

	/**
	 * 获取MTrk信息块
	 * 
	 * @param buffer
	 * @param headerChunks
	 * @return List<TrackChunk>
	 */
	private List<TrackChunk> readTreackChunks() {
		trackChunklist = new ArrayList<TrackChunk>();
		TrackChunk trackChunk;
		for (int i = 0; i < headerChunks.getTrackNumber(); i++) { // 遍历所有的音轨
			trackChunk = new TrackChunk();
			for (int j = 0; j < trackChunk.getMidiId().length; j++)
				trackChunk.getMidiId()[j] = (byte) (buffer.get()); // 读取"MTrk"
			trackChunk.setLength(buffer.getInt()); // 再度4字节，作为音轨长度
			byte[] data = new byte[trackChunk.getLength()]; // 分配length个字节的内存，用来存储数据
			buffer.get(data); // 逐字节的读取剩余的数据
			// System.out.println(data[0]);
			trackChunk.setData(data);
			trackChunklist.add(trackChunk); // 将音轨i的数据添加到音轨链表中
			trackChunk.transfer();
		}
		return trackChunklist;
	}

	public List<TrackChunk> getTrackChunklist() {
		return trackChunklist;
	}

	public HeaderChunks getHeaderChunks() {
		return headerChunks;
	}

	public ByteBuffer getBuffer() {
		return buffer;
	}

	public InputStream getLocalStream() {
		return localStream;
	}

	public void close() throws IOException {
		if (null != localStream)
			localStream.close();
	}

}