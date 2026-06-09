package sair.nsc;
 
import java.io.Serializable;

/**
 * Network IO serialization standard template for this program
 * 
 * @author Sair
 **/
public class MOD implements Serializable {

	/* SerializableID */
	private static final long serialVersionUID = 3493610299760916976L;

	/* mark targetID */
	private Integer downloaderID;

	/* mark targetJMD */
	private String jmd;

	/* mark targetID Client return Object */
	private Serializable obj;

	/**
	 * @param downloaderID
	 *            targetID ( The server is responsible for rewriting when
	 *            forwarding)
	 * @param obj
	 *            Client return Object(Object must implements Serializable)
	 * @param jmd
	 *            targetJMD
	 **/
	public MOD(Integer downloaderID, Serializable obj, String jmd) {
		this.downloaderID = downloaderID;
		this.obj = obj;
		this.jmd = jmd;
	}

	/**
	 * @return SerializableObject
	 **/
	public Serializable getObj() {
		return obj;
	}

	/**
	 * @return targetID
	 **/
	public Integer getDownloaderID() {
		return downloaderID;
	}

	/**
	 * @return targetJMD
	 **/
	public String getJmd() {
		return jmd;
	}
}
