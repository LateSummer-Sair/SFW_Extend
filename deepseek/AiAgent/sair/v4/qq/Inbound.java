package sair.v4.qq;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 入站媒体的短期登记簿：把「最近几条入站消息里的图片」记下来，
 * 让"只拿到 NapCat fileid"的技能能回退到<b>事件里本来就带的图片 URL</b>。
 *
 * <h3>为什么需要它</h3>
 * <p>NapCat 与插件<b>不同机</b>时，{@code get_image} 只会给出 <b>NapCat 那台机器</b>上的本地路径，
 * 插件机读不到；而事件里的图片段本来就带一个可直连的 {@code url}（腾讯 CDN / NapCat 的 http 服务）。
 * 这条 URL 只能从"入站事件"里拿到，技能手上只有模型给的一个裸串 —— 所以基板在收到消息时先记一份。</p>
 *
 * <p>登记簿是<b>进程内短期缓存</b>（默认 30 分钟、最多 256 条，只存 URL 与 file 值，不落盘、不入库）：
 * 它是"这次会话里刚发过的那张图"的定位线索，不是历史归档。</p>
 */
public final class Inbound {

    private Inbound() {}

    /** 条目有效期（毫秒）。 */
    private static final long TTL_MS = 30L * 60L * 1000L;
    /** 最多记多少条（按写入顺序淘汰）。 */
    private static final int MAX_ENTRIES = 256;
    /** 最多回溯几条"最近的图片"。 */
    private static final int MAX_RECENT = 16;

    private static final class Entry {
        final String file;
        final String url;
        final long ts;
        /** 文件段才有的显示名（图片为空）。 */
        final String name;
        /** 文件段才有的字节数（图片为 0）。 */
        final long size;

        Entry(String file, String url, long ts) {
            this(file, url, ts, "", 0L);
        }

        Entry(String file, String url, long ts, String name, long size) {
            this.file = Str.nz(file);
            this.url = Str.nz(url);
            this.ts = ts;
            this.name = Str.nz(name);
            this.size = size;
        }

        boolean live(long now) { return now - ts <= TTL_MS; }
    }

    private static final Object LOCK = new Object();
    /** file 值 → 条目（含小写与 basename 两个键）。 */
    private static final Map<String, Entry> BY_FILE = new LinkedHashMap<String, Entry>();
    /** 最近记下的条目（新的在最后）。 */
    private static final List<Entry> RECENT = new ArrayList<Entry>();
    /** 最近记下的<b>文件</b>段（新的在最后）：图片那条路不用它。 */
    private static final List<Entry> FILES = new ArrayList<Entry>();

