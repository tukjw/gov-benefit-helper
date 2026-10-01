# 0005. 공공서비스 API 호출과 테스트 stub

- 상태: 채택
- 날짜: 2026-09-30
- 출처: #15, [플랜](../plans/15-policy-collection.md)

## 배경

수집, 외부 장애 대응, 정기 실행 이슈가 모두 공공서비스(혜택) API를 호출한다. 인증키 노출을 막는 방식과 외부 호출을 재현하는 테스트 방식을 하나로 맞춰야 한다.

## 결정

- 호출은 `Gov24Client`(Spring `RestClient`)로만 한다. 인증은 `Authorization: Infuser <Decoding 키>` 헤더로 하고 `serviceKey` 쿼리는 쓰지 않는다.
- 인증키는 `GOV24_SERVICE_KEY` 환경 변수(또는 `backend/.env`)로만 받는다. 앱 기동에는 필요 없고 수집을 시작할 때 확인한다. 요청 헤더를 로그로 남기지 않고, 실패 메시지에는 경로·쪽·상태 코드와 응답의 `code`·`msg`만 담는다.
- 200인데 `data`가 없는 응답은 실패로, 빈 `data`는 마지막 쪽 뒤로 본다.
- 테스트는 MockWebServer 기반 로컬 stub(`Gov24StubServer`)으로 실제 HTTP를 흉내 낸다. fixture는 T0 스냅샷에서 고른 실제 레코드다.

## 근거

- 쿼리로 보내면 키가 URL과 접근 로그에 남는다. 헤더의 `Infuser` 접두어는 명세에 없지만 실제 호출로 확인했다([데이터 검증 보고서 §1](../research/gov24-api-data.md#1-인증과-페이지-조회)).
- `MockRestServiceServer`는 실제 서버가 아니라 시간 초과·연결 실패를 재현할 수 없다. WireMock은 Jetty 버전이 Spring Boot BOM과 충돌할 위험이 있다. MockWebServer는 Boot BOM이 관리하지 않아 버전을 `build.gradle`에 명시한다.

## 적용 위치

- `backend/src/main/java/kr/govbenefit/collection/client/`: `Gov24Client`, `Gov24Properties`
- `backend/src/main/resources/application.yaml`: `gov24.*`
- `backend/.env.example`: `GOV24_SERVICE_KEY` 안내
- `backend/src/test/java/kr/govbenefit/collection/Gov24StubServer.java`, `backend/src/test/resources/gov24/`

## 영향

- 시간 제한·재시도·호출 예산은 `Gov24Client`에 더하고, 같은 stub으로 지연·오류 응답을 재현해 검증한다.
- 실제 API에 요청하는 확인은 기본 `./gradlew test`에 넣지 않는다. 로컬에서는 `collect` 프로필로 실행한다.
