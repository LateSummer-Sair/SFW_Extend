package sair.v4.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LinkerLoader —— 扫描 {@code data/linker/} 下的 JSON，每个文件建一个出站客户端。
 *
 * <h3>目录约定</h3>
 * <pre>
 *   data/AiAgent-V4/linker/
 *     DC小枫.json     ← 文件名 = 客户端名称
 *     Yunzai.json
 * </pre>
 *
 * <h3>单个 JSON 的字段</h3>
 * <pre>
 * {
 *   "url":         "ws://127.0.0.1:8083",   // 必填：对端 OneBot 服务端地址
 *   "token":       "xxx",                    // 可选：Authorization: Bearer
 *   "prefix":      "dc-",                    // 可选：消息前缀（命中就路由到本客户端）
 *   "heartbeatMs": 30000,                    // 可选：心跳间隔（默认 30000）
 *   "enabled":     true                      // 可选：false 时不加载
 * }
 * </pre>
 *
 * <p>文件名（去掉 .json）就是客户端名。改一个文件不影响其他；删掉文件下次启动就不加载。
 * 不合规的 JSON（缺 url / 语法错 / url 不是 ws:// 开头）<b>会被跳过并打一行警告</b>，
 * 不影响其他客户端。</p>
 */
public final class LinkerLoader {

    private LinkerLoader() {}

    /** 一个已加载的客户端定义。 */
    public static final class Spec {
        public final String name;
        public final String url;
        public final String token;
        public final String prefix;
        public final int heartbeatMs;
        public Spec(String name, String url, String token, String prefix, int heartbeatMs) {
            this.name = name;
            this.url = url;
            this.token = token;
            this.prefix = prefix == null ? "" : prefix;
            this.heartbeatMs = heartbeatMs;
        }
    }

    /**
     * 扫描目录。
     *
     * @param dir  linker 目录（不存在就返回空列表，不报错）
     * @param out  控制台（可空）
     * @return 按文件名排序的合法配置
     */
    public static List<Spec> load(File dir, Out out) {
        List<Spec> list = new ArrayList<Spec>();
        if (dir == null || !dir.isDirectory()) {
            return list;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return list;

        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String fn = f.getName();
            if (!fn.toLowerCase().endsWith(".json")) continue;
            String name = fn.substring(0, fn.length() - 5).trim();
            if (name.isEmpty()) continue;

            String text;
            try {
                text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                warn(out, name, "读不出来：" + t.getClass().getSimpleName());
                continue;
            }
            JsonObject o = J.obj(text);
            if (o == null) { warn(out, name, "不是合法 JSON"); continue; }

            String url = J.s(o, "url", "").trim();
            if (url.isEmpty()) { warn(out, name, "缺 url 字段"); continue; }
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                warn(out, name, "url 必须以 ws:// 或 wss:// 开头（现在是 " + url + "）");
                continue;
            }
            boolean enabled = true;
            JsonElement en = o.get("enabled");
            if (en != null && en.isJsonPrimitive()) {
                try { enabled = en.getAsBoolean(); } catch (Throwable ignored) {}
            }
            if (!enabled) { dim(out, name, "enabled=false，跳过"); continue; }

            String token = J.s(o, "token", "");
            String prefix = J.s(o, "prefix", "");
            int hb = (int) J.l(o, "heartbeatMs", 30000L);
            if (hb < 3000) hb = 3000;
            if (hb > 600000) hb = 600000;

            list.add(new Spec(name, url, token, prefix, hb));
            dim(out, name, "已加载 -> " + url + (prefix.isEmpty() ? "（无前缀，不参与路由）" : "（前缀 \"" + prefix + "\"）"));
        }
        return list;
    }

    /**
     * linker 目录：架构数据根下的 {@code AiAgent-V4/linker}。
     *
     * <p>{@code base} 传框架根（即 {@code user.dir}）；真实位置是
     * {@code <框架根>/data/AiAgent-V4/linker}。</p>
     */
    public static File dirOf(File base) {
        return new File(new File(new File(base, "data"), "AiAgent-V4"), "linker");
    }

    private static void warn(Out out, String name, String msg) {
        if (out == null) return;
        try { out.print("[Linker] " + name + ".json 跳过：" + msg + "\n", Out.Tone.WARN); } catch (Throwable ignored) {}
    }

    private static void dim(Out out, String name, String msg) {
        if (out == null) return;
        try { out.print("[Linker] " + name + " " + msg + "\n", Out.Tone.DIM); } catch (Throwable ignored) {}
    }
}
