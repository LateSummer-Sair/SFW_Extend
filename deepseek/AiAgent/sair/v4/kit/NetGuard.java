package sair.v4.kit;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 网络访问闸门（SSRF）—— <b>安全判定必须留在基板</b>。
 *
 * <h3>为什么单独一个类</h3>
 * <p>「禁止访问内网地址」这条检查一旦写进 {@code data/skills/} 下由用户随手可改的源码里，
 * 就等于把安全闸门放进了一个"谁都能改的文本文件"：改一行 {@code return false} 就能让 AI 打内网。
 * 所以：<b>业务逻辑可以剥离，安全判定不行</b>。技能只能调用本类与 {@link Http} 的带闸门入口，
 * 不能自带一份副本；本类也<b>不产出任何面向用户/模型的文案</b>（只给结构性机器码，
 * 拒绝话术由各技能的 {@code prompt.md} 负责）。</p>
 *
 * <h3>覆盖范围</h3>
 * <ul>
 *   <li>名字：{@code localhost} / {@code *.localhost} / {@code ip6-localhost} / {@code localhost.localdomain}，
 *       以及云元数据域名（{@code metadata.google.internal} / {@code metadata.goog} / {@code instance-data}）</li>
 *   <li>IPv4 私网：{@code 10/8}、{@code 172.16/12}、{@code 192.168/16}；环回 {@code 127/8}；
 *       链路本地 {@code 169.254/16}（含云元数据 {@code 169.254.169.254}）；运营商 NAT {@code 100.64/10}；
 *       保留段 {@code 0/8} / {@code 192.0.0/24} / {@code 192.0.2/24} / {@code 198.18/15} /
 *       {@code 198.51.100/24} / {@code 203.0.113/24} / {@code 240/4}；组播 {@code 224/4} 与广播</li>
 *   <li>IPv6：{@code ::1}、{@code fe80::/10}、{@code fc00::/7}（唯一本地）、{@code ::}（未指定）、
 *       {@code ff00::/8}（组播），以及 IPv4 映射写法 {@code ::ffff:127.0.0.1}</li>
 *   <li><b>IP 变形</b>：十进制整数（{@code 2130706433}）、十六进制（{@code 0x7f000001} / {@code 0x7f.0.0.1}）、
 *       八进制（{@code 0177.0.0.1}）、少段写法（{@code 127.1} / {@code 127.0.1}）、末尾点（{@code localhost.}）、
 *       IPv6 方括号（{@code [::1]}）</li>
 *   <li><b>域名解析后再判一次</b>（防 {@code http://内网域名/} 绕过）：解析出的<b>任一</b>地址是内网就拒</li>
 * </ul>
 *
 * <p>解析失败的域名按"非内网"放行（与原 V3 行为一致）：DNS 抖动不该把正常抓取全拦掉，
 * 真正的连接失败会由 HTTP 层如实报错。</p>
 *
 * <h3>重定向</h3>
 * <p>本类只判单个 URL。要防"302 跳到内网"，必须<b>逐跳</b>调用本类 —— {@link Http} 的
 * {@code getGuarded} / {@code download} 关掉了 JDK 的自动跟随，自己走跳转并在每一跳前判一次。</p>
 *
 * <h3>离线探针豁免</h3>
 * <p>启动参数 {@code -Dv4.netguard.devAllow=127.0.0.1,localhost} 可放行指定主机名/IP（只认字面量，
 * 不认通配），供离线探针在本机起桩服务器验证"逐跳复检/编码探测"这类需要真实 HTTP 往返的行为。
 * 该表<b>在进程内第一次判定时读取一次并冻结</b>：运行期（技能里）改系统属性不再生效。</p>
 */
public final class NetGuard {

    private NetGuard() {}

    /** 判定结果：结构性机器码，不含面向用户/模型的文案。 */
    public static final class Verdict {

        /** 是否拒绝。 */
        public final boolean blocked;
        /** {@code ok} / {@code empty} / {@code bad_url} / {@code scheme} / {@code internal}。 */
        public final String code;
        /** {@code code=internal} 时的细分：{@code loopback}/{@code private}/{@code link_local}/
         *  {@code metadata}/{@code unique_local}/{@code cgnat}/{@code reserved}/{@code multicast}/
         *  {@code unspecified}/{@code name}；其余情况为空串。 */
        public final String reason;
        /** URL 里的主机名（原样，去掉方括号与末尾点）。 */
        public final String host;
        /** 判定命中的 IP（解析出来的，可能为空串）。 */
        public final String ip;

        Verdict(boolean blocked, String code, String reason, String host, String ip) {
            this.blocked = blocked;
            this.code = code == null ? "" : code;
            this.reason = reason == null ? "" : reason;
            this.host = host == null ? "" : host;
            this.ip = ip == null ? "" : ip;
        }

        public boolean ok() { return !blocked; }

        @Override
        public String toString() {
            return blocked ? ("blocked " + code + (reason.isEmpty() ? "" : "/" + reason) + " host=" + host + " ip=" + ip)
                    : "ok host=" + host;
        }
    }

    /** 只允许这两种协议出去。 */
    private static final String[] SCHEMES = {"http", "https"};

    /** 云元数据域名（按名字先拦一道：DNS 挂了也不能放行）。 */
    private static final String[] METADATA_NAMES = {
            "metadata.google.internal", "metadata.goog", "metadata", "instance-data",
            "metadata.azure.com", "metadata.aws.internal"
    };

    /** 环回/本机的别名。 */
    private static final String[] LOCAL_NAMES = {
            "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback", "local"
    };

    /** 离线探针豁免表（启动期读一次并冻结）。 */
    private static volatile Set<String> devAllow = null;
    private static final Object DEV_LOCK = new Object();

    // ================================================================ 判定

    /**
     * 判定一个待访问 URL。
     *
     * @return {@link Verdict}；{@code blocked=true} 表示必须拒绝（调用方用自己的 prompt.md 文案拒绝）
     */
    public static Verdict check(String url) {
        String raw = Str.trim(url);
        if (raw.isEmpty()) return new Verdict(true, "empty", "", "", "");
        URI uri;
        try {
            uri = new URI(raw);
        } catch (Throwable t) {
            return new Verdict(true, "bad_url", "", "", "");
        }
        String scheme = Str.lower(Str.nz(uri.getScheme()));
        boolean schemeOk = false;
        for (String s : SCHEMES) if (s.equals(scheme)) schemeOk = true;
        if (!schemeOk) return new Verdict(true, "scheme", "", Str.nz(uri.getHost()), "");
        String host = cleanHost(uri.getHost());
        if (host.isEmpty()) return new Verdict(true, "bad_url", "", "", "");
        if (devAllowed(host)) return new Verdict(false, "ok", "", host, "");
        InetAddress[] addrs = resolve(host);
        Verdict v = judge(host, addrs);
        if (v != null) return v;
        return new Verdict(false, "ok", "", host, addrs != null && addrs.length > 0
                ? Str.nz(addrs[0].getHostAddress()) : "");
    }

    /** 该主机名/IP 是否属于内网（应当拒绝）。空值按拒绝处理。 */
    public static boolean isInternalHost(String host) {
        String h = cleanHost(host);
        if (h.isEmpty()) return true;
        if (devAllowed(h)) return false;
        return judge(h, resolve(h)) != null;
    }

    /** 判定一个已解析的地址（给 HTTP 层在连接后复核用）。 */
    public static boolean isInternalAddress(InetAddress a) {
        return reasonOf(a) != null;
    }

    // ================================================================ 内部

    /** @return 非 null = 拒绝（带类别）；null = 放行 */
    private static Verdict judge(String host, InetAddress[] addrs) {
        String lower = Str.lower(host);
        for (String n : LOCAL_NAMES) {
            if (lower.equals(n) || lower.endsWith("." + n)) return new Verdict(true, "internal", "name", host, "");
        }
        for (String n : METADATA_NAMES) {
            if (lower.equals(n) || lower.endsWith("." + n)) {
                return new Verdict(true, "internal", "metadata", host, "");
            }
        }
        if (lower.equals("0") || lower.equals("0.0.0.0")) {
            return new Verdict(true, "internal", "unspecified", host, "");
        }
        // 字面量 IP（含十进制/十六进制/八进制/少段写法）：先自己判，再交给解析结果
        long legacy = parseLegacyIpv4(lower);
        if (legacy >= 0L) {
            String reason = reasonOfV4(legacy);
            if (reason != null) {
                return new Verdict(true, "internal", reason, host, ipv4Text(legacy));
            }
        }
        if (addrs == null) return null;                 // 解析失败 → 按非内网放行（原口径）
        for (InetAddress a : addrs) {
            String reason = reasonOf(a);
            if (reason != null) {
                return new Verdict(true, "internal", reason, host,
                        a == null ? "" : Str.nz(a.getHostAddress()));
            }
        }
        return null;
    }

    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return null;
        } catch (Throwable t) {
            return null;                                 // 解析异常一律按"解析不了"处理
        }
    }

    /** @return 类别码；null = 该地址可以出去 */
    private static String reasonOf(InetAddress a) {
        if (a == null) return "unspecified";
        try {
            if (a.isAnyLocalAddress()) return "unspecified";
            if (a.isLoopbackAddress()) return "loopback";
            if (a.isLinkLocalAddress()) {
                return isMetadataIp(a.getHostAddress()) ? "metadata" : "link_local";
            }
            if (a.isSiteLocalAddress()) return "private";
            if (a.isMulticastAddress()) return "multicast";
            byte[] b = a.getAddress();
            if (b == null || b.length == 0) return "unspecified";
            if (b.length == 4) {
                long v = u32(b[0], b[1], b[2], b[3]);
                return reasonOfV4(v);
            }
            // IPv6：fc00::/7 唯一本地（Java 的 isSiteLocalAddress 只管 fec0::/10，不管这块）
            if ((b[0] & 0xFE) == 0xFC) return "unique_local";
            // IPv4 映射/兼容（::ffff:a.b.c.d / ::a.b.c.d）—— 有些实现不折叠成 Inet4Address
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                return reasonOfV4(u32(b[12], b[13], b[14], b[15]));
            }
            if (mapped && b[10] == 0 && b[11] == 0) {
                String r = reasonOfV4(u32(b[12], b[13], b[14], b[15]));
                if (r != null) return r;
            }
            return null;
        } catch (Throwable t) {
            return "unspecified";
        }
    }

    /** @return 类别码；null = 该 IPv4 可以出去 */
    private static String reasonOfV4(long v) {
        long a = (v >>> 24) & 0xFFL;
        long b = (v >>> 16) & 0xFFL;
        long c = (v >>> 8) & 0xFFL;
        if (v == 0L || a == 0L) return "unspecified";                    // 0.0.0.0/8
        if (a == 127L) return "loopback";                                 // 127/8
        if (a == 10L) return "private";                                   // 10/8
        if (a == 172L && b >= 16L && b <= 31L) return "private";          // 172.16/12
        if (a == 192L && b == 168L) return "private";                     // 192.168/16
        if (a == 169L && b == 254L) {                                     // 169.254/16
            return (c == 169L && ((v & 0xFFL) == 254L)) ? "metadata" : "link_local";
        }
        if (a == 100L && b >= 64L && b <= 127L) return "cgnat";            // 100.64/10
        if (a == 192L && b == 0L && c == 0L) return "reserved";            // 192.0.0/24
        if (a == 192L && b == 0L && c == 2L) return "reserved";            // 192.0.2/24（TEST-NET-1）
        if (a == 198L && (b == 18L || b == 19L)) return "reserved";        // 198.18/15（基准测试）
        if (a == 198L && b == 51L && c == 100L) return "reserved";         // 198.51.100/24
        if (a == 203L && b == 0L && c == 113L) return "reserved";          // 203.0.113/24
        if (a >= 224L && a <= 239L) return "multicast";                    // 224/4
        if (a >= 240L) return "reserved";                                  // 240/4 + 广播
        return null;
    }

    private static boolean isMetadataIp(String ip) {
        return "169.254.169.254".equals(ip) || "fd00:ec2::254".equals(Str.lower(ip));
    }

    private static long u32(byte b0, byte b1, byte b2, byte b3) {
        return ((b0 & 0xFFL) << 24) | ((b1 & 0xFFL) << 16) | ((b2 & 0xFFL) << 8) | (b3 & 0xFFL);
    }

    private static String ipv4Text(long v) {
        return ((v >>> 24) & 0xFFL) + "." + ((v >>> 16) & 0xFFL) + "." + ((v >>> 8) & 0xFFL) + "." + (v & 0xFFL);
    }

    /**
     * 解析"传统 IPv4 变形态"（{@code inet_aton} 口径）：十进制/十六进制（{@code 0x}）/八进制（前导 0），
     * 支持 1–4 段（{@code 2130706433} / {@code 127.1} / {@code 0x7f.0.0.1}）。
     *
     * @return 32 位无符号值；{@code -1} = 不是 IP 字面量（当主机名处理）
     */
    static long parseLegacyIpv4(String host) {
        if (host == null || host.isEmpty() || host.length() > 32) return -1L;
        String[] parts = host.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) return -1L;
        long[] vals = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) return -1L;
            long v;
            if (p.length() > 2 && (p.startsWith("0x") || p.startsWith("0X"))) {
                v = parseRadix(p.substring(2), 16);
            } else if (p.length() > 1 && p.charAt(0) == '0') {
                v = parseRadix(p, 8);                     // 前导 0 → 八进制（含 "0" 本身）
            } else if (isDigits(p)) {
                v = parseRadix(p, 10);
            } else {
                return -1L;                               // 有字母又不是 0x → 是主机名
            }
            if (v < 0L) return -1L;
            vals[i] = v;
        }
        for (int i = 0; i < vals.length - 1; i++) if (vals[i] > 0xFFL) return -1L;
        long last = vals[vals.length - 1];
        long maxLast = parts.length == 1 ? 0xFFFFFFFFL
                : (parts.length == 2 ? 0xFFFFFFL : (parts.length == 3 ? 0xFFFFL : 0xFFL));
        if (last > maxLast) return -1L;
        long v = last;
        if (parts.length == 4) v |= vals[0] << 24 | vals[1] << 16 | vals[2] << 8;
        else if (parts.length == 3) v |= vals[0] << 24 | vals[1] << 16;
        else if (parts.length == 2) v |= vals[0] << 24;
        return v & 0xFFFFFFFFL;
    }

    private static long parseRadix(String s, int radix) {
        if (s.isEmpty()) return -1L;
        long out = 0L;
        for (int i = 0; i < s.length(); i++) {
            int d = Character.digit(s.charAt(i), radix);
            if (d < 0) return -1L;
            out = out * radix + d;
            if (out > 0xFFFFFFFFL) return -1L;
        }
        return out;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** 去掉方括号（{@code [::1]}）与末尾点（{@code localhost.}）。 */
    private static String cleanHost(String host) {
        String h = Str.trim(host);
        if (h.isEmpty()) return "";
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) h = h.substring(1, h.length() - 1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    // ================================================================ 离线豁免

    private static boolean devAllowed(String host) {
        Set<String> allow = ensureDevAllow();
        if (allow.isEmpty()) return false;
        String h = Str.lower(host);
        if (allow.contains(h)) return true;
        // 也允许"主机名匹配 + 解析出的地址匹配"两种写法
        InetAddress[] addrs = resolve(host);
        if (addrs != null) {
            for (InetAddress a : addrs) {
                if (a != null && allow.contains(Str.lower(Str.nz(a.getHostAddress())))) return true;
            }
        }
        return false;
    }

    private static Set<String> ensureDevAllow() {
        Set<String> allow = devAllow;
        if (allow == null) {
            synchronized (DEV_LOCK) {
                if (devAllow == null) devAllow = readDevAllow();
                allow = devAllow;
            }
        }
        return allow;
    }

    private static Set<String> readDevAllow() {
        Set<String> out = new LinkedHashSet<String>();
        try {
            String raw = System.getProperty("v4.netguard.devAllow", "");
            for (String p : Str.nz(raw).split("[,\\s]+")) {
                String s = Str.lower(Str.trim(p));
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 供运维/探针查看当前豁免表（只读）。 */
    public static List<String> devAllowList() {
        List<String> out = new ArrayList<String>();
        out.addAll(ensureDevAllow());
        return out;
    }

    /** 结构性名字清单（环回/元数据别名），给状态输出与文档用。 */
    public static List<String> localNames() {
        List<String> out = new ArrayList<String>();
        for (String s : LOCAL_NAMES) out.add(s);
        for (String s : METADATA_NAMES) out.add(s);
        return out;
    }
}
