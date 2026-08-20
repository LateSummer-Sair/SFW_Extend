package sair.ire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IRE script translator: translates .ire source into equivalent Java source.
 * <p>
 * Design notes:
 * <ul>
 * <li>ire is a modified Java; expressions/statements are passed through to javac;</li>
 * <li>only ire-specific sugar is recognized: group, fc, import{}, jar{};</li>
 * <li>ire type names (Int/Long/Double/Bool/Char/String/LLong/var) are replaced in type contexts and expression pass-through; basic types map to wrapper classes for generic compatibility;</li>
 * <li>fc is always public static; a function body without return always returns null.</li>
 * <li>translated classes are compiled/loaded/executed inside the current SFW runtime, never in a separate runtime.</li>
 * </ul>
 */
public class IRETranslator {

	/** ire type name -> Java type name (basic types map to wrapper classes for generic compatibility) */
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

	private enum TK {
		IDENT, NUMBER, STRING, CHAR, PUNCT, EOF
	}

	private static class Token {
		final TK type;
		final String value;
		final int line;

		Token(TK type, String value, int line) {
			this.type = type;
			this.value = value;
			this.line = line;
		}

		boolean is(String v) {
			return value.equals(v);
		}
	}

	private final String source;
	private String cleanedSource;
	private final List<Token> tokens = new ArrayList<Token>();
	private int pos = 0;

	/** ir 逻辑名（文件名去掉扩展名）-> 绝对路径，由 run 命令解析后传入 */
	private final Map<String, String> irPaths;

	private final StringBuilder out = new StringBuilder();
	private final List<String> javaImports = new ArrayList<String>();
	private final List<String> ireImports = new ArrayList<String>();
	private final List<String> irImports = new ArrayList<String>();
	private final List<String> jarPaths = new ArrayList<String>();

	private String groupName;
	private String javaSource;
	private String error;

	public IRETranslator(String source) {
		this(source, null);
	}

	public IRETranslator(String source, Map<String, String> irPaths) {
		this.source = source;
		this.irPaths = irPaths == null ? new HashMap<String, String>() : irPaths;
	}

	// ------------------------------------------------------------------
	// public API
	// ------------------------------------------------------------------

	public boolean translate() {
		try {
			extractImportAndJar();
			tokenize(cleanedSource);
			parseTopLevel();
			javaSource = out.toString();
			return true;
		} catch (Exception e) {
			error = e.getMessage();
			return false;
		}
	}

