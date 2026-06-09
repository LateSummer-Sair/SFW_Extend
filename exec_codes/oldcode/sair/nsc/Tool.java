package sair.nsc;
 
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * The purpose of this class is to avoid coupling.</br>
 * </br>
 * This is a tool class that is often called</br>
 * 
 * @author Sair
 **/
public class Tool {

	/**
	 * Used to send a string. </br>
	 * This method repackages the string into a new final state string and sends
	 * it to the object output stream. </br>
	 * At the end, </br>
	 * it automatically updates the state of the stream.</br>
	 * 
	 * @param oos
	 *            target ObjectOutputStream
	 * @param infos
	 *            target String
	 **/
	public static final void printSEND(ObjectOutputStream oos, String infos) throws IOException {
		final String info = new String(infos);
		oos.writeObject(info);
		oos.flush();
	}

	/**
	 * Works like printSEND, but this is only used to send MOD.
	 * 
	 * @param oos
	 *            target ObjectOutputStream
	 * @param mod
	 *            MOD object
	 **/
	public static final void modSEND(MOD mod, ObjectOutputStream oos) throws IOException {
		oos.writeObject(mod);
		oos.flush();
	}

	/**
	 * Used to read objects in the input stream</br>
	 * This method is only available to clients</br>
	 * </br>
	 * If the server uses this method,</br>
	 * the error log will be confused, </br>
	 * or it can't meet the exception and force to terminate.</br>
	 * 
	 * @param ois
	 *            target ObjectOutputStream
	 * @return Object in target ObjectOutputStream
	 **/
	public static final Object objREAD(ObjectInputStream ois) throws IOException {
		try {
			final Object getInfo = ois.readObject();
			return getInfo;
		} catch (ClassNotFoundException e) {
			//SaLogger.outLogger(e);
		}
		return null;
	}
}
