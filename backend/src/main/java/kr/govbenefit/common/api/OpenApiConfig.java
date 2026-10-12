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
            로그인 없이 쓰는 혜택 검색·상세 API와 가입·로그인·내 정보 API.

            - 개인 API는 로그인으로 받은 액세스 토큰을 `Authorization: Bearer <토큰>` 헤더로 보낸다. 토큰은 15분 뒤 만료되며, 토큰이 없거나 위조·만료됐으면 401 `UNAUTHENTICATED`다(결정 0014·0015).
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
