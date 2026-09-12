package sair.aiagent.util;

import java.net.InetAddress;

/**
 * 网络访问闸门 —— <b>安全判定必须留在 Java</b>。
 *
 * <h3>为什么单独提一个类</h3>
 * <p>
 * 「禁止访问内网地址」这条检查原先散落在 {@code AgentActionHandler} 的 web/download 实现里。
 * 三方技能改为「一个技能一个文件夹（md + Java 源码）」后，web/download 这类工具的实现可以被剥离到
 * {@code data/skills/} 下由用户直接编辑的源码里 —— 如果把这条检查一起搬过去，
 * 就等于把安全闸门放进了一个「谁都能改的文本文件」：改一行 {@code return false} 就能让 AI 打内网。
 * 所以：<b>业务逻辑可以剥离，安全判定不行</b>；技能只能调用本类，不能自带一份副本。
 * </p>
 *
 * <h3>覆盖范围</h3>
 * <ul>
 *   <li>字面量：{@code localhost} / {@code 127.0.0.1} / {@code 0.0.0.0}</li>
 *   <li>IPv4 私网：{@code 10.0.0.0/8}、{@code 172.16.0.0/12}、{@code 192.168.0.0/16}</li>
 *   <li>链路本地：{@code 169.254.0.0/16}、IPv6 {@code fe80::/10}</li>
 *   <li>环回与唯一本地 IPv6：{@code ::1}、{@code fc00::/7}</li>
 *   <li><b>域名解析后</b>再判一次（防 {@code http://内网域名/} 绕过）</li>
 * </ul>
 *
 * <p>无法解析的域名按「非内网」放行（原行为），避免 DNS 抖动把正常下载全拦掉。</p>
 */
public final class NetGuard {

    private NetGuard() {}

    /**
     * 该主机名/IP 是否属于内网（应当拒绝访问）。
     *
     * @param host 主机名或 IP（取自 URL 的 host 部分）
     * @return true = 内网/环回/链路本地，应当拒绝；空值也按拒绝处理
     */
    public static boolean isInternalHost(String host) {
        if (host == null || host.isEmpty()) return true;
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("0.0.0.0")) return true;
        if (lower.startsWith("10.") || lower.startsWith("192.168.")) return true;
        if (lower.startsWith("172.")) {
            try {
                int second = Integer.parseInt(lower.substring(4, lower.indexOf('.', 4)));
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {
                // 非标准写法 → 交给下面的解析判定
            }
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (ip == null) return false;
            if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0")
                    || ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
            if (ip.startsWith("172.")) {
                int di = ip.indexOf('.', 4);
                if (di > 0) {
                    int s = Integer.parseInt(ip.substring(4, di));
                    if (s >= 16 && s <= 31) return true;
                }
            }
            if (ip.startsWith("169.254.")) return true;
            if (ip.equals("::1") || ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) return true;
        } catch (Exception ignored) {
            // 解析失败 → 按非内网处理（与原实现一致）
        }
        return false;
    }

    /**
     * 校验一个待访问的 URL 是否允许访问。
     *
     * @return null = 允许；否则返回拒绝原因（可直接回给模型）
     */
    public static String checkUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "地址为空";
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return "仅支持 http/https 地址: " + u;
        }
        try {
            java.net.URI uri = new java.net.URI(u);
            String host = uri.getHost();
            if (isInternalHost(host)) {
                return "禁止访问内网地址 (" + (host == null ? "?" : host) + ")";
            }
        } catch (Exception e) {
            return "URL 格式无效: " + e.getMessage();
        }
        return null;
    }
}
