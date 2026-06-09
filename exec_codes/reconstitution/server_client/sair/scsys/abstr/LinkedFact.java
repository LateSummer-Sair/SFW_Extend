package sair.scsys.abstr;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

public abstract class LinkedFact implements Runnable {

	protected static ObjectOutputStream castOutputStream(int sid, OutputStream outputStream) {
		try {
			return new ObjectOutputStream(outputStream);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
}
