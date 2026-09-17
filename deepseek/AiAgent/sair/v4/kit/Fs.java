package sair.v4.kit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 文件工具（读/写/遍历/摘要）。全部 UTF-8，写入不带 BOM。 */
public final class Fs {

    private Fs() {}

    public static final Charset UTF8 = Charset.forName("UTF-8");

    public static String read(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            byte[] b = readBytes(f);
            String s = new String(b, UTF8);
            if (s.startsWith("\uFEFF")) s = s.substring(1);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    public static String read(File f, String def) {
        String s = read(f);
        return s == null ? def : s;
    }

    /**
     * 读文本，<b>编码自动探测</b>：BOM → 严格 UTF-8 → GBK → GB18030 → 强制 UTF-8 兜底。
     *
     * <p>与 {@link #read} 的分工：{@link #read} 是"契约就是 UTF-8"的读取（技能 md、prompt.md、配置、
     * 六库导出的 JSONL），写错编码就该暴露成乱码而不是被猜；{@link #readAuto} 是"内容来自外部世界"的读取
     * （用户丢进数据目录的 GBK/ANSI 文本），探测口径在 {@link Text}。</p>
     */
    public static String readAuto(File f) {
        Text.Decoded d = readDecoded(f);
        return d == null ? null : d.text;
    }

    /** 同 {@link #readAuto}，并给出实际用的字符集。读不到返回 null。 */
    public static Text.Decoded readDecoded(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return Text.decode(readBytes(f), null);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            close(in);
        }
    }

    public static boolean write(File f, String text) {
        if (f == null) return false;
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            OutputStream os = new FileOutputStream(f);
            try {
                os.write(text == null ? new byte[0] : text.getBytes(UTF8));
                os.flush();
            } finally {
                close(os);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean writeBytes(File f, byte[] data) {
        if (f == null) return false;
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            OutputStream os = new FileOutputStream(f);
            try {
                os.write(data == null ? new byte[0] : data);
                os.flush();
            } finally {
                close(os);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean append(File f, String text) {
        if (f == null || text == null) return false;
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            OutputStream os = new FileOutputStream(f, true);
            try {
                os.write(text.getBytes(UTF8));
                os.flush();
            } finally {
                close(os);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean mkdirs(File d) { return d != null && (d.isDirectory() || d.mkdirs()); }

    /** 目录下直接子文件（按名字排序，可过滤后缀，忽略大小写）。 */
    public static List<File> files(File dir, String suffix) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.isFile()) continue;
            if (suffix != null && !f.getName().toLowerCase().endsWith(suffix.toLowerCase())) continue;
            out.add(f);
        }
        Collections.sort(out, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        return out;
    }

    /** 目录下直接子目录（按名字排序）。 */
    public static List<File> dirs(File dir) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) if (f.isDirectory()) out.add(f);
        Collections.sort(out, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        return out;
    }

    /** 递归收集文件（限深，suffix 可为 null）。 */
    public static void walk(File dir, String suffix, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory() || depth < 0) return;
        for (File f : files(dir, suffix)) out.add(f);
        for (File d : dirs(dir)) walk(d, suffix, depth - 1, out);
    }

    public static List<File> walk(File dir, String suffix, int depth) {
        List<File> out = new ArrayList<File>();
        walk(dir, suffix, depth, out);
        return out;
    }

    public static boolean deleteRec(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRec(c);
        }
        return f.delete();
    }

    public static long size(File f) { return f == null || !f.exists() ? 0L : f.length(); }

    public static String baseName(File f) {
        if (f == null) return "";
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    public static String ext(File f) {
        if (f == null) return "";
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1).toLowerCase() : "";
    }

    public static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data == null ? new byte[0] : data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String md5(File f) {
        try {
            return md5(readBytes(f));
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean copy(File src, File dst) {
        if (src == null || dst == null || !src.isFile()) return false;
        try {
            return writeBytes(dst, readBytes(src));
        } catch (Exception e) {
            return false;
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
