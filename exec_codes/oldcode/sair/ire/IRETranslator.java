package sair.ire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IRE script translator: translates .ire source into equivalent Java source.
 * <p>
 * IRE is a thin syntax-extension layer over Java:
 * <ul>
 * <li>all Java keywords / statements / expressions are passed through verbatim;</li>
 * <li>ire-specific sugar: group (→ public class), fc (→ public static method with implicit return), import{}, jar{};</li>
 * <li>type aliases: Int→Integer, Bool→Boolean, Char→Character, LLong→java.math.BigInteger, var→Object
 *     (Long/Double/String are identity);</li>
 * <li>one group per file (the unique public class); other non-public class/interface/enum are allowed;</li>
 * <li>ir invocation: {@code MyLib2;} / {@code MyLib2.Label;} inside function bodies.</li>
 * </ul>
 */
public class IRETranslator {

	/** ire type alias -> Java type name */
	private static final Map<String, String> TYPE_MAP = new HashMap<String, String>();
	static {
		TYPE_MAP.put("Int", "Integer");
		TYPE_MAP.put("Long", "Long");
		TYPE_MAP.put("Double", "Double");
		TYPE_MAP.put("Bool", "Boolean");
		TYPE_MAP.put("Char", "Character");
		TYPE_MAP.put("String", "String");
		TYPE_MAP.put("LLong", "java.math.BigInteger");
		TYPE_MAP.put("var", "Object");
	}

	/** fixed package name for generated classes */
	public static final String SCRIPT_PACKAGE = "sair.ire.script";

	private final String source;
	private String cleanedSource;
	private final Map<String, String> irPaths;

	private final StringBuilder out = new StringBuilder();
	private final List<String> javaImports = new ArrayList<String>();
	private final List<String> ireImports = new ArrayList<String>();
	private final List<String> irImports = new ArrayList<String>();
	private final List<String> jarPaths = new ArrayList<String>();

	/** fc 函数名 -> 解析后的返回类型（用于 var 类型推断） */
	private final Map<String, String> functionReturnTypes = new HashMap<String, String>();

	/** 跨 group 的函数签名：GroupName.funcName -> 返回类型（由 IRE.run 预扫描传入） */
	private final Map<String, String> globalFunctionTypes;

	/** 跨 group 的字段类型：GroupName.fieldName -> 类型（由 IRE.run 预扫描传入） */
	private final Map<String, String> globalFieldTypes;

	/** 函数体内局部变量名 -> 类型（用于 for(var x : list) 元素类型推断） */
	private final Map<String, String> localTypes = new HashMap<String, String>();

	/** 字段名 -> 类型（类作用域，用于 for(var x : 字段) 推断） */
	private final Map<String, String> fieldTypes = new HashMap<String, String>();

	/** 参数名 -> 类型（函数作用域，用于 for(var x : 参数) 推断） */
	private final Map<String, String> paramTypes = new HashMap<String, String>();

	private String groupName;
	private String javaSource;
	private String error;

	public IRETranslator(String source) {
		this(source, null, null, null);
	}

	public IRETranslator(String source, Map<String, String> irPaths) {
		this(source, irPaths, null, null);
	}

	public IRETranslator(String source, Map<String, String> irPaths, Map<String, String> globalFunctionTypes) {
		this(source, irPaths, globalFunctionTypes, null);
	}

	public IRETranslator(String source, Map<String, String> irPaths, Map<String, String> globalFunctionTypes,
			Map<String, String> globalFieldTypes) {
		this.source = source;
		this.irPaths = irPaths == null ? new HashMap<String, String>() : irPaths;
		this.globalFunctionTypes = globalFunctionTypes == null ? new HashMap<String, String>() : globalFunctionTypes;
		this.globalFieldTypes = globalFieldTypes == null ? new HashMap<String, String>() : globalFieldTypes;
	}

	// ------------------------------------------------------------------
	// public API
	// ------------------------------------------------------------------

	public boolean translate() {
		try {
			extractImportAndJar();
			collectFieldTypes();
			collectFunctionSignatures();
			emitHeader();
			transform();
			javaSource = out.toString();
			return true;
		} catch (Exception e) {
			error = e.getMessage();
			return false;
		}
	}

	/**
	 * only extract import{} / jar{} declarations, without transforming the body.
	 * Used by IRE.run collection phase to avoid double parsing.
	 */
	public boolean extractImports() {
		try {
			extractImportAndJar();
			return true;
		} catch (Exception e) {
			error = e.getMessage();
			return false;
		}
	}

	public String getJavaSource() {
		return javaSource;
	}

	public String getGroupName() {
		return groupName;
	}

	public String getError() {
		return error;
	}

	public String getFullClassName() {
		if (groupName == null)
			return null;
		return SCRIPT_PACKAGE + "." + groupName;
	}

	public List<String> getJavaImports() {
		return javaImports;
	}

	public List<String> getIreImports() {
		return ireImports;
	}

	public List<String> getIrImports() {
		return irImports;
	}

	public List<String> getJarPaths() {
		return jarPaths;
	}

	public Map<String, String> getFunctionReturnTypes() {
		return functionReturnTypes;
	}

	public Map<String, String> getFieldTypes() {
		return fieldTypes;
	}

	/**
	 * 预扫描：提取 group 名与所有 fc 函数签名（供 IRE.run 构建跨 group 的全局签名表）。
	 * 不产生转译输出，也不设置 groupName 字段（避免与 transform 的"单 group"校验冲突）。
	 */
	public String extractSignatures() {
		try {
			extractImportAndJar();
			collectFieldTypes();
			String gname = collectFunctionSignatures();
			return gname;
		} catch (Exception e) {
			error = e.getMessage();
			return null;
		}
	}

	// ------------------------------------------------------------------
	// import/jar block extraction (based on raw source, preserving \ : etc. in paths)
	// ------------------------------------------------------------------

