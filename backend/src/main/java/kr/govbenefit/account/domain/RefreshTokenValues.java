package kr.govbenefit.account.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 리프레시 토큰 원문의 생성과 해시(결정 0017). 원문은 32바이트 난수의 base64url(패딩 없음, 43자)이다.
 */
public final class RefreshTokenValues {

    private static final int RANDOM_BYTES = 32;
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenValues() {
    }

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 형식이 틀린 값은 DB를 조회하지 않고 거절한다.
     */
    public static boolean isWellFormed(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    /**
     * 원문의 SHA-256(소문자 hex). 원문의 엔트로피가 256비트라 느린 해시나 솔트가 필요 없고, 같은 원문이 같은 값이 되어 조회에 쓴다.
     */
    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
