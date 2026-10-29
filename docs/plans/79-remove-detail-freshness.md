status: done

# 구현 계획 — #79 상세 API 응답에서 오래된 정보 판정(freshness)을 제거

브랜치: `refactor/79-remove-detail-freshness` (develop `1105ea3` 기준)

## 목표 및 범위

- 목표: 쓰는 곳이 없는 서버의 오래된 정보 판정을 없앤다. 상세 API는 수집 실행을 조회하지 않고 저장된 혜택과 상세만 읽는다. 정보 표시 기준은 "서버는 오래된 정보를 판정하지 않고, 출처 수정 시각과 수집 시각을 구분해 보여준다"로 바꾼다.
- 포함:
  - 상세 응답의 `freshness` 필드, `Freshness` DTO, 판정을 위한 수집 실행 조회.
  - 판정 테스트, API 명세 테스트의 `Freshness` 항목, 프론트 API 타입.
  - `REQ-DETAIL-01`, `DEC-FRESHNESS`(정보 표시 기준), 디자인 문서의 정보 최신성 행.
- 제외:
  - `DEC-FRESHNESS`의 미정 항목(캐시 허용 지연·장애 시 응답 기준, 알림 날짜 재검증)과 `REQ-PERF-01`의 캐시 문장. 그대로 둔다.
  - 수집 실행(`collection_run`)의 기록 방식과 상태 정의(결정 0007).
  - 지난 플랜(#29, #30, #33, #66, #72)과 채택된 결정 문서(0010, 0012)의 배경 문단. 당시 기록이므로 고치지 않는다.
  - 화면 변경. 상세 화면은 이미 `freshness`를 읽지 않는다(#72).

## 확인된 사실과 제약

- 판정 코드
  - `BenefitQueryService.getDetail`(93–101행)이 `CollectionRunRepository.findFirstByStatusInOrderByFinishedAtDesc(LIST_UPDATED)`로 기준 시각을 찾고 `Freshness.judge(referenceAt, LocalDateTime.now(clock))`로 판정한다.
  - 이 클래스에서 `LIST_UPDATED`, `collectionRunRepository`, `clock`, `CollectionRun`·`CollectionStatus`·`Freshness`·`LocalDateTime` import는 이 판정에만 쓰인다.
  - `CollectionRunRepository.findFirstByStatusInOrderByFinishedAtDesc`는 이 판정 외에 호출하는 곳이 없다.
- `BenefitDetailResponse`의 마지막 컴포넌트가 `Freshness freshness`이고, `of(Benefit, BenefitDetail, Freshness)`가 이를 받는다. `sourceModifiedAt`·`lastSeenAt`은 판정과 무관하게 `Benefit`에서 나온다.
- `BenefitController` 클래스 Javadoc(28행)이 `DEC-FRESHNESS`를 가리킨다. `@Operation`·`OpenApiConfig.DESCRIPTION`에는 판정 설명이 없다.
- 테스트
  - `FreshnessTest`는 48시간 경계 단위 테스트다.
  - `BenefitDetailApiTest`는 판정 시나리오 7개(228–287행)와 `assertFreshness`를 가진다. `run(...)`은 수집 실행 기록을 넣는 도우미이고, `@BeforeEach`에서 `collection_run` 등을 비운다.
  - `ApiDocsTest`의 `상세_응답의_의미가_설명에_있다`(172–173행)와 시각 형식 검사(531행)에 `Freshness` 항목이 있다. `schema(name)`은 스키마가 있어야 통과하는 도우미다.
- 프론트: `frontend/lib/api/types.ts`의 `Freshness` 타입(26–29행)과 `BenefitDetail.freshness`(66행). 다른 파일은 이 타입과 필드를 쓰지 않는다.
- 문서의 현재 문구
  - `requirements.md:93`: 서버가 48시간 기준으로 판정해 응답에서 구분하고, 웹 화면은 경고를 띄우지 않는다(#72).
  - `development.md:114`: `DEC-FRESHNESS(정보 표시 기준)`의 내용과 근거가 판정 기준 중심이다. 출처 수정 시각이 없으면 비워 두는 기준과 결정 0008의 시각 표시 참조는 판정과 별개로 유지된다.
  - `development.md:94`: 정보 표시 기준을 2026-10-03에 정했다는 이력 문단. 이후 결정 변경은 98·100행처럼 이력 문단을 한 줄씩 덧붙인다.
  - `development.md:127`: 미정 항목. 유지한다.
  - `docs/design/README.md:65`: 정보 최신성 행. 경고를 두지 않는다(#72)고만 적혀 있다.

## 설계 결정

1. **쓰지 않게 된 저장소 메서드 `findFirstByStatusInOrderByFinishedAtDesc`를 지운다.** 호출하는 곳이 없어지고, 같은 조회가 다시 필요하면 그때 목적에 맞게 만든다. 대안인 남겨 두기는 쓰이지 않는 쿼리를 유지할 이유가 없다.
2. **응답에 `freshness`가 없다는 것을 테스트로 고정한다.** 상세 API 테스트에서 `body.has("freshness")`가 거짓인지, API 명세 테스트에서 `components.schemas.Freshness`가 없는지 확인한다. 프론트가 이 필드를 읽지 않으므로 하위 호환 기간은 두지 않는다.
3. **수집 실행 기록과 무관하게 상세를 응답하는 테스트는 남긴다.** 판정이 사라져도 "수집이 오래 중단돼도 저장된 상세를 조회한다"(`REQ-DETAIL-01`)는 요구는 그대로이므로, 실행 기록이 없을 때와 `FAILED`만 있을 때를 한 테스트로 확인한다.

## 단계별 구현 계획

1. **플랜 상태**: 착수할 때 이 파일의 `status`를 `in-progress`로 바꾼다.
2. **백엔드 판정 제거** `kr.govbenefit.benefit`
   - `dto/Freshness.java`를 삭제한다.
   - `dto/BenefitDetailResponse`: `freshness` 컴포넌트와 `@Schema`를 지우고 `of(Benefit benefit, BenefitDetail detail)`로 바꾼다.
   - `service/BenefitQueryService.getDetail`: 혜택을 찾고 `BenefitDetailResponse.of(benefit, benefitDetailRepository.findById(benefit.getId()).orElse(null))`를 돌려준다. `LIST_UPDATED`, `collectionRunRepository`, `clock` 필드와 쓰이지 않는 import를 지운다.
   - `controller/BenefitController` 클래스 Javadoc: `DEC-FRESHNESS`를 빼고 "상세(REQ-DETAIL-01)"로 둔다.
   - `collection/repository/CollectionRunRepository`: `findFirstByStatusInOrderByFinishedAtDesc`와 쓰이지 않는 import를 지운다(설계 1).
3. **테스트**
   - `benefit/dto/FreshnessTest.java`를 삭제한다.
   - `BenefitDetailApiTest`
     - 판정 테스트 7개(`목록을_갱신한_실행_종료_후_48시간_직전이면_오래되지_않았다` ~ `목록을_갱신한_실행이_없으면_오래된_정보이고_기준_시각이_없다`)와 `assertFreshness`를 지운다.
     - `수집_실행이_없거나_FAILED만_있어도_저장된_상세를_준다`를 더한다(설계 3). 실행 기록 없이 `detail("FULL")`의 `detail.applyMethod`를 확인하고, `run(CollectionStatus.FAILED, ...)`을 넣은 뒤 다시 확인한다.
     - `로그인_없이_목록과_상세_항목을_조회한다`: 첫 줄의 `run(SUCCEEDED, ...)`을 지우고 `assertThat(body.has("freshness")).isFalse()`를 더한다(설계 2). `sourceModifiedAt`·`lastSeenAt` 검증은 그대로다.
     - 클래스 Javadoc의 판정 설명을 지운다. 쓰이지 않게 된 import(`OffsetDateTime`, `CollectionRun` 등)를 정리한다.
   - `ApiDocsTest`
     - `상세_응답의_의미가_설명에_있다`에서 `Freshness` 두 항목을 지운다.
     - 시각 형식 검사 목록에서 `{"Freshness", "referenceAt"}`를 지운다.
     - `Freshness` 스키마가 명세에 없는지 확인하는 단언을 더한다(설계 2). `spec.path("components").path("schemas").has("Freshness")`가 거짓인지 본다. 위치는 `상세_응답의_의미가_설명에_있다` 끝.
4. **프론트** `frontend/lib/api/types.ts`: `Freshness` 타입과 `BenefitDetail.freshness`를 지운다.
5. **문서**
   - `docs/product/requirements.md:93`(`REQ-DETAIL-01`)을 다음으로 바꾼다.
     > 출처·수집 시점을 확인할 수 있다. 수집이 오래 중단돼도 저장된 상세를 조회할 수 있다. 서버는 오래된 정보를 판정하지 않고, 출처 수정 시각과 수집 시각을 구분해 보여준다(2026-10-26, #79).
   - `docs/product/development.md:114` `DEC-FRESHNESS(정보 표시 기준)`
     - 내용: 서버는 오래된 정보를 판정하지 않는다. 출처 수정 시각과 수집 시각은 구분해 보여주고, 출처 수정 시각이 없으면 비워 둔다(2026-10-26, #79). 처음에는 목록을 갱신한 마지막 수집 실행(`SUCCEEDED`·`PARTIAL`)의 종료에서 48시간이 지나면 오래된 정보로 판정해 응답에 담았다(2026-10-05, #30).
     - 근거: 웹 화면은 판정으로 경고를 띄우지 않기로 했다. 경고는 평소에는 뜨지 않고 수집이 이틀 넘게 실패해야 뜨는데, 상세에 출처 수정 시각과 수집 시각이 이미 있다(2026-10-26, #72). 판정을 쓰는 곳이 없는데 상세 요청마다 수집 실행을 조회하고 API 계약에 남아 있어 없앴다(2026-10-26, #79). 시각 표시는 결정 0008을 따른다.
     - 날짜·참조 칸은 그대로 둔다.
   - `docs/product/development.md:94` 아래 이력 문단을 더한다.
     > DEC-FRESHNESS의 정보 표시 기준에서 서버의 오래된 정보 판정을 2026-10-26에 없앴다. 출처 수정 시각과 수집 시각을 구분해 보여주는 기준은 그대로다. 기록은 #79에서 했다.
   - `docs/product/development.md:127`의 `DEC-FRESHNESS` 미정 항목은 고치지 않는다.
   - `docs/design/README.md:65`: "상세에 출처 수정 시각과 수집 시각을 따로 표시한다. 서버는 오래된 정보를 판정하지 않고 화면에도 경고를 두지 않는다(#72, #79). 목록 항목에 마지막 확인 시각"으로 바꾼다.
6. **마무리**: 검증 명령을 통과시키고, PR 직전 마지막 커밋에서 `status`를 `done`으로 바꾼다.

## 완료 조건 매핑

| 완료 조건 | 구현 단계 | 확인 방법 |
| --- | --- | --- |
| 상세 API 응답에 `freshness` 필드가 없고, `sourceModifiedAt`·`lastSeenAt`은 그대로 나온다. | 2, 3 | `BenefitDetailApiTest.로그인_없이_목록과_상세_항목을_조회한다`, `출처_수정_시각이_없으면_비우고_수집_시각은_따로_준다` |
| 수집 실행이 없거나 `FAILED`만 있어도 저장된 상세를 응답한다. | 2, 3 | `BenefitDetailApiTest.수집_실행이_없거나_FAILED만_있어도_저장된_상세를_준다` |
| OpenAPI 명세에 `Freshness` 스키마가 없다. | 2, 3 | `ApiDocsTest.상세_응답의_의미가_설명에_있다` |
| 프론트 API 타입에 `Freshness`가 없다. | 4 | `grep -rni freshness frontend --exclude-dir=node_modules --exclude-dir=.next`가 비어 있고 `npm run typecheck` 통과 |
| `REQ-DETAIL-01`과 `DEC-FRESHNESS`에 서버 판정을 없앤 날짜와 이슈 번호가 적혀 있고, `DEC-FRESHNESS`의 캐시·알림 미정 항목은 그대로 남아 있다. | 5 | `requirements.md`·`development.md` diff 확인. `development.md`의 아직 결정할 사항 표의 `DEC-FRESHNESS` 행이 diff에 없음 |

## 검증 명령

- `backend/`에서 `./gradlew test`(Docker 필요).
- `frontend/`에서 `npm run lint`, `npm run typecheck`, `npm run build`.
- 남은 참조 확인: `grep -rni freshness backend/src frontend --exclude-dir=node_modules --exclude-dir=.next`의 결과가 부재를 확인하는 테스트 단언 두 줄(`BenefitDetailApiTest`, `ApiDocsTest`)뿐이다.
- 로컬 확인(선택): `local` 프로필로 띄워 `curl -s localhost:8080/api/benefits/<serviceId> | jq 'has("freshness")'`가 `false`이고, `/v3/api-docs`의 `components.schemas`에 `Freshness`가 없다.

## 결정 필요

없음