	private void extractImportAndJar() {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		int n = source.length();
		while (i < n) {
			char c = source.charAt(i);

			if (c == '"') {
				int end = skipString(i);
				sb.append(source, i, end);
				i = end;
				continue;
			}
			if (c == '\'') {
				int end = skipChar(i);
				sb.append(source, i, end);
				i = end;
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = source.charAt(i + 1);
				if (d == '/') {
					int end = i;
					while (end < n && source.charAt(end) != '\n')
						end++;
					sb.append(source, i, end);
					i = end;
					continue;
				}
				if (d == '*') {
					int end = i + 2;
					while (end + 1 < n && !(source.charAt(end) == '*' && source.charAt(end + 1) == '/'))
						end++;
					end = Math.min(end + 2, n);
					sb.append(source, i, end);
					i = end;
					continue;
				}
			}

			if (c == 'i' && source.startsWith("import", i) && isBoundary(i - 1) && isBoundary(i + 6)) {
				int brace = findNextChar(i + 6, '{');
				if (brace >= 0) {
					int end = findMatchingBrace(brace);
					if (end >= 0) {
						parseImportContent(source.substring(brace + 1, end));
						sb.append(' ');
						i = end + 1;
						continue;
					}
				}
			}
			if (c == 'j' && source.startsWith("jar", i) && isBoundary(i - 1) && isBoundary(i + 3)) {
				int brace = findNextChar(i + 3, '{');
				if (brace >= 0) {
					int end = findMatchingBrace(brace);
					if (end >= 0) {
						parseJarContent(source.substring(brace + 1, end));
						sb.append(' ');
						i = end + 1;
						continue;
					}
				}
			}

			sb.append(c);
			i++;
		}
		cleanedSource = sb.toString();
	}

	private boolean isBoundary(int idx) {
		if (idx < 0 || idx >= source.length())
			return true;
		return !Character.isJavaIdentifierPart(source.charAt(idx));
	}

	private int skipString(int i) {
		int n = source.length();
		i++;
		while (i < n) {
			char d = source.charAt(i);
			if (d == '\\') {
				i += 2;
				continue;
			}
			if (d == '"') {
				i++;
				break;
			}
			i++;
		}
		return i;
	}

	private int skipChar(int i) {
		int n = source.length();
		i++;
		while (i < n) {
			char d = source.charAt(i);
			if (d == '\\') {
				i += 2;
				continue;
			}
			if (d == '\'') {
				i++;
				break;
			}
			i++;
		}
		return i;
	}

	private int findNextChar(int i, char target) {
		int n = source.length();
		while (i < n) {
			char c = source.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (c == target)
				return i;
			return -1;
		}
		return -1;
	}

