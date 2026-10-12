package kr.govbenefit.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import kr.govbenefit.common.api.ApiErrorResponse.InputError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

class BusinessExceptionTest {

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"INTERNAL_ERROR", "REQUEST_FAILED"})
    void 상태_코드가_정해지지_않은_오류_코드는_던질_수_없다(ErrorCode code) {
        assertThatThrownBy(() -> new BusinessException(code)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 상태_코드는_오류_코드가_정한다() {
        Arrays.stream(ErrorCode.values())
                .filter(code -> code.status() != null)
                .forEach(code -> assertThat(new BusinessException(code).status()).isEqualTo(code.status()));
    }

    @Test
    void 입력_오류는_400이고_받은_항목을_응답_오류로_싣는다() {
        InvalidInputException ex = new InvalidInputException("keyword", "가 나", "단어가 많습니다.");

        assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.errors()).containsExactly(new InputError("keyword", "가 나", "단어가 많습니다."));
    }

    @Test
    void 설명은_오류_코드_뒤에_붙여_예외_메시지로_남긴다() {
        assertThat(new BusinessException(ErrorCode.NOT_FOUND, "serviceId=X").getMessage())
                .isEqualTo("NOT_FOUND: serviceId=X");
        assertThat(new BusinessException(ErrorCode.NOT_FOUND).getMessage()).isEqualTo("NOT_FOUND");
    }
}
