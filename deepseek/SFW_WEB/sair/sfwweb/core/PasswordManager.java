package sair.sfwweb.core;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * SFW Web 密码管理器
 * 使用 PBKDF2WithHmacSHA256 进行安全的密码哈希
 * 首次登录时设置密码，后续登录验证密码
 */
public class PasswordManager {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 32;

    private final ConfigManager config;

    public PasswordManager(ConfigManager config) {
        this.config = config;
    }

    /**
     * 检查密码是否已设置
     */
    public boolean isPasswordSet() {
        return config.isPasswordSet();
    }

    /**
     * 设置新密码（首次使用或重置密码时调用）
     * @param password 明文密码
     */
    public boolean setPassword(String password) {
        if (password == null || password.length() < 6) {
            return false; // 密码至少6位
        }
        try {
            byte[] salt = generateSalt();
            byte[] hash = hashPassword(password, salt);

            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            String hashBase64 = Base64.getEncoder().encodeToString(hash);

            config.setPasswordSalt(saltBase64);
            config.setPasswordHash(hashBase64);
            return true;
        } catch (Exception e) {
            System.err.println("[SFW_WEB] Password set error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 验证密码
     * @param password 用户输入的明文密码
     * @return true=密码正确，false=密码错误
     */
    public boolean verifyPassword(String password) {
        if (!isPasswordSet()) return false;
        if (password == null) return false;

        try {
            String saltBase64 = config.getPasswordSalt();
            String hashBase64 = config.getPasswordHash();

            if (saltBase64 == null || saltBase64.isEmpty()) return false;
            if (hashBase64 == null || hashBase64.isEmpty()) return false;

            byte[] salt = Base64.getDecoder().decode(saltBase64);
            byte[] expectedHash = Base64.getDecoder().decode(hashBase64);
            byte[] actualHash = hashPassword(password, salt);

            return slowEquals(expectedHash, actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 重置密码（需要验证旧密码）
     */
    public boolean resetPassword(String oldPassword, String newPassword) {
        if (!verifyPassword(oldPassword)) return false;
        return setPassword(newPassword);
    }

    /**
     * 使用 PBKDF2 对密码进行哈希
     */
    private byte[] hashPassword(String password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
        return factory.generateSecret(spec).getEncoded();
    }

    /**
     * 生成随机盐值
     */
    private byte[] generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return salt;
    }

    /**
     * 时间恒定比较，防止时序攻击
     */
    private boolean slowEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
