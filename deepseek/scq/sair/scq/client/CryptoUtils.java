package sair.scq.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 加解密工具 —— AES-256对称加密，密钥由用户密码派生。
 * 
 * <h3>密钥派生</h3>
 * 密码 → SHA-256哈希 → 取前16字节(128位)作为AES密钥。
 * （使用128位AES以保证与JDK8默认策略兼容）
 */
public class CryptoUtils {

    private static final String ALGORITHM = "AES";

    /**
     * 从密码派生AES密钥。
     * @param password 用户密码
     * @return 16字节密钥
     */
    public static byte[] deriveKey(String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(password.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[16];
            System.arraycopy(hash, 0, key, 0, 16);
            return key;
        } catch (Exception e) {
            return password.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * AES加密。
     * @param data 明文
     * @param keyBytes 密钥字节
     * @return Base64编码的密文
     */
    public static String encrypt(String data, byte[] keyBytes) {
        if (data == null || data.isEmpty()) return "";
        try {
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToBase64(encrypted);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * AES解密。
     * @param encryptedBase64 Base64编码的密文
     * @param keyBytes 密钥字节
     * @return 明文
     */
    public static String decrypt(String encryptedBase64, byte[] keyBytes) {
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) return "";
        try {
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(base64ToBytes(encryptedBase64));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== Base64 编解码（避免依赖外部库） ====================

    private static final char[] BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static String bytesToBase64(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int len = data.length;
        for (int i = 0; i < len; i += 3) {
            int b1 = data[i] & 0xFF;
            int b2 = (i + 1 < len) ? data[i + 1] & 0xFF : 0;
            int b3 = (i + 2 < len) ? data[i + 2] & 0xFF : 0;
            int triple = (b1 << 16) | (b2 << 8) | b3;
            sb.append(BASE64_CHARS[(triple >> 18) & 0x3F]);
            sb.append(BASE64_CHARS[(triple >> 12) & 0x3F]);
            sb.append((i + 1 < len) ? BASE64_CHARS[(triple >> 6) & 0x3F] : '=');
            sb.append((i + 2 < len) ? BASE64_CHARS[triple & 0x3F] : '=');
        }
        return sb.toString();
    }

    private static byte[] base64ToBytes(String base64) {
        String s = base64.replaceAll("[^A-Za-z0-9+/=]", "");
        int len = s.length();
        int padding = 0;
        if (len > 0 && s.charAt(len - 1) == '=') padding++;
        if (len > 1 && s.charAt(len - 2) == '=') padding++;

        int byteCount = (len * 6) / 8 - padding;
        byte[] result = new byte[byteCount];
        int byteIndex = 0;

        for (int i = 0; i < len; i += 4) {
            int quad = 0;
            for (int j = 0; j < 4; j++) {
                char c = (i + j < len) ? s.charAt(i + j) : 'A';
                int val;
                if (c >= 'A' && c <= 'Z') val = c - 'A';
                else if (c >= 'a' && c <= 'z') val = c - 'a' + 26;
                else if (c >= '0' && c <= '9') val = c - '0' + 52;
                else if (c == '+') val = 62;
                else if (c == '/') val = 63;
                else val = 0;
                quad = (quad << 6) | val;
            }
            if (byteIndex < byteCount) result[byteIndex++] = (byte)((quad >> 16) & 0xFF);
            if (byteIndex < byteCount) result[byteIndex++] = (byte)((quad >> 8) & 0xFF);
            if (byteIndex < byteCount) result[byteIndex++] = (byte)(quad & 0xFF);
        }
        return result;
    }
}
