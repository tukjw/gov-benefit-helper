package kr.govbenefit.account.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 가입 비밀번호 규칙: 8자 이상(코드 포인트), UTF-8 72바이트 이하(결정 0013).
 */
class PasswordValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 일곱_자는_짧고_여덟_자는_통과한다() {
        assertThat(reasons("a".repeat(7))).containsExactly(PasswordValidator.TOO_SHORT);
        assertThat(reasons("a".repeat(8))).isEmpty();
        assertThat(reasons("")).containsExactly(PasswordValidator.TOO_SHORT);
    }

    @Test
    void 글자_수는_코드_포인트로_센다() {
        // 이모지는 UTF-16으로 2글자지만 1자로 센다. 7개는 14글자여도 짧다.
        assertThat(reasons("😀".repeat(7))).containsExactly(PasswordValidator.TOO_SHORT);
        assertThat(reasons("😀".repeat(8))).isEmpty();
    }

    @Test
    void 바이트는_UTF_8로_72까지다() {
        assertThat(reasons("a".repeat(72))).isEmpty();
        assertThat(reasons("a".repeat(73))).containsExactly(PasswordValidator.TOO_LONG);
        // 한글은 3바이트라 24자까지다.
        assertThat(reasons("가".repeat(24))).isEmpty();
        assertThat(reasons("가".repeat(25))).containsExactly(PasswordValidator.TOO_LONG);
        // 이모지는 4바이트라 18자까지다.
        assertThat(reasons("😀".repeat(18))).isEmpty();
        assertThat(reasons("😀".repeat(19))).containsExactly(PasswordValidator.TOO_LONG);
    }

    @Test
    void 공백도_글자다() {
        assertThat(reasons(" ".repeat(8))).isEmpty();
    }

    @Test
    void null은_필수_검사가_맡는다() {
        assertThat(reasons(null)).containsExactly("비밀번호를 입력해야 합니다.");
    }

    private static List<String> reasons(String password) {
        return validator.validateProperty(new SignUpRequest("me@example.com", password), "password").stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }
}
