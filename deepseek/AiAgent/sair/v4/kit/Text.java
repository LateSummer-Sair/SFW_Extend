package sair.v4.kit;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;

/**
 * 字节 → 文本的解码口径（基板唯一一份）。
 *
 * <p>顺序：<b>BOM → 声明字符集（HTTP 头）→ HTML meta charset → 试探解码（严格 UTF-8 → GBK → GB18030）
 * → 强制 UTF-8 兜底</b>。V3 的 {@code util/FileUtils}（本地文件）与 {@code util/WebFetcher}（网页）
 * 各写了一份；V4 收成一份，{@link Fs#readAuto} 与 {@link Http#decode} 都用它。</p>
 *
 * <p>为什么"试探解码"用严格模式而不是数 {@code \uFFFD}：替换字符只在解码器"宽容模式"下才产生，
 * 数它等于先污染再补救；严格模式（{@code CodingErrorAction.REPORT}）能直接判定
 * "这段字节是不是合法的 UTF-8 / GBK"，判据更硬。</p>
 *
 * <p><b>平台默认</b>（{@link #platformDefault()}）：Windows 的控制台/命令行输出是本机 ANSI 代码页
 * （简体中文 Windows 就是 GBK），Linux/macOS 是 UTF-8。所以：
 * <ul>
 *   <li>{@link #decode(byte[], String)} —— "文件/网页"口径，<b>无线索时严格 UTF-8 优先</b>
 *       （契约内容与网络内容绝大多数是 UTF-8），平台默认只作最后的兜底；</li>
 *   <li>{@link #decodeNative(byte[], String)} —— "平台原生内容"口径（{@code cmd.exe} 输出、
 *       本机工具产物），<b>无线索时平台默认优先</b>：Windows 上 {@code echo 中文} 的 GBK 字节
 *       里有一部分恰好也是合法 UTF-8（如"一"=D2 BB），只有让 GBK 先试才不会被解成乱码。</li>
 * </ul>
 * 两条口径的线索链完全一致：<b>有 BOM / 声明字符集 / meta 就按线索走，谁都不例外</b>。</p>
 */
public final class Text {

    private Text() {}

    /** 解码结果。 */
    public static final class Decoded {
        public final String text;
        /** 实际用的字符集名（大写形态，如 {@code UTF-8} / {@code GBK}）。 */
        public final String charset;
        /** 是否走了兜底（所有试探都失败，用首选字符集硬解）。 */
        public final boolean fallback;

        Decoded(String text, String charset, boolean fallback) {
            this.text = text == null ? "" : text;
            this.charset = charset == null ? "" : charset;
            this.fallback = fallback;
        }

        @Override
        public String toString() { return charset + (fallback ? "(fallback)" : ""); }
    }

    /** 媒体头最多扫多少字节找 meta charset / 判二进制。 */
    private static final int HEAD = 4096;

    /**
     * 一段字节该对外声明什么 charset（给文件外链 / 下载用）：探不出来返回空串，
     * 调用方**不要声明** —— 宁可让客户端自己猜，也别谎报 UTF-8
     * （GBK 的 .txt 被声明成 {@code charset=utf-8} 就是乱码）。
     */
    public static String declaredCharset(byte[] head) {
        Decoded d = decode(head, null);
        return d.fallback ? "" : d.charset;
    }

    /** 平台默认字符集：Windows = GBK（本机 ANSI 代码页），其它 = UTF-8。 */
    public static String platformDefault() {
        String os = System.getProperty("os.name", "");
        return os != null && os.toLowerCase().contains("win") ? "GBK" : "UTF-8";
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os != null && os.toLowerCase().contains("win");
    }

    /** 平台原生内容（命令/控制台输出、本机工具产物）：无线索时先按平台默认试。 */
    public static Decoded decodeNative(byte[] bytes, String declared) {
        return decode(bytes, declared, platformDefault());
    }

    /** 文件/网页口径：无线索时严格 UTF-8 优先（等于 {@code decode(bytes, declared, "UTF-8")}）。 */
    public static Decoded decode(byte[] bytes, String declared) {
        return decode(bytes, declared, "UTF-8");
    }

