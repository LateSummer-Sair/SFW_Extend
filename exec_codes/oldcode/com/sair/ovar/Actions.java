package com.sair.ovar;

import java.awt.Color;
import java.util.HashMap;
import java.util.Set;

import sair.Pathes;
import sair.sys.SairCons;
import sair.sys.tools.ToolPack;

class Actions {

	String add(String args, HashMap<String, Object> ovarMap) {
		String[] splited = args.split(" ");
		if (splited.length > 0) {
			boolean result = ovarMap.containsKey(splited[0]);
			if (!result) {
				String cmd = ToolPack.reArg(splited, new Integer[] { 0 });
				Object n_o = SairCons.runner(false, cmd);
				ovarMap.put(splited[0], n_o);
				SairCons.println(Color.GREEN, splited[0] + " is added");
				return String.valueOf(true);
			} else {
				SairCons.println(Color.RED, splited[0] + " : isHas = true");
				return String.valueOf(false);
			}
		}
		return String.valueOf(false);
	}

	public Object del(String args, HashMap<String, Object> ovarMap) {
		boolean result = ovarMap.containsKey(args);
		if (result) {
			SairCons.println(Color.GREEN, args + " is remove");
			return ovarMap.remove(args);
		} else
			SairCons.println(Color.RED, args + " : isHas = false");
		return null;
	}

	public Object set(String args, HashMap<String, Object> ovarMap) {
		String[] splited = args.split(" ");
		if (splited.length > 0) {
			boolean result = ovarMap.containsKey(splited[0]);
			if (result) {
				String cmd = ToolPack.reArg(splited, new Integer[] { 0 });
				Object n_o = SairCons.runner(false, cmd);
				Object o_o = ovarMap.get(splited[0]);
				ovarMap.put(splited[0], n_o);
				SairCons.println(Color.GREEN, splited[0] + " is update");
				return o_o;
			} else {
				SairCons.println(Color.RED, splited[0] + " : isHas = false");
				return null;
			}
		}
		return null;
	}

	public Object get(String args, HashMap<String, Object> ovarMap) {
		boolean result = ovarMap.containsKey(args);
		if (result)
			return ovarMap.get(args);
		else
			SairCons.println(Color.RED, args + " : isHas = false");
		return null;
	}

	public String ishas(String args, HashMap<String, Object> ovarMap) {
		boolean result = ovarMap.containsKey(args);
		if (result)
			SairCons.println(Color.GREEN, args + " : isHas = true");
		else
			SairCons.println(Color.RED, args + " : isHas = false");
		return String.valueOf(result);
	}

	private final String sp = Pathes.printSplit + Pathes.printSplit;

	public Object list(HashMap<String, Object> ovarMap) {
		Set<String> keySet = ovarMap.keySet();
		SairCons.println(sp);
		SairCons.print(Color.RED, "\r\nName");
		SairCons.print(Color.PINK, "\t\tclassName");
		SairCons.print(Color.WHITE, "\t\t\t\t\tV");
		SairCons.println(sp);
		for (String name : keySet) {
			Object o = ovarMap.get(name);
			SairCons.print(Color.RED, "\r\n" + name);
			if (o != null) {
				SairCons.print(Color.PINK, "\t\t" + o.getClass().getName());
				if (o instanceof CharSequence || o instanceof Boolean || o instanceof Number
						|| o instanceof Character) {
					SairCons.print(Color.WHITE, "\t\t\t\t" + o);
				} else {
					SairCons.print(Color.YELLOW, "\t\t\t\tisnot base data");
				}
			} else {
				SairCons.print(Color.PINK, "\t\tnull");
				SairCons.print(Color.WHITE, "\t\t\t\t\tnull");
			}
		}
		SairCons.println(sp);
		return true;
	}

}
