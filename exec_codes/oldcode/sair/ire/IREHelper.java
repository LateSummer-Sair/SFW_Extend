package sair.ire;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sair.sys.IRLabel;
import sair.sys.IRRunnable;
import sair.sys.SairCons;

/**
 * IRE 脚本运行时辅助类：在转译后的 Java 代码中调用，用于执行 ir 文件。
 * <ul>
 * <li>{@link #runIr(String)}：按 ir 主流程顺序执行；</li>
 * <li>{@link #runIrLabel(String, String)}：跳转到 ir 中指定标签块执行。</li>
 * </ul>
 * ir 命令走 SFW 的 SairCons.runner 分发，与原生 ir 执行一致。
 */
public class IREHelper {

	private static final String TO = "/TO:";

	public static Object runIr(String path) {
		IRRunnable irr = loadIr(path);
		if (irr == null)
			return null;
		irr.run();
		return true;
	}

	public static Object runIrLabel(String path, String labelName) {
		IRRunnable irr = loadIr(path);
		if (irr == null)
			return null;
		IRLabel label = irr.getLabel(labelName);
		if (label == null) {
			SairCons.println("ire: ir label not found [" + labelName + "] in " + path);
			return null;
		}
		List<String> lines = label.getLines();
		if (lines != null)
			for (String line : lines)
				runLine(irr, line);
		return true;
	}

	private static IRRunnable loadIr(String path) {
		IRRunnable existing = IRRunnable.irpool.get(path);
		if (existing != null)
			return existing;
		try {
			File file = new File(path);
			if (!file.exists()) {
				SairCons.println("ire: ir file not found [" + path + "]");
				return null;
			}
			List<String> allLines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
			return new IRRunnable(allLines, path);
		} catch (Exception e) {
			SairCons.println("ire: load ir failed [" + path + "] " + e.getMessage());
			return null;
		}
	}

	/** /TO: 递归跳转的最大深度（防止死循环之外的超深链） */
	private static final int MAX_IR_DEPTH = 256;

	/** 递归执行一行 ir 命令，处理 /TO: 标签跳转 */
	private static void runLine(IRRunnable irr, String line) {
		runLine0(irr, line, new HashSet<String>(), 0);
	}

	private static void runLine0(IRRunnable irr, String line, Set<String> active, int depth) {
		if (depth > MAX_IR_DEPTH) {
			SairCons.println("ire: /TO: 跳转层级过深或被阻断 [" + depth + "]");
			return;
		}
		if (line == null)
			return;
		line = line.replaceAll("^\\s+", "");
		if (line.isEmpty())
			return;
		if (line.startsWith(TO)) {
			String name = line.substring(TO.length()).trim();
			if (name.isEmpty())
				return;
			if (!active.add(name)) {
				// 标签在当前跳转链上重复出现，说明存在循环，终止以避免栈溢出
				SairCons.println("ire: ir 标签循环跳转被终止 [" + name + "]");
				return;
			}
			IRLabel label = irr.getLabel(name);
			if (label == null) {
				SairCons.println("ire: ir label not found [" + name + "]");
				active.remove(name);
				return;
			}
			List<String> lines = label.getLines();
			if (lines != null)
				for (String l : lines)
					runLine0(irr, l, active, depth + 1);
			active.remove(name);
		} else {
			SairCons.runner(false, line);
		}
	}
}
