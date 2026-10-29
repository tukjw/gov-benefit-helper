# 0006. 공공서비스 API 실패 처리와 호출 예산

- 상태: 채택
- 날짜: 2026-10-01
- 출처: #16, [플랜](../plans/16-collection-fault-tolerance.md)

## 배경

`REQ-QUALITY-01`은 외부 호출에 시간 제한과 재시도 상한을 두고, 호출 제한·일시 장애·인증 실패를 같은 방식으로 무한 재시도하지 않으며, 수집 실행 한 번이 전체 API 한도를 소진하지 않도록 요구한다. 공공서비스 API를 부르는 모든 수집이 같은 기준을 따라야 한다. [0005](0005-gov24-client-and-stub.md)의 호출 방식에 실패 처리를 더한다.

## 결정

- 실패는 아래 유형으로 나누고 유형마다 재시도와 실행 중단 여부를 정한다. 유형은 `CollectionErrorType`이며, `API_` 접두어가 공공 API 오류다.

  | 유형 | 판정 | 재시도 | 처리 |
  | --- | --- | --- | --- |
  | `API_TIMEOUT` | 연결·응답 시간 제한 초과 | 예 | 상한까지 재시도, 소진하면 그 쪽 실패 |
  | `API_UNAVAILABLE` | 연결 실패, 5xx | 예 | 같음 |
  | `API_BAD_RESPONSE` | 200인데 `data` 없음, 본문 해석 불가, 401·403·429 외 4xx | 아니오 | 그 쪽 실패 |
  | `API_TRUNCATED` | 계산한 마지막 쪽 전에 `data: []` | 아니오 | 그 경로 순회를 끝내고 그 쪽 실패 |
  | `API_AUTH` | 401, 403 | 아니오 | 실행 중단 |
  | `API_RATE_LIMIT` | 429 | 아니오 | 실행 중단 |
  | `BUDGET_EXCEEDED` | 다음 시도가 실행 호출 예산을 넘김 | - | 실행 중단 |
  | `DB_ERROR` | 쪽 저장 중 DB 예외 | 아니오 | 그 쪽 실패 |
  | `INTERNAL` | 그 밖의 예외 | 아니오 | 실행 실패 |

- 재시도는 Spring Framework 내장 `RetryTemplate`으로 `Gov24Client.fetchPage` 안에서 한다. 기본값은 최대 재시도 2회(총 3회 시도), 첫 대기 1초에서 두 배씩 늘린다.
- 시간 제한은 연결 5초, 응답 헤더까지 10초다. 본문 수신 시간에는 제한을 두지 않는다.
- 호출 예산은 실행마다 하나(`Gov24CallBudget`)이며, 재시도를 포함한 실제 HTTP 시도마다 하나씩 차감한다. 기본값은 실행당 100회다.
- 호출 제한은 기다렸다가 다시 시도하지 않고 실행을 중단한다. 판정은 `Gov24ErrorClassifier` 한 곳에 둔다.
- 값은 `gov24.connect-timeout`, `read-timeout`, `max-retries`, `retry-delay`, `max-calls-per-run`으로 바꾼다.

## 근거

- 인증 실패는 이후 요청도 모두 실패한다. 200인데 `data`가 없는 응답은 요청이 같으면 결과도 같다([데이터 검증 보고서 §2](../research/gov24-api-data.md#2-실패-응답)).
- 호출 제한·시간 초과·5xx·403 응답은 관찰하지 못했다. 개발계정 트래픽 10,000의 기간 단위(일·월)도 확인하지 않았다. 한도가 일·월 단위라면 기다려도 풀리지 않고, `Retry-After`를 보내는지 근거가 없다. 수집은 하루 1회라 다음 실행에서 다시 받는다.
- 전체 수집은 정상일 때 33회 호출, 쪽 응답은 최대 1.1초다. 예산 100회는 정상 호출의 약 3배이고, 시간 제한 10초는 실측 최대의 약 10배다.
- `DB_ERROR`로 실행을 멈추면 컬럼보다 긴 값 하나처럼 데이터에 따라 나는 오류 때문에 그 쪽 뒤의 모든 쪽이 매일 갱신되지 않는다. DB를 아예 쓸 수 없으면 실패 기록도 저장하지 못하므로 그때 실행을 끝낸다.
- 제외한 대안: 직접 구현한 재시도 반복문(백오프·분기 관리 부담), Resilience4j(Spring Boot 4 호환 미검증, 하루 1회 배치에 서킷 브레이커는 과함), spring-retry(유지보수 모드), 경로별 예산(설정이 세 배), 시작 전 필요 호출 수 계산(경로마다 첫 쪽을 받아야 알 수 있음).

## 적용 위치

- `backend/src/main/java/kr/govbenefit/collection/domain/CollectionErrorType.java`
- `backend/src/main/java/kr/govbenefit/collection/client/`: `Gov24Client`, `Gov24ErrorClassifier`, `Gov24CallBudget`, `Gov24ApiException`, `Gov24Properties`
- `backend/src/main/resources/application.yaml`: `gov24.*`
- `backend/src/test/java/kr/govbenefit/collection/service/CollectionFaultToleranceTest.java`

## 영향

- 공공서비스 API를 새로 부르는 수집은 `Gov24Client.fetchPage`와 실행의 `Gov24CallBudget`을 거친다.
- 호출 제한 응답이 실제로 관찰되면 `Gov24ErrorClassifier`의 판정을 고치고 이 문서를 대체한다.
- 본문 수신이 느려 생기는 문제가 관찰되면 호출 전체 시간 상한을 더한다.
