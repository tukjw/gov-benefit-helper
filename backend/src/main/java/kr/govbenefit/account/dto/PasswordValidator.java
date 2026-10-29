package kr.govbenefit.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import kr.govbenefit.account.domain.PasswordRule;

/**
 * 짧은 경우와 긴 경우를 다른 문구로 알린다.
 */
public class PasswordValidator implements ConstraintValidator<Password, String> {

    static final String TOO_SHORT = "8자 이상이어야 합니다.";
    static final String TOO_LONG = "72바이트 이하여야 합니다(한글은 24자).";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String reason = !PasswordRule.isLongEnough(value) ? TOO_SHORT
                : !PasswordRule.isWithinMaxBytes(value) ? TOO_LONG
                : null;
        if (reason == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(reason).addConstraintViolation();
        return false;
    }
}
