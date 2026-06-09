package sair.jud;

import sair.FCM;
import sair.Pathes;
import sair.sys.SairCons;
import sair.user.Activity;

/**
 * jud - 条件判断插件，提供类似Excel�? IF �? IFNOT 的能�?
 * <p>
 * 命令格式�? jud/if [条件值] || [真分支命令] || [假分支命令] jud/ifnot [条件值] || [真分支命令] ||
 * [假分支命令]
 * <p>
 * 条件值判断规则（真�?�）�? 非空、且不是 "false"�?"0"�?"null"（不区分大小写）即为�?
 * <p>
 * 支持比较函数�? jud/eq [val1] || [val2] || [真分支] || [假分支] -- 等于 jud/neq [val1] ||
 * [val2] || [真分支] || [假分支] -- 不等�? jud/gt [val1] || [val2] || [真分支] || [假分支] --
 * 大于 jud/lt [val1] || [val2] || [真分支] || [假分支] -- 小于 jud/ge [val1] || [val2] ||
 * [真分支] || [假分支] -- 大于等于 jud/le [val1] || [val2] || [真分支] || [假分支] -- 小于等于
 * jud/empty [val] || [真分支] || [假分支] -- 是否为空
 * <p>
 * 示例�? jud/if %myvar% || /println yes || /println no jud/ifnot %myvar% ||
 * /println yes || /println no jud/eq %a% || %b% || /println equal || /println
 * not-equal
 *
 * @author DeepSeek_V4Pro
 */
public class JudMain extends Activity {

	private static final String SPLIT = "\\|\\|";

	@Override
	public Object main(String funcName, String args) {
		if (args == null) {
			SairCons.println(FCM.Error_Color, "jud: args is null");
			return false;
		}

		switch (funcName) {
		case "if":
			return doIf(args, false);
		case "ifnot":
			return doIf(args, true);
		case "eq":
			return doCompare(args, "eq");
		case "neq":
			return doCompare(args, "neq");
		case "gt":
			return doCompare(args, "gt");
		case "lt":
			return doCompare(args, "lt");
		case "ge":
			return doCompare(args, "ge");
		case "le":
			return doCompare(args, "le");
		case "empty":
			return doEmpty(args);
		default:
			return false;
		}
	}

	/**
	 * if / ifnot 基础实现
	 */
	private Object doIf(String args, boolean invert) {
		String[] parts = args.split(SPLIT);
		if (parts.length < 3) {
			SairCons.println(FCM.Error_Color, "jud: 参数不足，需要: condition || true_cmd || false_cmd");
			return false;
		}

		String condition = trimPart(parts, 0);
		String trueCmd = trimPart(parts, 1);
		String falseCmd = trimPart(parts, 2);

		boolean isTruthy = isTruthy(condition);
		if (invert) {
			isTruthy = !isTruthy;
		}

		String cmdToRun = isTruthy ? trueCmd : falseCmd;
		if (cmdToRun != null && !cmdToRun.isEmpty()) {
			SairCons.runner(false, cmdToRun);
		}
		return null;
	}

