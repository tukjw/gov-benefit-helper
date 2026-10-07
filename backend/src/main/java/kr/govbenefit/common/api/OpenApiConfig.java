package kr.govbenefit.common.api;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 공개 API의 OpenAPI 명세(결정 0012). 명세와 문서 화면은 local 프로필에서만 열린다.
 */
@Configuration
public class OpenApiConfig {

    private static final String ERROR_SCHEMA = ApiErrorResponse.class.getSimpleName();

    private static final String DESCRIPTION = """
            로그인 없이 쓰는 혜택 검색·상세 API.

            - 성공 응답은 데이터를 그대로 보낸다. 실패할 때만 HTTP 상태 코드와 `ApiErrorResponse` 본문(`code`, `message`, `errors`)을 보낸다(결정 0009). 분기는 `code`로 한다.
            - 시각은 `+09:00` 오프셋을 붙인 ISO-8601 문자열이다. 예: `2026-10-04T04:00:12.345+09:00`(결정 0010).
            - 값이 없는 필드도 `null`로 응답에 들어간다. 서버는 기본 문구로 채우지 않으므로 '정보 없음' 표시는 클라이언트가 한다.
            """;

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("gov-benefit-helper API").description(DESCRIPTION));
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
