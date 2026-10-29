# 0002. DB와 애플리케이션 시간대

- 상태: 채택
- 날짜: 2026-09-27
- 출처: #1, [플랜](../plans/1-backend-init.md)

## 배경

서비스 사용자와 공공 API 데이터는 한국 시간을 기준으로 한다. DB 서버, JDBC 세션, Hibernate 중 하나라도 시간대가 다르면 저장하고 읽은 시각이 어긋난다.

## 결정

DB 서버, JDBC 연결, Hibernate 시각 변환을 모두 `Asia/Seoul`로 맞춘다. 로컬 DB와 테스트 DB도 같은 시간대로 띄운다.

## 근거

- JDBC의 `serverTimezone`은 서버 세션 시간대를 바꾸지 않고, 서버가 그 시간대라고 가정만 한다. 그래서 서버 시간대도 함께 맞춰야 한다.
- 시간대를 지정하지 않은 MySQL 컨테이너는 UTC로 뜬다(확인). 테스트 컨테이너에도 시간대를 명시한다.

## 적용 위치

- `docker-compose.local.yml`: `TZ: Asia/Seoul`
- `backend/src/test/java/kr/govbenefit/TestcontainersConfiguration.java`: `TZ` 환경 변수, `serverTimezone` URL 파라미터
- `backend/src/main/resources/application.yaml`: JDBC URL의 `serverTimezone`, `spring.jpa.properties.hibernate.jdbc.time_zone`

## 영향

- 새 시각 컬럼(수집 시각, 마지막 확인 시각 등)은 이 시간대 기준으로 저장·조회한다.
- 수집 스케줄러의 실행 시각과 날짜 경계는 이 시간대로 해석한다.
- 운영 DB와 애플리케이션 실행 환경을 구성할 때 같은 시간대를 쓴다.