	/**
	 * 比较函数实现：eq / neq / gt / lt / ge / le 格式: jud/eq val1 || val2 || true_cmd
	 * || false_cmd
	 */
	private Object doCompare(String args, String op) {
		String[] parts = args.split(SPLIT);
		if (parts.length < 4) {
			SairCons.println(FCM.Error_Color, "jud: 参数不足，需要: val1 || val2 || true_cmd || false_cmd");
			return false;
		}

		String val1 = trimPart(parts, 0);
		String val2 = trimPart(parts, 1);
		String trueCmd = trimPart(parts, 2);
		String falseCmd = trimPart(parts, 3);

		boolean result = false;
		try {
			double d1 = Double.parseDouble(val1);
			double d2 = Double.parseDouble(val2);
			// 数字比较
			switch (op) {
			case "eq":
				result = (d1 == d2);
				break;
			case "neq":
				result = (d1 != d2);
				break;
			case "gt":
				result = (d1 > d2);
				break;
			case "lt":
				result = (d1 < d2);
				break;
			case "ge":
				result = (d1 >= d2);
				break;
			case "le":
				result = (d1 <= d2);
				break;
			}
		} catch (NumberFormatException e) {
			// 字符串比�?
			switch (op) {
			case "eq":
				result = val1.equals(val2);
				break;
			case "neq":
				result = !val1.equals(val2);
				break;
			case "gt":
				result = val1.compareTo(val2) > 0;
				break;
			case "lt":
				result = val1.compareTo(val2) < 0;
				break;
			case "ge":
				result = val1.compareTo(val2) >= 0;
				break;
			case "le":
				result = val1.compareTo(val2) <= 0;
				break;
			}
		}

		String cmdToRun = result ? trueCmd : falseCmd;
		if (cmdToRun != null && !cmdToRun.isEmpty()) {
			SairCons.runner(false, cmdToRun);
		}
		return null;
	}

	/**
	 * empty 判空：val为空则执行true分支
	 */
	private Object doEmpty(String args) {
		String[] parts = args.split(SPLIT);
		if (parts.length < 3) {
			SairCons.println(FCM.Error_Color, "jud: 参数不足，需要: val || true_cmd || false_cmd");
			return false;
		}

		String val = trimPart(parts, 0);
		String trueCmd = trimPart(parts, 1);
		String falseCmd = trimPart(parts, 2);

		boolean isEmpty = (val == null || val.isEmpty());
		String cmdToRun = isEmpty ? trueCmd : falseCmd;
		if (cmdToRun != null && !cmdToRun.isEmpty()) {
			SairCons.runner(false, cmdToRun);
		}
		return null;
	}

	/**
	 * 获取并修剪数组的指定位置元素
	 */
	private String trimPart(String[] parts, int index) {
		if (index >= parts.length)
			return "";
		String val = parts[index];
		return val == null ? "" : val.trim();
	}

	/**
	 * 判断值是否为"�?"
	 */
	private boolean isTruthy(String val) {
		if (val == null || val.isEmpty())
			return false;
		val = val.trim().toLowerCase();
		return !("false".equals(val) || "0".equals(val) || "null".equals(val));
	}

	@Override
	public String[] help() {
		return new String[] { Pathes.printSplit, "jud - 条件判断插件 (类似Excel IF/IFNOT)", "Coder:DeepSeek_V4Pro",
				Pathes.printSplit, "[基础判断]", "  jud/if [条件] || [真分支] || [假分支]", "    条件为真时执行真分支命令，否则执行假分支",
				"  jud/ifnot [条件] || [真分支] || [假分支]", "    条件为假时执行真分支命令，否则执行假分支", "  真值规则：非空、非false、非0、非null",
				Pathes.printSplit, "[比较判断]", "  jud/eq  [v1] || [v2] || [真] || [假]  -- 等于",
				"  jud/neq [v1] || [v2] || [真] || [假]  -- 不等于", "  jud/gt  [v1] || [v2] || [真] || [假]  -- 大于",
				"  jud/lt  [v1] || [v2] || [真] || [假]  -- 小于", "  jud/ge  [v1] || [v2] || [真] || [假]  -- 大于等于",
				"  jud/le  [v1] || [v2] || [真] || [假]  -- 小于等于", "  jud/empty [v] || [真] || [假]          -- 是否为空",
				"  数字优先按数值比较，否则按字符串比较", Pathes.printSplit, "[使用示例]", "  jud/if %score% || /println 及格 || /println 不及格",
				"  jud/eq %a% || %b% || /println 相等 || /println 不等",
				"  jud/gt %num% || 60 || /println 大于60 || /println 小于等于60", Pathes.printSplit, };
	}

	@Override
	public void exit() {
	}

	@Override
	protected String dataDir() {
		return "jud";
	}
}
