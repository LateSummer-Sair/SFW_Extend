package com.sair.jj;

public final class LineIndexManager {
	private long indexNumber;
	
	public LineIndexManager(){
		reset();
	}

	public void reset() {
		indexNumber = -1;
	}

	public long get() {
		indexNumber++;
		return indexNumber;
	}
}
