package sair.client.coma;
import sair.client.Client;

public final class ClientCommand extends Client {
	private String command;

	public ClientCommand(int portCMD, String host, String command) {
		super(portCMD, host);
		this.command = command;
	}

	public Object toWork() {
		Object result = super.toWork(new WorkCommand(this, command));
		super.close();
		return result;
	}
}