	/**
	 * only extract import{} / jar{} declarations, without tokenizing or parsing
	 * the group body. Used by IRE.run collection phase to avoid double parsing.
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
	// lexical analysis
	// ------------------------------------------------------------------

	private void tokenize(String input) {
		int i = 0;
		int n = input.length();
		int line = 1;
		while (i < n) {
			char c = input.charAt(i);

			if (c == '\n') {
				line++;
				i++;
				continue;
			}
			if (c == '\r') {
				i++;
				continue;
			}
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}

			if (c == '/' && i + 1 < n && input.charAt(i + 1) == '/') {
				i += 2;
				while (i < n && input.charAt(i) != '\n')
					i++;
				continue;
			}

			if (c == '/' && i + 1 < n && input.charAt(i + 1) == '*') {
				i += 2;
				while (i + 1 < n && !(input.charAt(i) == '*' && input.charAt(i + 1) == '/')) {
					if (input.charAt(i) == '\n')
						line++;
					i++;
				}
				i += 2;
				continue;
			}

			if (c == '"') {
				int start = i;
				i++;
				while (i < n) {
					char d = input.charAt(i);
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
				tokens.add(new Token(TK.STRING, input.substring(start, i), line));
				continue;
			}

			if (c == '\'') {
				int start = i;
				i++;
				while (i < n) {
					char d = input.charAt(i);
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
				tokens.add(new Token(TK.CHAR, input.substring(start, i), line));
				continue;
			}

			if (Character.isJavaIdentifierStart(c)) {
				int start = i;
				i++;
				while (i < n && Character.isJavaIdentifierPart(input.charAt(i)))
					i++;
				tokens.add(new Token(TK.IDENT, input.substring(start, i), line));
				continue;
			}

			if (Character.isDigit(c)) {
				int start = i;
				i++;
				while (i < n && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '.'
						|| input.charAt(i) == '_'))
					i++;
				tokens.add(new Token(TK.NUMBER, input.substring(start, i), line));
				continue;
			}

			tokens.add(new Token(TK.PUNCT, String.valueOf(c), line));
			i++;
		}
		tokens.add(new Token(TK.EOF, "", line));
	}

	// ------------------------------------------------------------------
	// parser utilities
	// ------------------------------------------------------------------

	private Token peek() {
		return tokens.get(pos);
	}

	private Token peek(int ahead) {
		int idx = pos + ahead;
		if (idx >= tokens.size())
			idx = tokens.size() - 1;
		return tokens.get(idx);
	}

	private Token next() {
		Token t = tokens.get(pos);
		if (pos < tokens.size() - 1)
			pos++;
		return t;
	}

	private boolean check(String v) {
		return peek().is(v);
	}

	private boolean checkEof() {
		return peek().type == TK.EOF;
	}

	private Token expect(String v) {
		Token t = next();
		if (!t.is(v))
			throw new IllegalStateException("syntax error at line " + t.line + ": expected '" + v + "', got '" + t.value + "'");
		return t;
	}

	private void emit(String s) {
		out.append(s);
	}

	private void emitLine(String s) {
		out.append(s).append('\n');
	}

	private static boolean isTypeName(String v) {
		return TYPE_MAP.containsKey(v);
	}

	private static String toJavaType(String v) {
		return TYPE_MAP.get(v);
	}

	/**
	 * parse a type in type position (return type / parameter / field / local
	 * variable). Supports ire type names, qualified Java class names
	 * ({@code java.util.List}), generic arguments ({@code List<String>}) and
	 * array dimensions ({@code Int[]}, {@code String[][]}).
	 */
	private String parseType() {
		Token first = next();
		if (first.type != TK.IDENT)
			throw new IllegalStateException(
					"syntax error at line " + first.line + ": expected type name, got '" + first.value + "'");
		StringBuilder sb = new StringBuilder();
		sb.append(isTypeName(first.value) ? toJavaType(first.value) : first.value);

		// qualified name: a.b.C
		while (check(".") && peek(1).type == TK.IDENT) {
			next();
			sb.append('.').append(next().value);
		}

		// generic arguments: List<String>
		while (check("<")) {
			sb.append(parseGenericArguments());
		}

		// array dimensions: Int[]
		while (check("[") && peek(1).is("]")) {
			next();
			next();
			sb.append("[]");
		}
		return sb.toString();
	}

