package kr.govbenefit.account.domain;

import java.nio.charset.StandardCharsets;

/**
 * 비밀번호 길이 규칙(결정 0013, DEC-AUTH). 8자 이상, UTF-8 72바이트 이하다. 글자 수는 코드 포인트로 센다.
 * 72바이트는 BCrypt가 처리하는 최대 길이다. 한글만 쓰면 24자까지다.
 */
public final class PasswordRule {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordRule() {
    }

    public static boolean isLongEnough(String password) {
        return password.codePointCount(0, password.length()) >= MIN_LENGTH;
    }

    public static boolean isWithinMaxBytes(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
