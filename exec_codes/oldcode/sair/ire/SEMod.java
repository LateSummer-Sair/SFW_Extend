package sair.ire;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

class SEMod {
	ScriptEngineManager SEM = new ScriptEngineManager();
	ScriptEngine JSE = SEM.getEngineByName("JavaScript");

	final Object eval(String funcName, Object[] obs) throws Exception {
		Invocable jsInvoke = (Invocable) JSE;
		Object res = jsInvoke.invokeFunction(funcName, obs);
		return res;
	}
}