	private int findMatchingBrace(int brace) {
		int n = source.length();
		int depth = 0;
		int i = brace;
		while (i < n) {
			char c = source.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = source.charAt(i + 1);
				if (d == '/') {
					while (i < n && source.charAt(i) != '\n')
						i++;
					continue;
				}
				if (d == '*') {
					i += 2;
					while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/'))
						i++;
					i += 2;
					continue;
				}
			}
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0)
					return i;
			}
			i++;
		}
		return -1;
	}

	private void parseImportContent(String content) {
		String[] items = content.split("[;\n\r]");
		for (String item : items) {
			item = stripLineComment(item).trim();
			if (item.isEmpty())
				continue;
			if (item.startsWith("java:")) {
				javaImports.add(item.substring("java:".length()).trim());
			} else if (item.toLowerCase().endsWith(".ire")) {
				ireImports.add(item);
			} else if (item.toLowerCase().endsWith(".ir")) {
				irImports.add(item);
			} else {
				ireImports.add(item);
			}
		}
	}

	private void parseJarContent(String content) {
		String[] items = content.split("[;\n\r]");
		for (String item : items) {
			item = stripLineComment(item).trim();
			if (!item.isEmpty())
				jarPaths.add(item);
		}
	}

	private static String stripLineComment(String s) {
		int idx = s.indexOf("//");
		if (idx >= 0)
			return s.substring(0, idx);
		return s;
	}

	// ------------------------------------------------------------------
	// header + main transformation
	// ------------------------------------------------------------------

	private void emitHeader() {
		out.append("package ").append(SCRIPT_PACKAGE).append(";\n\n");
		out.append("import sair.ire.IREHelper;\n");
		for (String imp : javaImports)
			out.append("import ").append(imp).append(";\n");
		if (!javaImports.isEmpty())
			out.append('\n');
	}

	/**
	 * 主扫描：逐字符透传，仅识别 group / fc / 类型别名，其余（含所有 Java 关键字、
	 * class/interface/enum、语句、表达式、注解、泛型、lambda）原样复制。
	 */
	private void transform() {
		int i = 0, n = cleanedSource.length();
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				int e = skipString(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '\'') {
				int e = skipChar(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					int e = i;
					while (e < n && cleanedSource.charAt(e) != '\n')
						e++;
					out.append(cleanedSource, i, e);
					i = e;
					continue;
				}
				if (d == '*') {
					int e = i + 2;
					while (e + 1 < n && !(cleanedSource.charAt(e) == '*' && cleanedSource.charAt(e + 1) == '/'))
						e++;
					e = Math.min(e + 2, n);
					out.append(cleanedSource, i, e);
					i = e;
					continue;
				}
			}
			if (isIdentStart(c)) {
				int e = readIdent(i);
				String w = cleanedSource.substring(i, e);
				if (w.equals("group")) {
					i = transformGroup(e);
					continue;
				}
				if (w.equals("fc")) {
					i = transformFc(e, "");
					continue;
				}
				if (w.equals("var")) {
					i = transformVar(e, true);
					continue;
				}
				if (w.equals("class") || w.equals("interface") || w.equals("enum")) {
					i = transformClassType(e, w);
					continue;
				}
				if (isModifier(w)) {
					int ni = tryModifierBlock(i);
					if (ni >= 0) {
						i = ni;
						continue;
					}
					ni = tryModifierFc(i);
					if (ni >= 0) {
						i = ni;
						continue;
					}
				}
				String m = TYPE_MAP.get(w);
				if (m != null && !m.equals(w))
					out.append(m);
				else
					out.append(w);
				i = e;
				continue;
			}
			out.append(c);
			i++;
		}
	}

	/** group Name → public class Name（extends/implements/泛型/body 由主循环透传） */
	private int transformGroup(int i) {
		if (groupName != null)
			throw new IllegalStateException("one .ire file may contain only one group");
		int j = skipWs(i);
		int e = readIdent(j);
		if (e == j)
			throw new IllegalStateException("group requires a name");
		groupName = cleanedSource.substring(j, e);
		out.append("public class ").append(groupName);
		return e;
	}

	/** fc [返回类型] name(params) { body }（默认非 static、隐式返回糖、强制返回）；prefixMods 为 fc 之前的前置修饰符 */
	private int transformFc(int i, String prefixMods) {
		int n = cleanedSource.length();
		// 1. 找到参数列表的 '('（返回类型不含括号；注解带参是极少数边界情况）
		int paren = findParamParen(i);
		if (paren < 0)
			throw new IllegalStateException("fc: cannot find '(' of parameter list");

		// 2. [i, paren) = [返回类型] 函数名
		int[] nameRange = findLastIdent(i, paren);
		if (nameRange == null)
			throw new IllegalStateException("fc: cannot find function name");
		int nameStart = nameRange[0], nameEnd = nameRange[1];
		String funcName = cleanedSource.substring(nameStart, nameEnd);
		String retType = cleanedSource.substring(i, nameStart).trim();

		// 3. 参数列表（先收集参数类型，供返回类型推断引用参数）
		int closeParen = findMatchingParen(paren);
		if (closeParen < 0)
			throw new IllegalStateException("fc: unclosed parameter list");
		paramTypes.clear();
		collectParamTypes(paren + 1, closeParen);

		// 4. 函数体
		int k = skipWs(closeParen + 1);
		if (k >= n || cleanedSource.charAt(k) != '{')
			throw new IllegalStateException("fc: expected '{' for function body");
		int closeBrace = findMatchingBrace(k);
		if (closeBrace < 0)
			throw new IllegalStateException("fc: unclosed function body");

		// 5. 确定返回类型：fc 强制返回；无显式返回类型时从函数体推断；fc 与 void 冲突
		String resolvedRet;
		if (retType.isEmpty()) {
			resolvedRet = inferReturnType(k, closeBrace);
		} else {
			resolvedRet = resolveRegion(i, nameStart).trim();
			if ("void".equals(resolvedRet))
				throw new IllegalStateException("fc 与 void 冲突：fc 强制返回，不能声明为 void");
		}

		// 6. 前置修饰符（fc 之前：如 private static fc foo()）
		Set<String> mods = new HashSet<String>();
		if (prefixMods != null) {
			for (String m : prefixMods.trim().split("\\s+")) {
				if (!m.isEmpty())
					mods.add(m);
			}
		}

		// 7. 组装修饰符（fc 与 Java 完全一致：不默认 public、不默认 static，作用范围/静态需修饰符显式指定）
		StringBuilder mods2 = new StringBuilder();
		if (mods.contains("public"))
			mods2.append("public");
		else if (mods.contains("private"))
			mods2.append("private");
		else if (mods.contains("protected"))
			mods2.append("protected");
		if (mods.contains("static")) {
			if (mods2.length() > 0)
				mods2.append(' ');
			mods2.append("static");
		}
		for (String m : new String[] { "final", "synchronized", "abstract", "volatile", "transient", "native",
				"strictfp" }) {
			if (mods.contains(m)) {
				if (mods2.length() > 0)
					mods2.append(' ');
				mods2.append(m);
			}
		}

		// 8. 输出签名
		StringBuilder sig = new StringBuilder();
		if (mods2.length() > 0)
			sig.append(mods2).append(' ');
		sig.append(resolvedRet).append(' ').append(funcName).append('(');
		out.append(sig);

		// 9. 参数列表
		copyResolved(paren + 1, closeParen);
		out.append(')');

		// 10. 函数体
		copyBody(k, closeBrace, isPrimitiveOrVoid(resolvedRet));

		return closeBrace + 1;
	}

	/** 推断 fc 无显式返回类型时的返回类型：扫描顶层 return，无 return 则 Object（补 return null） */
	private String inferReturnType(int openBrace, int closeBrace) {
		int depth = 0;
		int i = openBrace + 1;
		String result = null;
		while (i < closeBrace) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (c == '/' && i + 1 < closeBrace) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					while (i < closeBrace && cleanedSource.charAt(i) != '\n')
						i++;
					continue;
				}
				if (d == '*') {
					i += 2;
					while (i + 1 < closeBrace && !(cleanedSource.charAt(i) == '*' && cleanedSource.charAt(i + 1) == '/'))
						i++;
					i += 2;
					continue;
				}
			}
			if (c == '{') {
				depth++;
				i++;
				continue;
			}
			if (c == '}') {
				depth--;
				i++;
				continue;
			}
			if (isIdentStart(c)) {
				int e = readIdent(i);
				String w = cleanedSource.substring(i, e);
				if (depth == 0 && w.equals("return")) {
					int afterReturn = skipWs(e);
					String t;
					if (afterReturn < closeBrace && cleanedSource.charAt(afterReturn) == ';')
						t = "Object";
					else
						t = inferExprType(e);
					result = (result == null) ? t : commonType(result, t);
				}
				i = e;
				continue;
			}
			i++;
		}
		return result == null ? "Object" : result;
	}

	/** class/interface/enum 声明头：把 '+' 替换为 extends、':' 替换为 implements（泛型 <> 内不替换） */
	private int transformClassType(int i, String keyword) {
		int n = cleanedSource.length();
		out.append(keyword);
		int genericDepth = 0;
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				int e = skipString(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '\'') {
				int e = skipChar(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					int e = i;
					while (e < n && cleanedSource.charAt(e) != '\n')
						e++;
					out.append(cleanedSource, i, e);
					i = e;
					continue;
				}
				if (d == '*') {
					int e = i + 2;
					while (e + 1 < n && !(cleanedSource.charAt(e) == '*' && cleanedSource.charAt(e + 1) == '/'))
						e++;
					e = Math.min(e + 2, n);
					out.append(cleanedSource, i, e);
					i = e;
					continue;
				}
			}
			if (c == '<') {
				genericDepth++;
				out.append(c);
				i++;
				continue;
			}
			if (c == '>') {
				genericDepth--;
				out.append(c);
				i++;
				continue;
			}
			if (c == '{' || c == ';') {
				// 声明头结束，'{' 或 ';' 交回主循环处理
				return i;
			}
			if (genericDepth == 0) {
				if (c == '+' && i + 1 < n && cleanedSource.charAt(i + 1) != '+') {
					out.append(" extends ");
					i++;
					continue;
				}
				if (c == ':' && i + 1 < n && cleanedSource.charAt(i + 1) != ':') {
					out.append(" implements ");
					i++;
					continue;
				}
			}
			out.append(c);
			i++;
		}
		return i;
	}

	/** 检测批量修饰符作用域：<mods> : { ... }。是则展开并返回新索引，否则返回 -1 */
	private int tryModifierBlock(int i) {
		int n = cleanedSource.length();
		int p = i;
		StringBuilder mods = new StringBuilder();
		while (true) {
			p = skipWs(p);
			if (p >= n || !isIdentStart(cleanedSource.charAt(p)))
				break;
			int e = readIdent(p);
			String w = cleanedSource.substring(p, e);
			if (isModifier(w)) {
				if (mods.length() > 0)
					mods.append(' ');
				mods.append(w);
				p = e;
			} else {
				break;
			}
		}
		if (mods.length() == 0)
			return -1;
		p = skipWs(p);
		if (p >= n || cleanedSource.charAt(p) != ':')
			return -1;
		p = skipWs(p + 1);
		if (p >= n || cleanedSource.charAt(p) != '{')
			return -1;
		return expandModifierBlock(mods.toString(), p);
	}

	/** 检测带前置修饰符的 fc：<mods> fc ...。是则转译并返回新索引，否则返回 -1 */
	private int tryModifierFc(int i) {
		int n = cleanedSource.length();
		int p = i;
		StringBuilder mods = new StringBuilder();
		while (true) {
			p = skipWs(p);
			if (p >= n || !isIdentStart(cleanedSource.charAt(p)))
				break;
			int e = readIdent(p);
			String w = cleanedSource.substring(p, e);
			if (isModifier(w)) {
				if (mods.length() > 0)
					mods.append(' ');
				mods.append(w);
				p = e;
			} else {
				break;
			}
		}
		if (mods.length() == 0)
			return -1;
		p = skipWs(p);
		if (p + 1 >= n || cleanedSource.charAt(p) != 'f' || cleanedSource.charAt(p + 1) != 'c')
			return -1;
		int afterFc = p + 2;
		if (afterFc < n && Character.isJavaIdentifierPart(cleanedSource.charAt(afterFc)))
			return -1;
		return transformFc(afterFc, mods.toString());
	}

	/** 展开批量修饰符作用域：把修饰符应用到块内每个字段声明 */
	private int expandModifierBlock(String mods, int brace) {
		int n = cleanedSource.length();
		int i = brace + 1;
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				int e = skipString(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '\'') {
				int e = skipChar(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					int e = i;
					while (e < n && cleanedSource.charAt(e) != '\n')
						e++;
					out.append(cleanedSource, i, e);
					i = e;
					continue;
				}
				if (d == '*') {
					int e = i + 2;
					while (e + 1 < n && !(cleanedSource.charAt(e) == '*' && cleanedSource.charAt(e + 1) == '/'))
						e++;
					e = Math.min(e + 2, n);
					out.append(cleanedSource, i, e);
					i = e;
					continue;
				}
			}
			if (c == '}') {
				// 块结束
				return i + 1;
			}
			if (Character.isWhitespace(c)) {
				out.append(c);
				i++;
				continue;
			}
			if (isIdentStart(c)) {
				int e = readIdent(i);
				String w = cleanedSource.substring(i, e);
				if (w.equals("fc")) {
					// fc 方法：块修饰符作为前置修饰符
					i = transformFc(e, mods);
					continue;
				}
			}
			// 字段声明开始：加修饰符前缀
			out.append(mods).append(' ');
			i = expandFieldDecl(i);
		}
		return i;
	}

	/** 透传一个字段声明（直到顶层 ';' 或块结束 '}'），处理 var 与类型别名，返回新索引 */
	private int expandFieldDecl(int i) {
		int n = cleanedSource.length();
		int depth = 0;
		while (i < n) {
			char d = cleanedSource.charAt(i);
			if (d == '"') {
				int e = skipString(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (d == '\'') {
				int e = skipChar(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (d == '/' && i + 1 < n) {
				char e = cleanedSource.charAt(i + 1);
				if (e == '/') {
					int j = i;
					while (j < n && cleanedSource.charAt(j) != '\n')
						j++;
					out.append(cleanedSource, i, j);
					i = j;
					continue;
				}
				if (e == '*') {
					int j = i + 2;
					while (j + 1 < n && !(cleanedSource.charAt(j) == '*' && cleanedSource.charAt(j + 1) == '/'))
						j++;
					j = Math.min(j + 2, n);
					out.append(cleanedSource, i, j);
					i = j;
					continue;
				}
			}
			if (d == '{') {
				depth++;
				out.append(d);
				i++;
				continue;
			}
			if (d == '}') {
				if (depth == 0)
					return i;
				depth--;
				out.append(d);
				i++;
				continue;
			}
			if (d == ';' && depth == 0) {
				out.append(d);
				return i + 1;
			}
			if (isIdentStart(d)) {
				int e = readIdent(i);
				String w = cleanedSource.substring(i, e);
				if (w.equals("var")) {
					i = transformVar(e, true);
					continue;
				}
				String m = TYPE_MAP.get(w);
				if (m != null && !m.equals(w))
					out.append(m);
				else
					out.append(w);
				i = e;
				continue;
			}
			out.append(d);
			i++;
		}
		return i;
	}

	// ------------------------------------------------------------------
	// var 类型推断
	// ------------------------------------------------------------------

	/** 预扫描：收集所有 fc 函数的返回类型，并返回 group 名（供 var 推断方法调用返回值） */
	private String collectFunctionSignatures() {
		String gname = null;
		int i = 0, n = cleanedSource.length();
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					while (i < n && cleanedSource.charAt(i) != '\n')
						i++;
					continue;
				}
				if (d == '*') {
					i += 2;
					while (i + 1 < n && !(cleanedSource.charAt(i) == '*' && cleanedSource.charAt(i + 1) == '/'))
						i++;
					i += 2;
					continue;
				}
			}
			if (isIdentStart(c)) {
				int e = readIdent(i);
				String w = cleanedSource.substring(i, e);
				if (w.equals("group") && gname == null) {
					int j = skipWs(e);
					int nameEnd = readIdent(j);
					if (nameEnd > j)
						gname = cleanedSource.substring(j, nameEnd);
				} else if (w.equals("fc")) {
					collectOneFunctionSignature(e);
				}
				i = e;
				continue;
			}
			i++;
		}
		return gname;
	}

	/** 收集单个 fc 的返回类型（无显式返回类型时从函数体推断），供 var 推断方法调用返回值 */
	private void collectOneFunctionSignature(int i) {
		int n = cleanedSource.length();
		int paren = findParamParen(i);
		if (paren < 0)
			return;
		int[] nameRange = findLastIdent(i, paren);
		if (nameRange == null)
			return;
		int nameStart = nameRange[0], nameEnd = nameRange[1];
		String name = cleanedSource.substring(nameStart, nameEnd);
		String ret = cleanedSource.substring(i, nameStart).trim();

		int closeParen = findMatchingParen(paren);
		if (closeParen < 0)
			return;

		// 参数类型（供返回类型推断引用参数）
		paramTypes.clear();
		collectParamTypes(paren + 1, closeParen);

		// 函数体
		int k = skipWs(closeParen + 1);
		if (k >= n || cleanedSource.charAt(k) != '{')
			return;
		int closeBrace = findMatchingBrace(k);
		if (closeBrace < 0)
			return;

		// 确定返回类型
		String resolvedRet;
		if (ret.isEmpty()) {
			resolvedRet = inferReturnType(k, closeBrace);
		} else {
			resolvedRet = resolveRegion(i, nameStart).trim();
		}
		functionReturnTypes.put(name, resolvedRet);
	}

	/** 预扫描：收集类体中的字段类型（跳过函数体，供字段访问推断与跨 group 字段表） */
	private void collectFieldTypes() {
		int i = 0, n = cleanedSource.length();
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					while (i < n && cleanedSource.charAt(i) != '\n')
						i++;
					continue;
				}
				if (d == '*') {
					i += 2;
					while (i + 1 < n && !(cleanedSource.charAt(i) == '*' && cleanedSource.charAt(i + 1) == '/'))
						i++;
					i += 2;
					continue;
				}
			}
			if (isIdentStart(c)) {
				int e = readIdent(i);
				String w = cleanedSource.substring(i, e);
				if (w.equals("fc")) {
					// 跳过整个函数（含 body）
					int paren = findParamParen(e);
					if (paren >= 0) {
						int closeParen = findMatchingParen(paren);
						if (closeParen >= 0) {
							int k = skipWs(closeParen + 1);
							if (k < n && cleanedSource.charAt(k) == '{') {
								int closeBrace = findMatchingBrace(k);
								if (closeBrace >= 0) {
									i = closeBrace + 1;
									continue;
								}
							}
						}
					}
					i = e;
					continue;
				}
				tryCollectField(i);
				i = e;
				continue;
			}
			i++;
		}
	}

	/** var 推断：var name = expr → 推断类型；var name : iterable → 推断元素类型；否则回退 Object */
	private int transformVar(int i, boolean isField) {
		int j = skipWs(i);
		int nameEnd = readIdent(j);
		if (nameEnd > j) {
			String name = cleanedSource.substring(j, nameEnd);
			int k = skipWs(nameEnd);
			if (k < cleanedSource.length()) {
				char ch = cleanedSource.charAt(k);
				if (ch == '=') {
					String t = inferExprType(k + 1);
					(isField ? fieldTypes : localTypes).put(name, t);
					out.append(t);
					return i;
				}
				if (ch == ':') {
					// for-each：var x : iterable
					String t = inferIterableElementType(k + 1);
					localTypes.put(name, t);
					out.append(t);
					return i;
				}
			}
		}
		out.append("Object");
		return i;
	}

	/** 查找变量类型：局部 → 参数 → 字段 */
	private String lookupType(String name) {
		String t = localTypes.get(name);
		if (t != null)
			return t;
		t = paramTypes.get(name);
		if (t != null)
			return t;
		return fieldTypes.get(name);
	}

	/** 推断 for-each 迭代源的元素类型（支持变量、数组、Iterable、Map 的 entrySet/keySet/values） */
	private String inferIterableElementType(int i) {
		int j = skipWs(i);
		if (j >= cleanedSource.length() || !isIdentStart(cleanedSource.charAt(j)))
			return "Object";
		int e = readIdent(j);
		String name = cleanedSource.substring(j, e);
		String type = lookupType(name);
		if (type == null)
			return "Object";
		// 方法链：.entrySet() / .keySet() / .values()
		int p = skipWs(e);
		if (p < cleanedSource.length() && cleanedSource.charAt(p) == '.') {
			int q = readIdent(p + 1);
			if (q > p + 1) {
				String method = cleanedSource.substring(p + 1, q);
				int r = skipWs(q);
				if (r < cleanedSource.length() && cleanedSource.charAt(r) == '(') {
					if (method.equals("entrySet"))
						return mapEntryType(type);
					if (method.equals("keySet"))
						return mapKeyType(type);
					if (method.equals("values"))
						return mapValueType(type);
				}
			}
		}
		return elementType(type);
	}

	private String mapEntryType(String type) {
		String[] kv = mapKeyValue(type);
		return kv == null ? "Object" : "java.util.Map.Entry<" + kv[0] + "," + kv[1] + ">";
	}

	private String mapKeyType(String type) {
		String[] kv = mapKeyValue(type);
		return kv == null ? "Object" : kv[0];
	}

	private String mapValueType(String type) {
		String[] kv = mapKeyValue(type);
		return kv == null ? "Object" : kv[1];
	}

	/** 已知集合/字符串方法的返回类型推断（链式方法调用） */
	private String knownMethodReturn(String type, String method) {
		if (method.equals("get")) {
			if (isMapType(type))
				return mapValueType(type);
			return elementType(type);
		}
		if (method.equals("size") || method.equals("length"))
			return "int";
		if (method.equals("isEmpty"))
			return "boolean";
		if ("String".equals(type)) {
			if (method.equals("charAt"))
				return "char";
			if (method.equals("substring") || method.equals("trim") || method.equals("toUpperCase")
					|| method.equals("toLowerCase"))
				return "String";
		}
		return "Object";
	}

	private boolean isMapType(String type) {
		if (type == null)
			return false;
		type = type.trim();
		int lt = type.indexOf('<');
		String base = lt >= 0 ? type.substring(0, lt).trim() : type;
		return base.endsWith("Map");
	}

	/** 若 type 是 Map<K,V>，返回 [K, V]；否则返回 null */
	private String[] mapKeyValue(String type) {
		type = type.trim();
		int lt = type.indexOf('<');
		if (lt < 0 || !type.endsWith(">"))
			return null;
		String base = type.substring(0, lt).trim();
		if (!base.endsWith("Map"))
			return null;
		String args = type.substring(lt + 1, type.length() - 1).trim();
		int comma = args.indexOf(',');
		if (comma < 0)
			return null;
		return new String[] { args.substring(0, comma).trim(), args.substring(comma + 1).trim() };
	}

	/** 识别类体中的字段声明并记录类型（Type name [= ... | , ... | ;]） */
	private void tryCollectField(int i) {
		TypeResult t = readType(i);
		if (t.end <= i)
			return;
		int j = skipWs(t.end);
		if (j < cleanedSource.length() && isIdentStart(cleanedSource.charAt(j))) {
			int nameEnd = readIdent(j);
			String name = cleanedSource.substring(j, nameEnd);
			int k = skipWs(nameEnd);
			if (k < cleanedSource.length()) {
				char ch = cleanedSource.charAt(k);
				if (ch == '=' || ch == ';' || ch == ',')
					fieldTypes.put(name, t.type);
			}
		}
	}

	/** 收集参数类型（Type name, ...），供 for(var x : 参数) 推断 */
	private void collectParamTypes(int start, int end) {
		int i = start;
		while (i < end) {
			i = skipWs(i);
			if (i >= end)
				break;
			// 跳过修饰符
			while (i < end && isIdentStart(cleanedSource.charAt(i))) {
				int e = readIdent(i);
				if (isModifier(cleanedSource.substring(i, e)))
					i = skipWs(e);
				else
					break;
			}
			TypeResult t = readType(i);
			if (t.end <= i)
				break;
			i = skipWs(t.end);
			// 可变参数 ...
			if (i + 2 < end && cleanedSource.charAt(i) == '.' && cleanedSource.charAt(i + 1) == '.'
					&& cleanedSource.charAt(i + 2) == '.')
				i = skipWs(i + 3);
			if (i < end && isIdentStart(cleanedSource.charAt(i))) {
				int nameEnd = readIdent(i);
				paramTypes.put(cleanedSource.substring(i, nameEnd), t.type);
				i = nameEnd;
			}
			i = skipWs(i);
			if (i < end && cleanedSource.charAt(i) == ',')
				i++;
		}
	}

	private static boolean isModifier(String w) {
		return w.equals("final") || w.equals("static") || w.equals("public") || w.equals("private")
				|| w.equals("protected") || w.equals("abstract") || w.equals("synchronized")
				|| w.equals("volatile") || w.equals("transient") || w.equals("native") || w.equals("strictfp");
	}

	/** 从集合/数组类型提取元素类型：T[] → T；Iterable<T>（List/Set/Collection 等）→ T */
	private String elementType(String type) {
		if (type == null)
			return "Object";
		type = type.trim();
		if (type.endsWith("[]"))
			return type.substring(0, type.length() - 2);
		int lt = type.indexOf('<');
		if (lt >= 0 && type.endsWith(">")) {
			String args = type.substring(lt + 1, type.length() - 1).trim();
			int comma = args.indexOf(',');
			if (comma >= 0)
				args = args.substring(0, comma).trim();
			return args;
		}
		return "Object";
	}

	/** 推断时的当前位置（递归下降解析用） */
	private int inferPos;

	/**
	 * 从初始值表达式推断类型（递归下降）：
	 * 字面量 / 变量引用 / 方法调用 / new / 强转 / 括号 / 一元 / 二元 / 三元 / 数组访问。
	 */
	private String inferExprType(int start) {
		inferPos = start;
		return inferTernary();
	}

	private String inferTernary() {
		String t = inferBinary();
		int save = inferPos;
		inferPos = skipWs(inferPos);
		if (inferPos < cleanedSource.length() && cleanedSource.charAt(inferPos) == '?') {
			inferPos++;
			String a = inferExprType(inferPos);
			inferPos = skipWs(inferPos);
			if (inferPos < cleanedSource.length() && cleanedSource.charAt(inferPos) == ':') {
				inferPos++;
				String b = inferExprType(inferPos);
				return commonType(a, b);
			}
		}
		inferPos = save;
		return t;
	}

	private String inferBinary() {
		String t = inferUnary();
		while (true) {
			int save = inferPos;
			inferPos = skipWs(inferPos);
			String op = readBinaryOp(inferPos);
			if (op == null) {
				inferPos = save;
				return t;
			}
			inferPos += op.length();
			String rhs = inferUnary();
			t = combineBinary(t, op, rhs);
		}
	}

	private String inferUnary() {
		inferPos = skipWs(inferPos);
		if (inferPos >= cleanedSource.length())
			return "Object";
		char c = cleanedSource.charAt(inferPos);
		if (c == '-' || c == '+' || c == '!' || c == '~') {
			inferPos++;
			String t = inferUnary();
			return c == '!' ? "boolean" : t;
		}
		return inferPostfix();
	}

	private String inferPostfix() {
		String t = inferPrimary();
		while (true) {
			inferPos = skipWs(inferPos);
			if (inferPos >= cleanedSource.length())
				return t;
			char c = cleanedSource.charAt(inferPos);
			if (c == '[') {
				t = elementType(t);
				int close = findMatchingBracket(inferPos);
				if (close < 0)
					return t;
				inferPos = close + 1;
				continue;
			}
			if (c == '.') {
				inferPos++;
				int e = readIdent(inferPos);
				if (e <= inferPos)
					return "Object";
				String name = cleanedSource.substring(inferPos, e);
				inferPos = e;
				inferPos = skipWs(inferPos);
				if (inferPos < cleanedSource.length() && cleanedSource.charAt(inferPos) == '(') {
					// 链式方法调用：已知集合方法推断，否则 Object
					t = knownMethodReturn(t, name);
					continue;
				}
				// 字段访问：查字段类型（本 group）
				String ft = fieldTypes.get(name);
				t = ft != null ? ft : "Object";
				continue;
			}
			return t;
		}
	}

	private String inferPrimary() {
		inferPos = skipWs(inferPos);
		if (inferPos >= cleanedSource.length())
			return "Object";
		char c = cleanedSource.charAt(inferPos);
		if (c == '"')
			return "String";
		if (c == '\'')
			return "char";
		if (Character.isDigit(c)) {
			String t = inferNumberType(inferPos);
			while (inferPos < cleanedSource.length()
					&& (Character.isLetterOrDigit(cleanedSource.charAt(inferPos)) || cleanedSource.charAt(inferPos) == '.'
							|| cleanedSource.charAt(inferPos) == '_'))
				inferPos++;
			return t;
		}
		if (c == '(') {
			int close = findMatchingParen(inferPos);
			if (close >= 0) {
				TypeResult t = readType(inferPos + 1);
				if (skipWs(t.end) == close) {
					// 强转 (Type) expr
					inferPos = close + 1;
					return t.type;
				}
				// 括号表达式 (expr)
				inferPos++;
				String inner = inferExprType(inferPos);
				inferPos = close + 1;
				return inner;
			}
			return "Object";
		}
		if (isIdentStart(c)) {
			int e = readIdent(inferPos);
			String w = cleanedSource.substring(inferPos, e);
			inferPos = e;
			if (w.equals("true") || w.equals("false"))
				return "boolean";
			if (w.equals("null"))
				return "Object";
			if (w.equals("new")) {
				TypeResult t = readType(skipWs(inferPos));
				inferPos = t.end;
				return t.type;
			}
			// 简单变量引用：返回类型，后续 .field 由 inferPostfix 处理
			String varType = lookupType(w);
			if (varType != null)
				return varType;
			// 读取完整限定名（方法调用 MyLib.foo() 或静态字段 MyLib.CONST）
			StringBuilder qname = new StringBuilder(w);
			while (inferPos < cleanedSource.length() && cleanedSource.charAt(inferPos) == '.') {
				int q = readIdent(inferPos + 1);
				if (q <= inferPos + 1)
					break;
				qname.append('.').append(cleanedSource.substring(inferPos + 1, q));
				inferPos = q;
			}
			inferPos = skipWs(inferPos);
			if (inferPos < cleanedSource.length() && cleanedSource.charAt(inferPos) == '(') {
				// 方法调用：先查本 group，再查全局（Group.func）
				String ret = functionReturnTypes.get(qname.toString());
				if (ret == null)
					ret = globalFunctionTypes.get(qname.toString());
				return ret != null ? ret : "Object";
			}
			// 静态字段访问（Group.field）
			String t = globalFieldTypes.get(qname.toString());
			return t != null ? t : "Object";
		}
		return "Object";
	}

	private String combineBinary(String a, String op, String b) {
		if (op.equals("+")) {
			if ("String".equals(a) || "String".equals(b))
				return "String";
			return numericPromote(a, b);
		}
		if (op.equals("-") || op.equals("*") || op.equals("/") || op.equals("%"))
			return numericPromote(a, b);
		if (op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=") || op.equals("==")
				|| op.equals("!="))
			return "boolean";
		if (op.equals("&&") || op.equals("||"))
			return "boolean";
		if (op.equals("&") || op.equals("|") || op.equals("^")) {
			if ("boolean".equals(a) && "boolean".equals(b))
				return "boolean";
			return numericPromote(a, b);
		}
		if (op.equals("<<") || op.equals(">>") || op.equals(">>>"))
			return numericPromote(a, a);
		return "Object";
	}

	private String numericPromote(String a, String b) {
		int r = Math.max(numericRank(a), numericRank(b));
		switch (r) {
		case 4:
			return "double";
		case 3:
			return "float";
		case 2:
			return "long";
		default:
			return "int";
		}
	}

	private int numericRank(String t) {
		if (t == null)
			return 0;
		if (t.equals("double"))
			return 4;
		if (t.equals("float"))
			return 3;
		if (t.equals("long"))
			return 2;
		if (t.equals("int") || t.equals("short") || t.equals("byte") || t.equals("char"))
			return 1;
		return 0;
	}

	private String commonType(String a, String b) {
		if (a == null || a.equals("Object"))
			return b == null ? "Object" : b;
		if (b == null || b.equals("Object"))
			return a;
		if (a.equals(b))
			return a;
		if (numericRank(a) > 0 && numericRank(b) > 0)
			return numericPromote(a, b);
		return "Object";
	}

	private String readBinaryOp(int i) {
		int n = cleanedSource.length();
		if (i >= n)
			return null;
		char c = cleanedSource.charAt(i);
		if (i + 2 < n) {
			String three = cleanedSource.substring(i, i + 3);
			if (three.equals(">>>"))
				return three;
		}
		if (i + 1 < n) {
			String two = cleanedSource.substring(i, i + 2);
			if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("&&")
					|| two.equals("||") || two.equals("<<") || two.equals(">>"))
				return two;
		}
		if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '<' || c == '>' || c == '&' || c == '|'
				|| c == '^')
			return String.valueOf(c);
		return null;
	}

	/** 数字字面量类型推断 */
	private String inferNumberType(int i) {
		int n = cleanedSource.length();
		int e = i;
		while (e < n && (Character.isLetterOrDigit(cleanedSource.charAt(e)) || cleanedSource.charAt(e) == '.'
				|| cleanedSource.charAt(e) == '_'))
			e++;
		String num = cleanedSource.substring(i, e);
		char last = num.charAt(num.length() - 1);
		if (num.startsWith("0x") || num.startsWith("0X") || num.startsWith("0b") || num.startsWith("0B"))
			return (last == 'l' || last == 'L') ? "long" : "int";
		if (last == 'f' || last == 'F')
			return "float";
		if (last == 'd' || last == 'D')
			return "double";
		if (last == 'l' || last == 'L')
			return "long";
		if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0)
			return "double";
		return "int";
	}

	/** 类型读取结果 */
	private static class TypeResult {
		final String type;
		final int end;

		TypeResult(String type, int end) {
			this.type = type;
			this.end = end;
		}
	}

	/** 读取一个类型（限定名 + 泛型 + 数组维度） */
	private TypeResult readType(int i) {
		int n = cleanedSource.length();
		StringBuilder sb = new StringBuilder();
		// 限定名
		while (i < n && isIdentStart(cleanedSource.charAt(i))) {
			int e = readIdent(i);
			String w = cleanedSource.substring(i, e);
			String m = TYPE_MAP.get(w);
			sb.append(m != null && !m.equals(w) ? m : w);
			i = e;
			if (i < n && cleanedSource.charAt(i) == '.') {
				sb.append('.');
				i++;
			} else {
				break;
			}
		}
		// 泛型实参
		while (i < n && cleanedSource.charAt(i) == '<') {
			int close = findMatchingAngle(i);
			if (close < 0)
				break;
			sb.append(resolveRegion(i, close + 1));
			i = close + 1;
		}
		// 数组维度
		while (i < n && cleanedSource.charAt(i) == '[') {
			int close = findMatchingBracket(i);
			if (close < 0)
				break;
			sb.append("[]");
			i = close + 1;
		}
		return new TypeResult(sb.toString(), i);
	}

	private int findMatchingAngle(int open) {
		int depth = 0, i = open, n = cleanedSource.length();
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (c == '<')
				depth++;
			else if (c == '>') {
				depth--;
				if (depth == 0)
					return i;
			}
			i++;
		}
		return -1;
	}

	private int findMatchingBracket(int open) {
		int depth = 0, i = open, n = cleanedSource.length();
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (c == '[')
				depth++;
			else if (c == ']') {
				depth--;
				if (depth == 0)
					return i;
			}
			i++;
		}
		return -1;
	}

	// ------------------------------------------------------------------
	// scanning helpers
	// ------------------------------------------------------------------

	private int findParamParen(int i) {
		int n = cleanedSource.length();
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					while (i < n && cleanedSource.charAt(i) != '\n')
						i++;
					continue;
				}
				if (d == '*') {
					i += 2;
					while (i + 1 < n && !(cleanedSource.charAt(i) == '*' && cleanedSource.charAt(i + 1) == '/'))
						i++;
					i += 2;
					continue;
				}
			}
			if (c == '(')
				return i;
			i++;
		}
		return -1;
	}

	private int[] findLastIdent(int start, int end) {
		int lastStart = -1, lastEnd = -1;
		int i = start;
		while (i < end) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (isIdentStart(c)) {
				int e = readIdent(i);
				lastStart = i;
				lastEnd = e;
				i = e;
				continue;
			}
			i++;
		}
		if (lastStart < 0)
			return null;
		return new int[] { lastStart, lastEnd };
	}

	private int findMatchingParen(int open) {
		int depth = 0, i = open, n = cleanedSource.length();
		while (i < n) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				i = skipString(i);
				continue;
			}
			if (c == '\'') {
				i = skipChar(i);
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					while (i < n && cleanedSource.charAt(i) != '\n')
						i++;
					continue;
				}
				if (d == '*') {
					i += 2;
					while (i + 1 < n && !(cleanedSource.charAt(i) == '*' && cleanedSource.charAt(i + 1) == '/'))
						i++;
					i += 2;
					continue;
				}
			}
			if (c == '(')
				depth++;
			else if (c == ')') {
				depth--;
				if (depth == 0)
					return i;
			}
			i++;
		}
		return -1;
	}

	/** 复制 [start, end) 到 out，解析类型别名（字符串/字符/注释原样复制） */
	private void copyResolved(int start, int end) {
		out.append(resolveRegion(start, end));
	}

	/** 返回 [start, end) 解析类型别名后的字符串（字符串/字符/注释原样保留） */
	private String resolveRegion(int start, int end) {
		StringBuilder sb = new StringBuilder();
		int i = start;
		while (i < end) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				int e = skipString(i);
				sb.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '\'') {
				int e = skipChar(i);
				sb.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '/' && i + 1 < end) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					int e = i;
					while (e < end && cleanedSource.charAt(e) != '\n')
						e++;
					sb.append(cleanedSource, i, e);
					i = e;
					continue;
				}
				if (d == '*') {
					int e = i + 2;
					while (e + 1 < end && !(cleanedSource.charAt(e) == '*' && cleanedSource.charAt(e + 1) == '/'))
						e++;
					e = Math.min(e + 2, end);
					sb.append(cleanedSource, i, e);
					i = e;
					continue;
				}
			}
			if (isIdentStart(c)) {
				int e = readIdent(i);
				String w = cleanedSource.substring(i, e);
				String m = TYPE_MAP.get(w);
				if (m != null && !m.equals(w))
					sb.append(m);
				else
					sb.append(w);
				i = e;
				continue;
			}
			sb.append(c);
			i++;
		}
		return sb.toString();
	}

	/**
	 * 复制函数体 [openBrace, closeBrace)，解析别名、识别 ir 调用、检测顶层 return；
	 * 必要时在 '}' 前补 return null（void/基本类型除外）。
	 */
	private void copyBody(int openBrace, int closeBrace, boolean skipImplicitReturn) {
		boolean hasReturn = false;
		int depth = 0;
		localTypes.clear();
		out.append('{');
		int i = openBrace + 1;
		while (i < closeBrace) {
			char c = cleanedSource.charAt(i);
			if (c == '"') {
				int e = skipString(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '\'') {
				int e = skipChar(i);
				out.append(cleanedSource, i, e);
				i = e;
				continue;
			}
			if (c == '/' && i + 1 < closeBrace) {
				char d = cleanedSource.charAt(i + 1);
				if (d == '/') {
					int e = i;
					while (e < closeBrace && cleanedSource.charAt(e) != '\n')
						e++;
					out.append(cleanedSource, i, e);
					i = e;
					continue;
				}
				if (d == '*') {
					int e = i + 2;
					while (e + 1 < closeBrace && !(cleanedSource.charAt(e) == '*' && cleanedSource.charAt(e + 1) == '/'))
						e++;
					e = Math.min(e + 2, closeBrace);
					out.append(cleanedSource, i, e);
					i = e;
					continue;
				}
			}
			if (c == '{') {
				depth++;
				out.append(c);
				i++;
				continue;
			}
			if (c == '}') {
				depth--;
				out.append(c);
				i++;
				continue;
			}
			if (isIdentStart(c)) {
				int e = readIdent(i);
				String w = cleanedSource.substring(i, e);
				// ir 调用：逻辑名; 或 逻辑名.Label;
				int irEnd = tryIrInvocation(i, e, closeBrace);
				if (irEnd >= 0) {
					i = irEnd;
					continue;
				}
				if (w.equals("var")) {
					i = transformVar(e, false);
					continue;
				}
				if (depth == 0 && w.equals("return"))
					hasReturn = true;
				String m = TYPE_MAP.get(w);
				if (m != null && !m.equals(w))
					out.append(m);
				else
					out.append(w);
				i = e;
				continue;
			}
			out.append(c);
			i++;
		}
		if (!skipImplicitReturn && !hasReturn)
			out.append("\nreturn null;\n");
		out.append('}');
	}

	/** 若 [identStart, identEnd) 是 ir 调用，输出对应 IREHelper 调用并返回新索引；否则返回 -1 */
	private int tryIrInvocation(int identStart, int identEnd, int limit) {
		String w = cleanedSource.substring(identStart, identEnd);
		if (!irPaths.containsKey(w))
			return -1;
		int after = skipWs(identEnd);
		if (after < limit && cleanedSource.charAt(after) == ';') {
			out.append("IREHelper.runIr(\"").append(escapeJavaString(irPaths.get(w))).append("\");");
			return after + 1;
		}
		if (after < limit && cleanedSource.charAt(after) == '.') {
			int ls = readIdent(after + 1);
			if (ls > after + 1) {
				int afterLabel = skipWs(ls);
				if (afterLabel < limit && cleanedSource.charAt(afterLabel) == ';') {
					String label = cleanedSource.substring(after + 1, ls);
					out.append("IREHelper.runIrLabel(\"").append(escapeJavaString(irPaths.get(w))).append("\", \"")
							.append(label).append("\");");
					return afterLabel + 1;
				}
			}
		}
		return -1;
	}

	private static boolean isPrimitiveOrVoid(String ret) {
		ret = ret.trim();
		if (ret.isEmpty())
			return false;
		String[] parts = ret.split("\\s+");
		String last = parts[parts.length - 1];
		if (last.equals("void"))
			return true;
		return last.equals("byte") || last.equals("short") || last.equals("int") || last.equals("long")
				|| last.equals("float") || last.equals("double") || last.equals("char") || last.equals("boolean");
	}

	private static String escapeJavaString(String s) {
		if (s == null)
			return "";
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '\\':
				sb.append("\\\\");
				break;
			case '"':
				sb.append("\\\"");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			case '\b':
				sb.append("\\b");
				break;
			case '\f':
				sb.append("\\f");
				break;
			default:
				if (c < 0x20 || c == 0x7F)
					sb.append(String.format("\\u%04x", (int) c));
				else
					sb.append(c);
			}
		}
		return sb.toString();
	}

	private static boolean isIdentStart(char c) {
		return Character.isJavaIdentifierStart(c);
	}

	private static boolean isIdentPart(char c) {
		return Character.isJavaIdentifierPart(c);
	}

	private int readIdent(int i) {
		int n = cleanedSource.length();
		int e = i;
		while (e < n && isIdentPart(cleanedSource.charAt(e)))
			e++;
		return e;
	}

	private int skipWs(int i) {
		int n = cleanedSource.length();
		while (i < n && Character.isWhitespace(cleanedSource.charAt(i)))
			i++;
		return i;
	}
}
