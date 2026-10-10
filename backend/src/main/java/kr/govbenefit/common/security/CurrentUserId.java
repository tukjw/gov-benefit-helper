package kr.govbenefit.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 요청한 사용자의 ID({@code Long})를 넣는다. 액세스 토큰의 {@code sub}에서 얻는다.
 * 개인 API는 사용자 ID를 경로·파라미터·본문으로 받지 않고 이 값만 쓴다(결정 0015).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {
}
