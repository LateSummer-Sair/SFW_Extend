package sair.v4.kit;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 敏感配置的静态加密（{@code config.json} 里的 apiKey / napcatToken 等）。
 *
 * <p><b>算法与 V3 的 {@code sair.aiagent.core.AiConfig} 逐字节对齐</b>，移植时只抄了口径、没抄实现：</p>
 *
 * <ul>
 *   <li>密钥：{@code SHA-256(java.vm.name | os.arch | user.name | hostname | user.dir | "AiAgent@SFW2024!")}
 *       取前 16 字节 → AES-128。字段顺序、拼接方式（**直接串接，无分隔符**）、尾串都在内。
 *       {@code hostname} 那一项在 V3 里是 {@code try/catch} 包着的：解析不出就**整段跳过**，
 *       所以同一台机器上"能解析主机名"与"不能解析"会派生出两把不同的密钥。</li>
 *   <li>加密：{@code AES/GCM/NoPadding}，随机 12 字节 IV，认证标签 128 位，**无 AAD**；
 *       存储 = {@code Base64(IV(12B) || 密文 || tag(16B))}，标准字母表带 {@code =} 填充。
 *       只是一个裸拼接，**没有任何前缀/分隔符**（所以能和 V3 的存量密文互通）。</li>
 *   <li>解密：先试 GCM；长度不足或 GCM 失败时回落到 V3 的旧格式
 *       {@code AES/ECB/PKCS5Padding}（只为兼容老配置，本机存量数据走的是 GCM）。</li>
 * </ul>
 *
 * <p><b>这意味着密钥与运行环境绑死</b>：换机器、换 JVM 实现、换用户名，或换个工作目录启动，
 * 都会解不开。所以 {@link #factorReport()} 存在的意义是"解密失败时把当前因子打出来"，
 * 让人一眼看出是哪一项变了（它只含路径与名字，不含密钥）。</p>
 */
public final class Crypto {

    /** GCM IV 长度（V3 的 {@code GCM_IV_LEN}）。 */
    public static final int IV_LEN = 12;

    /** GCM 认证标签位数（V3 的 {@code GCM_TAG_LEN}）。 */
    public static final int TAG_BITS = 128;

    /** 密钥派生尾串（V3 原样）。 */
    public static final String SEED = "AiAgent@SFW2024!";

    /** 短于这个长度就不可能是本格式的 GCM 密文（V3 的 {@code decrypt} 也用这个界判断）。 */
    public static final int MIN_GCM_LEN = IV_LEN + 16;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 派生一次就缓存（V3 是静态初始化）。 */
    private static volatile byte[] key;

    private Crypto() {}

    // ---------------------------------------------------------------- 对外

    /**
     * 加密。空串原样返回（空值不加密，保持 {@code ""}）。
     * 失败返回 {@code null}（不抛异常：配置读写不该炸掉基板）。
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[IV_LEN];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(UTF8));
            byte[] all = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, all, 0, IV_LEN);
            System.arraycopy(ct, 0, all, IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解密。**解得开才返回明文**；任何失败（不是密文 / 因子变了 / 密文损坏）一律返回 {@code null}，
     * 由调用方决定怎么提示 —— 绝不静默返回空串。
     */
    public static String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) return null;
        byte[] all;
        try {
            all = Base64.getDecoder().decode(enc);
        } catch (Throwable t) {
            return null;                                   // 根本不是 base64 → 不是密文
        }
        if (all.length < MIN_GCM_LEN) return legacyEcb(all);   // 太短 → 试 V3 旧格式
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), UTF8);
        } catch (Throwable t) {
            return legacyEcb(all);                          // 与 V3 一致：GCM 失败再试一次旧格式
        }
    }

    /**
     * 这个值"像不像密文"：合法 base64 且解码后 ≥ {@link #MIN_GCM_LEN} 字节。
     *
     * <p>用来区分"盘上是密文"与"盘上是明文（老配置 / 手填）"。
     * 明文 key（如 {@code sk-xxxx}）通常长度不足 28 字节，或者含 {@code -}、{@code _} 等
     * 非标准 base64 字符，会被判成"不是密文"，于是留给 {@code save()} 去加密 —— 这就是平滑升级。</p>
     */
    public static boolean looksEncrypted(String v) {
        if (v == null || v.isEmpty()) return false;
        try {
            return Base64.getDecoder().decode(v).length >= MIN_GCM_LEN;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前派生因子的可读描述（**不含密钥**，给解密失败的提示用）。 */
    public static String factorReport() {
        return "vm.name='" + prop("java.vm.name") + "'"
                + " os.arch='" + prop("os.arch") + "'"
                + " user.name='" + prop("user.name") + "'"
                + " hostname='" + hostName() + "'"
                + " user.dir='" + prop("user.dir") + "'";
    }

    // ---------------------------------------------------------------- 内部

    /** AES-128 密钥 = 六因子 SHA-256 的前 16 字节（顺序与 V3 一致）。 */
    public static byte[] aesKey() {
        byte[] k = key;
        if (k != null) return k;
        synchronized (Crypto.class) {
            if (key == null) key = derive();
            return key;
        }
    }

    private static byte[] derive() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prop("java.vm.name").getBytes(UTF8));
            sha.update(prop("os.arch").getBytes(UTF8));
            sha.update(prop("user.name").getBytes(UTF8));
            String hn = hostName();
            if (hn != null) sha.update(hn.getBytes(UTF8));    // 解析不出就整段跳过（V3 的 try/catch 语义）
            sha.update(prop("user.dir").getBytes(UTF8));
            sha.update(SEED.getBytes(UTF8));
            byte[] full = sha.digest();
            byte[] k = new byte[16];
            System.arraycopy(full, 0, k, 0, 16);
            return k;
        } catch (Throwable t) {
            return new byte[16];                              // SHA-256 不可能缺席；兜底不抛
        }
    }

    /** V3 的旧格式：AES/ECB/PKCS5Padding（无认证，仅兼容老配置）。 */
    private static String legacyEcb(byte[] all) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey(), "AES"));
            String s = new String(c.doFinal(all), UTF8);
            return printable(s) ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 32 || ch > 126) return false;
        }
        return !s.isEmpty();
    }

    private static String prop(String k) {
        String v = System.getProperty(k, "");
        return v == null ? "" : v;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Throwable t) {
            return null;                                      // 与 V3 一致：跳过这一项
        }
    }
}
