package kr.govbenefit.common.api;

/**
 * 요청한 자원이 없다. {@code NOT_FOUND}로 응답한다.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
