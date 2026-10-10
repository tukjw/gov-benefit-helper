package kr.govbenefit.account.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 가입할 때의 비밀번호 길이 규칙({@link kr.govbenefit.account.domain.PasswordRule}). null은 검사하지 않는다.
 */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Password {

    String message() default PasswordValidator.TOO_SHORT;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
