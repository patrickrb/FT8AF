package com.k1af.ft8af.wave;
/**
 * Operations for reading WAV files.
 * Deprecated. FT8CN no longer uses the audio file approach.
 * @author BGY70Z
 * @date 2023-03-20
 */

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;

@SuppressWarnings("unused")
public class WaveFileReader {
	private String filename = null;
	private int[][] data = null;

	private int len = 0;

	private String chunkdescriptor = null;
	private long chunksize = 0;
	private String waveflag = null;
	private String fmtsubchunk = null;
	private long subchunk1size = 0;
	private int audioformat = 0;
	private int numchannels = 0;
	private long samplerate = 0;
	private long byterate = 0;
	private int blockalign = 0;
	private int bitspersample = 0;
	private String datasubchunk = null;
	private long subchunk2size = 0;
	private FileInputStream fis = null;
	private BufferedInputStream bis = null;

	private boolean issuccess = false;

	public WaveFileReader(String filename) {

		this.initReader(filename);
	}

	// check if WAV reader was created successfully
	public boolean isSuccess() {
		return issuccess;
	}

	// get the encoding length per sample, 8-bit or 16-bit
	public int getBitPerSample() {
		return this.bitspersample;
	}

	// get the sampling rate
	public long getSampleRate() {
		return this.samplerate;
	}

	// get the number of channels: 1 = mono, 2 = stereo
	public int getNumChannels() {
		return this.numchannels;
	}

	// get the data length, i.e. the total number of samples
	public int getDataLen() {
		return this.len;
	}

	// get data
	// data is a 2D array; [n][m] represents the m-th sample value of the n-th channel
	public int[][] getData() {
		return this.data;
	}

	private void initReader(String filename) {
		this.filename = filename;

		try {
			fis = new FileInputStream(this.filename);
			bis = new BufferedInputStream(fis);

			this.chunkdescriptor = readString(WaveConstants.LENCHUNKDESCRIPTOR);
			if (!chunkdescriptor.endsWith("RIFF"))
				throw new IllegalArgumentException("RIFF miss, " + filename + " is not a wave file.");

			this.chunksize = readLong();
			this.waveflag = readString(WaveConstants.LENWAVEFLAG);
			if (!waveflag.endsWith("WAVE"))
				throw new IllegalArgumentException("WAVE miss, " + filename + " is not a wave file.");

			this.fmtsubchunk = readString(WaveConstants.LENFMTSUBCHUNK);
			if (!fmtsubchunk.endsWith("fmt "))
				throw new IllegalArgumentException("fmt miss, " + filename + " is not a wave file.");

			this.subchunk1size = readLong();
			this.audioformat = readInt();
			this.numchannels = readInt();
			this.samplerate = readLong();
			this.byterate = readLong();
			this.blockalign = readInt();
			this.bitspersample = readInt();

			this.datasubchunk = readString(WaveConstants.LENDATASUBCHUNK);
			if (!datasubchunk.endsWith("data"))
				throw new IllegalArgumentException("data miss, " + filename + " is not a wave file.");
			this.subchunk2size = readLong();

			this.len = (int) (this.subchunk2size / (this.bitspersample / 8) / this.numchannels);

			this.data = new int[this.numchannels][this.len];

			// read data
			for (int i = 0; i < this.len; ++i) {
				for (int n = 0; n < this.numchannels; ++n) {
					if (this.bitspersample == 8) {
						this.data[n][i] = bis.read();
					} else if (this.bitspersample == 16) {
						this.data[n][i] = this.readInt();
					}
				}
			}

			issuccess = true;
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (bis != null)
					bis.close();
				if (fis != null)
					fis.close();
			} catch (Exception e1) {
				e1.printStackTrace();
			}
		}
	}

	private String readString(int len) {
		byte[] buf = new byte[len];
		try {
			if (bis.read(buf) != len)
				throw new IOException("no more data!!!");
		} catch (IOException e) {
			e.printStackTrace();
		}
		return new String(buf);
	}

	private int readInt() {
		byte[] buf = new byte[2];
		int res = 0;
		try {
			if (bis.read(buf) != 2)
				throw new IOException("no more data!!!");
			res = (buf[0] & 0x000000FF) | (((int) buf[1]) << 8);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return res;
	}

	private long readLong() {
		long res = 0;
		try {
			long[] l = new long[4];
			for (int i = 0; i < 4; ++i) {
				l[i] = bis.read();
				if (l[i] == -1) {
					throw new IOException("no more data!!!");
				}
			}
			res = l[0] | (l[1] << 8) | (l[2] << 16) | (l[3] << 24);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return res;
	}

	private byte[] readBytes(int len) {
		byte[] buf = new byte[len];
		try {
			if (bis.read(buf) != len)
				throw new IOException("no more data!!!");
		} catch (IOException e) {
			e.printStackTrace();
		}
		return buf;
	}

	public static int[] readSingleChannel(String filename) {
		if (filename == null || filename.length() == 0) {
			return null;
		}
		try {
			WaveFileReader reader = new WaveFileReader(filename);
			int[] res = reader.getData()[0];
			return res;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
}
