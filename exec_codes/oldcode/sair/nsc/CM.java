package sair.nsc;
 
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * ClientManager
 * 
 * @version 2.0
 * @author Sair
 * 
 **/
public abstract class CM {

	/* mark Socket Object (Using singleton mode) */
	private Socket socket = null;

	/* mark OutputStream Object */
	private OutputStream dos = null;

	/* clientAutoPrintln Enable status */
	private boolean isOpen = false;

	/* mark AutoPrintln Object (Using singleton mode) */
	private AutoPrintln ap;
	

	/**
	 * 
	 * Creating this object immediately establishes the initial connection
	 * 
	 * @param port
	 *            PORT
	 * @param host
	 *            HOST or IP
	 **/
	public CM(int port, String host) {
		try {
			this.socket = new Socket(host, port);
			this.dos = this.socket.getOutputStream();
			this.isOpen = true;
		} catch (IOException e) {
			//SaLogger.outLogger(e);
		}
	}

	/**
	 * 
	 * Start to execute the client auto receiver and say hello to the server.
	 * </br>
	 * The greeting method is implemented by the user implementing the abstract
	 * method of this class.</br>
	 * 
	 * @param c
	 *            C Object
	 **/
	public synchronized void startWork(C c) {
		ap = new AutoPrintln(this, c);
		this.run(this.dos);
		new Thread(ap).start();
	}

	/**
	 * Method for say hello to the server
	 * 
	 * @param dos
	 *            this OutputStream is ObjectOutputStream
	 **/
	public abstract void run(OutputStream dos);

	/**
	 * close ClientManager and AutoPrintln
	 **/
	public synchronized void close() {
		if (this.socket != null)
			try {
				this.isOpen = false;
				ap = null;
				this.socket.close();
			} catch (IOException e) {
				//SaLogger.outLogger(e);
			}
	}

	/**
	 * Socket getter
	 * 
	 * @return this Socket
	 **/
	public Socket getSocket() {
		return socket;
	}

	/**
	 * clientAutoPrintln Enable status
	 * 
	 * @return AutoPrintln Enable status
	 **/
	public boolean isOpen() {
		return isOpen;
	}
}