	/** parse balanced generic type arguments, mapping ire type names inside */
	private String parseGenericArguments() {
		expect("<");
		StringBuilder sb = new StringBuilder("<");
		int depth = 1;
		while (!checkEof() && depth > 0) {
			if (check("<"))
				depth++;
			else if (check(">"))
				depth--;
			sb.append(transpileOne());
		}
		if (depth != 0)
			throw new IllegalStateException("syntax error: unclosed generic type argument");
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// top-level parsing
	// ------------------------------------------------------------------

	private void parseTopLevel() {
		emitLine("package " + SCRIPT_PACKAGE + ";");
		emitLine("");
		for (String imp : javaImports)
			emitLine("import " + imp + ";");
		if (!javaImports.isEmpty())
			emitLine("");

		while (!checkEof()) {
			if (check("group")) {
				parseGroup();
			} else {
				throw new IllegalStateException("syntax error at line " + peek().line + ": top level only allows group, got '" + peek().value + "'");
			}
		}
	}

	private void parseGroup() {
		expect("group");
		groupName = next().value;
		expect("{");
		emitLine("public class " + groupName + " {");
		emitLine("");
		parseGroupBody();
		emitLine("}");
	}

	private void parseGroupBody() {
		while (!check("}") && !checkEof()) {
			if (check("fc")) {
				parseFunction();
			} else {
				parseField();
			}
		}
		expect("}");
	}

	// ------------------------------------------------------------------
	// fields and functions
	// ------------------------------------------------------------------

	private void parseField() {
		String javaType = parseType();
		String name = next().value;
		StringBuilder decl = new StringBuilder();
		decl.append("public static ").append(javaType).append(' ').append(name);
		while (!check(";") && !checkEof()) {
			decl.append(' ').append(transpileOne());
		}
		expect(";");
		emitLine(decl.toString() + ";");
		emitLine("");
	}

	private void parseFunction() {
		expect("fc");
		String retJavaType = "Object";
		// return type: if an identifier follows 'fc' and the next token is not '(',
		// treat it as an explicit return type (ire type or qualified/generic Java type)
		if (peek().type == TK.IDENT && !peek(1).is("(")) {
			retJavaType = parseType();
		}

		String funcName = next().value;
		expect("(");
		List<String[]> params = parseParams();
		expect(")");

		emit("public static " + retJavaType + " " + funcName + "(");
		for (int i = 0; i < params.size(); i++) {
			String[] p = params.get(i);
			if (i > 0)
				emit(", ");
			emit(p[0] + " " + p[1]);
		}
		emit(") ");

		// 函数体：内联解析，确保默认 return 在 '}' 之前输出
		expect("{");
		emitLine("{");
		boolean hasReturn = false;
		while (!check("}") && !checkEof()) {
			if (parseStatement())
				hasReturn = true;
		}
		if (!hasReturn) {
			emitLine("return null;");
		}
		expect("}");
		emitLine("}");
		emitLine("");
	}

	private List<String[]> parseParams() {
		List<String[]> params = new ArrayList<String[]>();
		while (!check(")") && !checkEof()) {
			if (check(",")) {
				next();
				continue;
			}
			String javaType = parseType();
			String name = next().value;
			params.add(new String[] { javaType, name });
		}
		return params;
	}

	// ------------------------------------------------------------------
	// code blocks and statements
	// ------------------------------------------------------------------

	/** parse a block; returns whether it contains a top-level bare return */
	private boolean parseBlock() {
		expect("{");
		emitLine("{");
		boolean hasReturn = false;
		while (!check("}") && !checkEof()) {
			if (parseStatement())
				hasReturn = true;
		}
		expect("}");
		emitLine("}");
		return hasReturn;
	}

	/** parse a single statement; returns whether it is a top-level bare return */
	private boolean parseStatement() {
		if (checkEof())
			return false;
		String v = peek().value;

		if (v.equals("if")) {
			parseIf();
		} else if (v.equals("for")) {
			parseFor();
		} else if (v.equals("while")) {
			parseWhile();
		} else if (v.equals("do")) {
			parseDoWhile();
		} else if (v.equals("switch")) {
			parseSwitch();
		} else if (v.equals("return")) {
			parseReturn();
			return true;
		} else if (v.equals("try")) {
			parseTry();
		} else if (v.equals("throw")) {
			parseExpressionStatement();
		} else if (v.equals("break") || v.equals("continue")) {
			parseBreakContinue();
		} else if (v.equals("{")) {
			parseBlock();
		} else if (v.equals(";")) {
			next();
			emitLine(";");
		} else if (isDeclarationStart()) {
			parseLocalDeclaration();
		} else if (isIrInvocation()) {
			parseIrInvocation();
		} else {
			parseExpressionStatement();
		}
		return false;
	}

	private boolean isDeclarationStart() {
		Token t0 = peek(0);
		if (t0.type != TK.IDENT)
			return false;
		if (t0.is("new") || t0.is("this") || t0.is("super"))
			return false;

		int idx = 1;
		// qualified name: a.b.C
		while (peek(idx).is(".") && peek(idx + 1).type == TK.IDENT)
			idx += 2;
		// generic arguments: List<String>
		if (peek(idx).is("<")) {
			int depth = 0;
			int j = idx;
			while (true) {
				Token tj = peek(j);
				if (tj.type == TK.EOF)
					return false;
				if (tj.is("<"))
					depth++;
				else if (tj.is(">"))
					depth--;
				if (depth == 0)
					break;
				j++;
			}
			idx = j + 1;
		}
		// array dimensions: Int[]
		while (peek(idx).is("[") && peek(idx + 1).is("]"))
			idx += 2;

		Token name = peek(idx);
		if (name.type != TK.IDENT)
			return false;
		return !peek(idx + 1).is("(");
	}

	private void parseLocalDeclaration() {
		String javaType = parseType();
		String name = next().value;
		StringBuilder decl = new StringBuilder();
		decl.append(javaType).append(' ').append(name);
		while (!check(";") && !checkEof()) {
			decl.append(' ').append(transpileOne());
		}
		expect(";");
		emitLine(decl.toString() + ";");
	}

	/** 判断当前位置是否为 ir 调用：逻辑名; 或 逻辑名.Label; */
	private boolean isIrInvocation() {
		Token t0 = peek(0);
		if (t0.type != TK.IDENT || !irPaths.containsKey(t0.value))
			return false;
		Token t1 = peek(1);
		if (t1.is(";"))
			return true;
		if (t1.is(".")) {
			Token t2 = peek(2);
			Token t3 = peek(3);
			return t2.type == TK.IDENT && t3.is(";");
		}
		return false;
	}

	/** 生成 ir 调用：IREHelper.runIr(路径) 或 IREHelper.runIrLabel(路径, 标签) */
	private void parseIrInvocation() {
		String logicName = next().value;
		String path = escapeJavaString(irPaths.get(logicName));
		if (check(".")) {
			next();
			String label = next().value;
			emit("IREHelper.runIrLabel(\"" + path + "\", \"" + label + "\")");
		} else {
			emit("IREHelper.runIr(\"" + path + "\")");
		}
		expect(";");
		emitLine(";");
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

	private void parseIf() {
		expect("if");
		emit("if ");
		transpileParenthesized();
		emitLine(" ");
		parseBranch();
		if (check("else")) {
			next();
			emit(" else ");
			if (check("if")) {
				parseIf();
			} else {
				parseBranch();
			}
		}
	}

	private void parseBranch() {
		if (check("{")) {
			parseBlock();
		} else {
			parseStatement();
		}
	}

	private void parseFor() {
		expect("for");
		emit("for ");
		transpileParenthesized();
		emitLine(" ");
		parseBranch();
	}

	private void parseWhile() {
		expect("while");
		emit("while ");
		transpileParenthesized();
		emitLine(" ");
		parseBranch();
	}

	private void parseDoWhile() {
		expect("do");
		emit("do ");
		parseBranch();
		if (check("while")) {
			next();
			emit(" while ");
			transpileParenthesized();
			emitLine(";");
		}
	}

	private void parseSwitch() {
		expect("switch");
		emit("switch ");
		transpileParenthesized();
		emitLine(" ");
		expect("{");
		emitLine("{");
		while (!check("}") && !checkEof()) {
			if (check("case") || check("default")) {
				while (!check(":") && !checkEof()) {
					emit(transpileOne() + " ");
				}
				expect(":");
				emitLine(":");
			} else {
				parseStatement();
			}
		}
		expect("}");
		emitLine("}");
	}

	private void parseReturn() {
		expect("return");
		emit("return");
		while (!check(";") && !checkEof()) {
			emit(" " + transpileOne());
		}
		expect(";");
		emitLine(";");
	}

	private void parseTry() {
		expect("try");
		emit("try ");
		parseBlock();
		while (check("catch")) {
			next();
			emit(" catch ");
			transpileParenthesized();
			emitLine(" ");
			parseBlock();
		}
		if (check("finally")) {
			next();
			emit(" finally ");
			parseBlock();
		}
	}

	private void parseBreakContinue() {
		Token kw = next();
		emit(kw.value);
		if (!check(";")) {
			emit(" " + transpileOne());
		}
		expect(";");
		emitLine(";");
	}

	private void parseExpressionStatement() {
		while (!check(";") && !checkEof()) {
			emit(transpileOne() + " ");
		}
		expect(";");
		emitLine(";");
	}

	// ------------------------------------------------------------------
	// expression pass-through
	// ------------------------------------------------------------------

	/**
	 * pass through a parenthesized expression (if/while/switch/for/catch head),
	 * replacing type names inside while keeping parentheses balanced.
	 */
	private void transpileParenthesized() {
		expect("(");
		emit("(");
		int depth = 1;
		while (!checkEof() && depth > 0) {
			if (check("("))
				depth++;
			else if (check(")"))
				depth--;
			emit(transpileOne());
		}
	}

	/**
	 * pass through a single token; replace ire type names with Java type names,
	 * others are emitted unchanged. Cast (Int)x becomes (Integer)x naturally.
	 */
	private String transpileOne() {
		Token t = peek();
		if (t.type == TK.IDENT && isTypeName(t.value)) {
			next();
			String jt = toJavaType(t.value);
			// 类型名后紧跟标识符时补空格，如 Int i -> Integer i
			if (peek().type == TK.IDENT)
				return jt + " ";
			return jt;
		}
		next();
		return t.value;
	}
}
