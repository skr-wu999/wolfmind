package com.wolfmind.application.user;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 口令散列（INV-U1：口令只以散列与盐存储）。
 *
 * <p>用 PBKDF2-HMAC-SHA256 而不是裸 SHA-256：口令熵低，必须靠**慢散列**增加离线爆破成本。
 * 迭代次数是可配的，因为测试需要快、生产需要慢——把它写死会让"测试跑得动"与"生产够安全"
 * 只能二选一。
 *
 * <p>每次散列都用新的随机盐：同一个口令在不同账号下得到不同散列，避免彩虹表与
 * "两个账号口令相同"这一事实被看出来。
 *
 * <p><b>比对必须用常量时间</b>：{@link MessageDigest#isEqual} 逐字节比较但不短路，
 * 避免通过响应时间逐字节猜出散列。
 */
public final class PasswordHasher {

    /** 算法标识进凭据记录，便于将来换算法时识别旧数据。 */
    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 生产默认迭代次数；测试用低值以保持构建快速。 */
    public static final int DEFAULT_ITERATIONS = 120_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    public PasswordHasher(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("迭代次数必须为正数，实际为 " + iterations);
        }
        this.iterations = iterations;
    }

    public int iterations() {
        return iterations;
    }

    /** 生成新盐（Base64）。 */
    public String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** 按给定盐散列口令。 */
    public String hash(String password, String salt) {
        if (password == null) {
            throw new IllegalArgumentException("口令不能为空");
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    Base64.getDecoder().decode(salt),
                    iterations,
                    KEY_BITS);
            byte[] key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException error) {
            throw new IllegalStateException("口令散列失败：" + error.getMessage(), error);
        }
    }

    /** 常量时间比对。 */
    public static boolean matches(String candidateHash, String storedHash) {
        if (candidateHash == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidateHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                storedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}