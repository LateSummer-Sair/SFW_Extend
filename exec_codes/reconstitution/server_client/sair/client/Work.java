package sair.client;

import java.io.InputStream;
import java.io.OutputStream;

public interface Work {
	Object startWork(InputStream input, OutputStream output);
}
