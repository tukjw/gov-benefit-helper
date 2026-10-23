package kr.govbenefit.common.api;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import kr.govbenefit.common.security.CurrentUserId;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 공개 API의 OpenAPI 명세(결정 0012). 명세와 문서 화면은 local 프로필에서만 열린다.
 */
@Configuration
public class OpenApiConfig {

    /** 액세스 토큰 인증의 보안 스키마 이름. 개인 API의 {@code @Operation(security = ...)}에 쓴다. */
    public static final String BEARER_AUTH = "bearerAuth";

    private static final String ERROR_SCHEMA = ApiErrorResponse.class.getSimpleName();

    private static final String DESCRIPTION = """
            로그인 없이 쓰는 혜택 검색·상세 API와 이메일 인증·가입·로그인·로그인 유지·로그아웃·비밀번호 재설정·내 정보·프로필·북마크·조건 비교·탈퇴 API.

            - 가입은 인증번호 요청(`POST /api/auth/email-verification`) → 메일의 6자리 번호로 확인(`/confirm`, 가입 티켓을 받음) → 가입(`POST /api/auth/signup`에 티켓을 넣음) 순서다(결정 0019).
            - 비밀번호를 잊으면 재설정 요청(`POST /api/auth/password-reset`)으로 메일의 링크를 받고, 링크의 `#token=` 값과 새 비밀번호로 재설정(`/confirm`)한다. 재설정하면 그 계정의 모든 로그인이 끝난다(결정 0019).
            - 개인 API는 로그인으로 받은 액세스 토큰을 `Authorization: Bearer <토큰>` 헤더로 보낸다. 토큰은 15분 뒤 만료되며, 토큰이 없거나 위조·만료됐으면 401 `UNAUTHENTICATED`다(결정 0014·0018).
            - 로그인하면 리프레시 토큰을 `refresh_token` 쿠키(HttpOnly)로 준다. 액세스 토큰이 만료되면 재발급 API로 새 액세스 토큰을 받고, 로그아웃 API로 리프레시 토큰을 폐기한다. 리프레시 토큰은 14일 동안 쓰지 않으면 만료된다(결정 0017).
            - 프로필은 생년월일·거주 시도·시군구·성별·생애·가구 속성이며 모두 선택 입력이다. `PUT /api/me/profile`은 본문 전체로 바꾸고, 빠진 필드와 `null`은 미입력이다. 속성의 `["NONE"]`은 해당 없음이다. 지역 코드(행정표준기관코드)와 속성 값은 `GET /api/me/profile/options`에서 고른다(결정 0021·0022).
            - 북마크는 서비스 ID로 저장(`PUT /api/me/bookmarks/{serviceId}`)·해제(`DELETE`)하고, 둘 다 다시 보내도 결과가 같다. 목록(`GET /api/me/bookmarks`)은 마지막으로 저장한 순서이고 혜택의 현재 정보를 보여준다.
            - 조건 비교(`GET /api/me/matches/{serviceId}`)는 프로필과 혜택의 지원조건 코드를 비교한 결과이며 신청 가능 확정이 아니다. 결과가 `NEEDS_CHECK`이면 함께 주는 원문과 공식 링크로 확인한다(DEC-MATCH). 목록(`GET /api/me/matches`)은 `MATCH` → `NEEDS_CHECK` 순이고 개인 대상이 아닌 혜택은 나오지 않는다.
            - 탈퇴(`POST /api/me/withdrawal`)는 비밀번호를 다시 받고 계정·프로필·북마크·토큰을 즉시 지운다. 되돌릴 수 없고, 같은 이메일로 다시 가입하면 새 사용자다.
            - 성공 응답은 데이터를 그대로 보낸다. 실패할 때만 HTTP 상태 코드와 `ApiErrorResponse` 본문(`code`, `message`, `errors`)을 보낸다(결정 0009). 분기는 `code`로 한다.
            - 시각은 `+09:00` 오프셋을 붙인 ISO-8601 문자열이다. 예: `2026-10-04T04:00:12.345+09:00`(결정 0010).
            - 값이 없는 필드도 `null`로 응답에 들어간다. 서버는 기본 문구로 채우지 않으므로 '정보 없음' 표시는 클라이언트가 한다.
            """;

    static {
        // 요청자 ID는 토큰에서 얻으므로 명세의 요청 파라미터가 아니다.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUserId.class);
    }

    @Bean
    OpenAPI openApi() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("로그인 API가 준 액세스 토큰. 15분 뒤 만료된다.");
        return new OpenAPI()
                .info(new Info().title("gov-benefit-helper API").description(DESCRIPTION))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, bearer));
    }

    /**
     * 처리하지 못한 예외는 모든 API에서 500 {@code INTERNAL_ERROR}가 된다({@link GlobalExceptionHandler}).
     */
    @Bean
    OpenApiCustomizer internalErrorResponse() {
        return openApi -> {
            // 오류 응답을 명시한 API가 없으면 스키마가 등록되지 않으므로 직접 등록한다.
            if (!openApi.getComponents().getSchemas().containsKey(ERROR_SCHEMA)) {
                ModelConverters.getInstance(true).readAll(ApiErrorResponse.class)
                        .forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
            }
            ApiResponse response = new ApiResponse()
                    .description("서버에서 처리하지 못했다. `code`는 `INTERNAL_ERROR`다.")
                    .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                            new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA))));
            openApi.getPaths().values().forEach(path -> path.readOperations()
                    .forEach(operation -> operation.getResponses().addApiResponse("500", response)));
        };
    }
}