    /**
     * 按上表解码；{@code declared} 可为空（本地文件没有 HTTP 头）。
     * {@code preferred} 是"无线索时的首选字符集"（放在试探链首位、也当硬兜底），其余候选照旧。
     */
    public static Decoded decode(byte[] bytes, String declared, String preferred) {
        String prefer = Str.blank(preferred) ? "UTF-8" : Str.trim(preferred);
        byte[] b = bytes == null ? new byte[0] : bytes;
        if (b.length == 0) return new Decoded("", charsetOf(prefer).name(), false);
        // ① BOM
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            byte[] rest = new byte[b.length - 3];
            System.arraycopy(b, 3, rest, 0, rest.length);
            Decoded d = strict(rest, "UTF-8");
            return d != null ? d : new Decoded(new String(rest, Fs.UTF8), "UTF-8", false);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            Decoded d = strict(b, "UTF-16LE");
            if (d != null) return d;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            Decoded d = strict(b, "UTF-16BE");
            if (d != null) return d;
        }
        // ② 声明字符集（HTTP Content-Type / 调用方给的）
        Decoded d = strict(b, declared);
        if (d != null) return d;
        // ③ HTML meta charset（只在前 4KB 里找，且要求像 HTML 免得误伤普通文本）
        String head = new String(b, 0, Math.min(b.length, HEAD), Charset.forName("ISO-8859-1"));
        if (head.indexOf('<') >= 0) {
            String meta = metaCharset(head);
            if (Str.has(meta)) {
                d = strict(b, meta);
                if (d != null) return d;
            }
        }
        // ④ 试探解码：首选（文件/网页=UTF-8，控制台=平台默认）→ 其余候选（严格模式，重复的跳过）
        for (String cs : chain(prefer)) {
            d = strict(b, cs);
            if (d != null) return d;
        }
        // ⑤ 兜底：按首选字符集硬解（宁可乱码也不丢内容，调用方可以据此提示）
        Charset hard = charsetOf(prefer);
        return new Decoded(new String(stripBom(b), hard), hard.name(), true);
    }

    /** 从 Content-Type 头里取 charset（{@code text/html; charset=GBK}）。 */
    public static String contentTypeCharset(String contentType) {
        if (Str.blank(contentType)) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)charset\\s*=\\s*[\"']?([a-zA-Z0-9._-]+)").matcher(contentType);
        return m.find() ? m.group(1) : "";
    }

    /** 从 HTML 头里取 meta charset（{@code <meta charset=gbk>} 与 {@code <meta http-equiv=... content="text/html; charset=gbk">}）。 */
    public static String metaCharset(String head) {
        if (Str.blank(head)) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9._-]+)").matcher(head);
        return m.find() ? m.group(1) : "";
    }

    /** 该字节串看着像不像文本（NUL 占比与不可打印比例都低）。 */
    public static boolean looksText(byte[] b) {
        if (b == null || b.length == 0) return true;
        int n = Math.min(b.length, HEAD);
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int v = b[i] & 0xFF;
            if (v == 0) return false;
            if (v < 0x09 || (v > 0x0D && v < 0x20)) bad++;
        }
        return bad * 20 <= n;                            // >5% 控制字符 → 当二进制
    }

    /** 该字节串里 {@code \uFFFD} 的比例（兜底解码的可信度提示）。 */
    public static double replacementRatio(String s) {
        if (s == null || s.isEmpty()) return 0.0D;
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\uFFFD') n++;
        return (double) n / (double) s.length();
    }

    /** 候选字符集名清单（给状态输出/文档用）。 */
    public static List<String> candidates() {
        List<String> out = new ArrayList<String>();
        out.add("UTF-8");
        out.add("GBK");
        out.add("GB18030");
        return out;
    }

    /** 试探链：首选在前，其余候选按 {@link #candidates()} 的顺序补上（按解析后的规范名去重）。 */
    public static List<String> chain(String preferred) {
        List<String> out = new ArrayList<String>();
        add(out, Str.blank(preferred) ? "UTF-8" : Str.trim(preferred));
        for (String c : candidates()) add(out, c);
        return out;
    }

    /** 认不出来的字符集名回退到 UTF-8（调用方不该因为写错名字就拿不到内容）。 */
    private static Charset charsetOf(String name) {
        if (!Str.blank(name)) {
            try {
                return Charset.forName(Str.trim(name));
            } catch (Throwable ignored) {
            }
        }
        return Fs.UTF8;
    }

    private static void add(List<String> out, String name) {
        if (Str.blank(name)) return;
        String norm = charsetOf(name).name();
        for (String x : out) if (charsetOf(x).name().equals(norm)) return;
        out.add(Str.trim(name));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 严格解码：能解就用（且不含替换字符），否则 null。
     * 字符集名不认识也返回 null（交给下一个候选）。
     */
    private static Decoded strict(byte[] b, String charset) {
        if (Str.blank(charset)) return null;
        Charset cs;
        try {
            cs = Charset.forName(Str.trim(charset));
        } catch (Throwable t) {
            return null;
        }
        try {
            CharsetDecoder dec = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String s = dec.decode(ByteBuffer.wrap(b)).toString();
            if (s.indexOf('\uFFFD') >= 0) return null;
            return new Decoded(stripBom(s), cs.name(), false);
        } catch (CharacterCodingException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] stripBom(byte[] b) {
        if (b != null && b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            byte[] out = new byte[b.length - 3];
            System.arraycopy(b, 3, out, 0, out.length);
            return out;
        }
        return b;
    }

    static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s == null ? "" : s;
    }
}