    /** 记事：把一条入站消息里的图片段记下来（file 值 → url）。 */
    public static void remember(Ev e) {
        if (e == null) return;
        long now = System.currentTimeMillis();
        List<String> files = new ArrayList<String>();
        List<String> urls = new ArrayList<String>();
        try {
            for (JsonObject seg : e.segments()) {
                String type = Str.lower(J.s(seg, "type", ""));
                JsonObject d = J.sub(seg, "data");
                if (d == null) continue;
                if ("image".equals(type)) {
                    String f = Str.trim(J.s(d, "file", ""));
                    String u = Str.trim(J.s(d, "url", ""));
                    files.add(f);
                    urls.add(u);
                    if (Str.blank(u) && Str.blank(f)) continue;
                    Entry en = new Entry(f, u, now);
                    synchronized (LOCK) {
                        put(f, en);
                        put(basename(f), en);
                        RECENT.add(en);
                        while (RECENT.size() > MAX_RECENT) RECENT.remove(0);
                        trim(now);
                    }
                } else if ("file".equals(type)) {
                    // 文件段：记 file 值与显示名。它一般没有直链 url（要拿 get_file 换），
                    // 所以这里只登记"她刚收到过这个文件"这条线索，取内容由 file op=fetch 去做。
                    String f = Str.trim(J.s(d, "file", ""));
                    String nm = Str.trim(J.s(d, "name", ""));
                    long sz = J.l(d, "file_size", 0L);
                    String u = Str.trim(J.s(d, "url", ""));
                    if (Str.blank(f) && Str.blank(nm)) continue;
                    Entry en = new Entry(f, u, now, nm, sz);
                    synchronized (LOCK) {
                        put(f, en);
                        put(basename(f), en);
                        put(nm, en);
                        FILES.add(en);
                        while (FILES.size() > MAX_RECENT) FILES.remove(0);
                        trim(now);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 事件里那张图的直连地址。
     *
     * @param file NapCat 的 file 值（事件段里的 {@code data.file}，模型常原样抄回来）
     * @return 可直连的 {@code http(s)} / {@code data:} 地址；查不到返回空串
     */
    public static String urlOfFile(String file) {
        String f = Str.trim(file);
        if (f.isEmpty()) return "";
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            String u = look(f, now);
            if (Str.has(u)) return u;
            u = look(Str.lower(f), now);
            if (Str.has(u)) return u;
            String base = basename(f);
            if (Str.has(base)) {
                u = look(base, now);
                if (Str.has(u)) return u;
                u = look(Str.lower(base), now);
                if (Str.has(u)) return u;
            }
        }
        return "";
    }

    /** 最近几条入站图片的可直连地址（新的在前）。 */
    public static List<String> recentImageUrls(int limit) {
        int n = limit > 0 ? limit : 3;
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<String>();
        synchronized (LOCK) {
            for (int i = RECENT.size() - 1; i >= 0 && out.size() < n; i--) {
                Entry en = RECENT.get(i);
                if (en == null || !en.live(now)) continue;
                if (!usable(en.url)) continue;
                out.add(en.url);
            }
        }
        return out;
    }

    /**
     * 最近几条入站<b>文件</b>（新的在前）：{@code [{name, file, size, ts}]}。
     *
     * <p>给技能 {@code file op=fetch} 用：用户刚发的文件在这里能找到它的 {@code file} 值与显示名。</p>
     */
    public static List<JsonObject> recentFiles(int limit) {
        int n = limit > 0 ? limit : 3;
        long now = System.currentTimeMillis();
        List<JsonObject> out = new ArrayList<JsonObject>();
        synchronized (LOCK) {
            for (int i = FILES.size() - 1; i >= 0 && out.size() < n; i--) {
                Entry en = FILES.get(i);
                if (en == null || !en.live(now)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("name", en.name);
                o.addProperty("file", en.file);
                o.addProperty("size", en.size);
                o.addProperty("ts", en.ts);
                out.add(o);
            }
        }
        return out;
    }

    /** 当前登记条数（诊断/探针用）。 */
    public static int size() {
        synchronized (LOCK) {
            return RECENT.size();
        }
    }

    /** 清空（探针与重启用；运行期不需要）。 */
    public static void clear() {
        synchronized (LOCK) {
            BY_FILE.clear();
            RECENT.clear();
            FILES.clear();
        }
    }

    // ---------------------------------------------------------------- 内部

    private static void put(String key, Entry en) {
        String k = Str.trim(key);
        if (k.isEmpty()) return;
        BY_FILE.put(k, en);
    }

    private static String look(String key, long now) {
        Entry en = BY_FILE.get(key);
        if (en == null) return "";
        if (!en.live(now)) {
            BY_FILE.remove(key);
            return "";
        }
        return usable(en.url) ? en.url : "";
    }

    private static boolean usable(String url) {
        String u = Str.lower(Str.trim(url));
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("data:image/");
    }

    private static void trim(long now) {
        while (BY_FILE.size() > MAX_ENTRIES) {
            String oldest = null;
            long ts = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> e : BY_FILE.entrySet()) {
                if (e.getValue() != null && e.getValue().ts < ts) {
                    ts = e.getValue().ts;
                    oldest = e.getKey();
                }
            }
            if (oldest == null) break;
            BY_FILE.remove(oldest);
        }
        for (int i = RECENT.size() - 1; i >= 0; i--) {
            Entry en = RECENT.get(i);
            if (en == null || !en.live(now)) RECENT.remove(i);
        }
    }

    /** 取路径最后一段（NapCat 的 file 值有时是完整路径，有时只是文件名）。 */
    private static String basename(String p) {
        String s = Str.trim(p).replace('\\', '/');
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** 只读快照（诊断用）。 */
    public static List<String> files() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<String>(BY_FILE.keySet()));
        }
    }
}
