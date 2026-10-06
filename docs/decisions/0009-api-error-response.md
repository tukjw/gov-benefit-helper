# 0009. API 오류 응답 형식

- 상태: 채택
- 날짜: 2026-10-04
- 출처: #29, [플랜](../plans/29-benefit-search-api.md)

## 배경

#29에서 첫 공개 API(혜택 검색)를 만들면서 잘못된 입력을 400으로 응답해야 했다. 오류 본문 형식이 API마다 다르면 클라이언트가 API마다 오류를 따로 해석해야 한다. #30의 상세 조회부터 이후 모든 API가 같은 형식을 써야 한다.

## 결정

성공 응답은 데이터를 그대로 보내고, 실패할 때만 HTTP 상태 코드와 다음 본문을 보낸다. 성공·실패를 함께 감싸는 envelope은 쓰지 않는다.

```json
{
  "code": "INVALID_INPUT",
  "message": "요청 값이 올바르지 않습니다.",
  "errors": [
    { "field": "size", "value": "200", "reason": "1 이상 100 이하여야 합니다." }
  ]
}
```

- `code`: 클라이언트가 분기에 쓰는 고정 문자열이다. 목록은 `ErrorCode`에 있다.

  | 코드 | 상태 | 뜻 |
  | --- | --- | --- |
  | `INVALID_INPUT` | 400 | 요청 값이 올바르지 않다. 항목 오류는 `errors`에 있다 |
  | `NOT_FOUND` | 404 | 요청한 경로나 자원이 없다 |
  | `INTERNAL_ERROR` | 5xx | 서버에서 처리하지 못했다 |
  | `REQUEST_FAILED` | 그 밖의 상태 | 위에 해당하지 않는 실패(405 등). 상태 코드로 원인을 구분한다 |

- `message`: 사람이 읽는 한국어 문구다. 클라이언트는 이 값으로 분기하지 않는다.
- `errors`: 입력 항목별 오류이며 항상 배열이다. 항목 오류가 없으면 비어 있다. `field`는 요청 파라미터 이름, `value`는 받은 값(없었으면 `null`), `reason`은 거절한 이유다.
- 5xx 응답에는 예외 메시지나 내부 정보를 넣지 않고 로그에만 남긴다.

## 근거

- Spring `ProblemDetail`(RFC 9457)은 코드가 가장 적지만, 오류 형식을 직접 설계해 보기로 했다(2026-10-04 사용자 결정).
- 성공·실패 공통 envelope(`success`, `data`, `error`)은 클라이언트가 응답을 한 가지 방식으로 처리할 수 있지만, HTTP 상태 코드와 뜻이 겹치고 성공 응답이 한 겹 깊어진다. 상태 코드로 성공·실패를 가르고 실패만 본문 형식을 정했다.
- `timestamp`, `path` 같은 추적 필드는 클라이언트가 쓸 일이 없어 넣지 않았다. 추적이 필요하면 로그와 관측 도구(`DEC-OPS`)에서 다룬다.
- `errors`를 항상 배열로 두어 클라이언트가 `null` 검사 없이 처리하게 했다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/common/api/ApiErrorResponse.java`: 본문 형식
- `backend/src/main/java/kr/govbenefit/common/api/ErrorCode.java`: 코드 목록과 상태 코드 대응
- `backend/src/main/java/kr/govbenefit/common/api/GlobalExceptionHandler.java`: Spring MVC 예외와 입력 검증 실패를 이 형식으로 바꾼다
- `backend/src/main/java/kr/govbenefit/common/api/InvalidInputException.java`: Bean Validation 제약으로 표현하지 않은 입력 검증
- `backend/src/main/java/kr/govbenefit/common/api/NotFoundException.java`: 요청한 자원이 없다(`NOT_FOUND`). #30에서 추가
- `backend/src/main/java/kr/govbenefit/common/api/OpenApiConfig.java`: API 명세의 공통 500 응답. `ApiErrorResponse`의 스키마 설명과 함께 #33에서 추가([0012](0012-api-spec-docs.md))

## 영향

- 새 API는 오류 본문을 이 형식으로만 보낸다. 컨트롤러에서 오류 본문을 직접 만들지 않고 예외로 던진다.
- 코드를 추가하거나 뜻을 바꾸면 `ErrorCode`와 이 문서의 표를 함께 고친다. 이미 공개한 코드의 뜻은 바꾸지 않는다.
- 입력 검증 메시지는 요청 언어에 따라 바뀌지 않도록 제약 애너테이션의 `message`에 한국어로 적는다.
