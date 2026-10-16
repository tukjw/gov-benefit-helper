package kr.govbenefit.account.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 가입 전 이메일 인증번호의 생성과 해시(결정 0019). 번호는 6자리 숫자이며 앞자리 0도 자리로 센다.
 *
 * <p>해시는 SHA-256이다. 경우의 수가 10^6이라 DB가 유출되면 해시로 번호를 역산할 수 있다. 번호가 10분 뒤 만료되고
 * 비교 횟수를 제한한다는 점에 기댄다.
 */
public final class VerificationCodes {

    public static final int LENGTH = 6;
    private static final int BOUND = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationCodes() {
    }

    public static String generate() {
        return format(RANDOM.nextInt(BOUND));
    }

    static String format(int number) {
        return String.format("%0" + LENGTH + "d", number);
    }

    public static String hash(String code) {
        return OpaqueTokens.hash(code);
    }

    /**
     * 해시 비교에 걸리는 시간이 앞부분이 얼마나 맞는지에 따라 달라지지 않게 한다.
     */
    public static boolean matches(String code, String codeHash) {
        return MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.US_ASCII),
                codeHash.getBytes(StandardCharsets.US_ASCII));
    }
}
