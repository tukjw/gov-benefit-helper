status: in-progress

# 구현 계획 — #3 서비스 기획·요구사항 문서 추가

브랜치: `docs/3-product-docs` (develop `91cc16e` 기준)

## 목표 및 범위

- 목표: 제품 기능 이슈와 플랜이 참조할 기준 문서를 저장소에 둔다.
- 포함: `docs/README.md`, `docs/product/`의 `overview.md`·`requirements.md`·`data-source.md`·`development.md`, `AGENTS.md`의 문서 안내, `issue-creator` 스킬의 우선순위 라벨 목록.
- 제외: 문서에 적힌 작업 자체(데이터 검증, CI 등), GitHub 라벨·마일스톤 변경(이슈 생성 전에 완료), `issue-creator` 스킬의 마일스톤 지원.

## 확인된 사실과 제약

- 문서 초안은 `develop` 작업 트리에 커밋되지 않은 상태로 작성됐다. 이 브랜치가 `develop`과 같은 커밋에서 분기해 전환 시 그대로 옮겨졌다.
- GitHub 우선순위 라벨은 `priority/high`·`medium`·`low`에서 `priority/p0`·`p1`·`p2`로 이름을 바꿨다. 기존 라벨이 붙은 이슈·PR은 없었다. 마일스톤 `1차 탐색`, `2차 개인화 MVP`, `3차 알림`을 만들었다.
- `issue-creator` 스킬은 라벨 목록이 GitHub 라벨과 같아야 한다고 정한다. `.claude/skills`는 `.agents/skills`의 심볼릭 링크이므로 `.agents/skills/issue-creator/SKILL.md`만 수정한다.
- 코드 변경이 없어 `./gradlew test`는 이 작업의 검증 대상이 아니다.

## 설계 결정

1. **완료 조건의 우선순위는 `P0`·`P1`로 표기한다.** 이슈 우선순위 라벨과 같은 이름을 써서 요구사항과 이슈를 같은 기준으로 읽는다.
2. **성능·캐시 개선은 개발 순서에 미리 두지 않는다.** 관찰되지 않은 문제를 일정에 넣지 않고, 측정으로 확인되면 이슈로 만든다. 개발 순서 문서에는 사실에 근거한 알려진 위험만 둔다.

## 단계별 구현 계획

1. 이 플랜을 추가한다(`status: in-progress`).
2. `docs/README.md`와 `docs/product/` 문서 4개를 추가한다.
3. `AGENTS.md`의 저장소 구성에 `docs/product/` 안내를 추가한다.
4. `.agents/skills/issue-creator/SKILL.md`의 우선순위 라벨 목록을 `priority/p0`·`p1`·`p2`로 바꾼다.
5. 검증 명령을 실행한 뒤 이 플랜의 `status`를 `done`으로 바꾼다.

## 완료 조건 매핑

- [ ] 요구사항마다 고유 ID(`REQ-*`)와 P0·P1 완료 조건이 있다 → 2단계
- [ ] 문서 안의 요구사항 ID, 결정 ID(`DEC-*`), 문서 간 링크가 모두 실제 항목을 가리킨다 → 2단계, 검증 명령 1~3
- [ ] 개발 순서의 각 작업 후보에 관련 요구사항과 선행 조건이 적혀 있다 → 2단계
- [ ] `AGENTS.md`에서 `docs/README.md`를 거쳐 제품 문서를 찾아갈 수 있다 → 3단계
- [ ] 이슈 작성 스킬의 라벨 목록이 GitHub 저장소 라벨과 일치한다 → 4단계, 검증 명령 4

## 검증 명령

저장소 루트에서 bash로 실행한다. 1·2·4는 출력이 없어야 한다.

```bash
# 1. 참조된 요구사항 ID가 모두 정의돼 있는지
comm -23 <(grep -rhoE 'REQ-[A-Z]+-[0-9]+' docs | sort -u) \
  <(grep -hoE '^### REQ-[A-Z]+-[0-9]+' docs/product/requirements.md | cut -c5- | sort -u)

# 2. 참조된 결정 ID가 모두 정의돼 있는지
comm -23 <(grep -rhoE 'DEC-[A-Z]+' docs | sort -u) \
  <(grep -hoE '^\| DEC-[A-Z]+' docs/product/development.md | cut -c3- | sort -u)

# 4. 스킬의 라벨 목록과 GitHub 라벨이 같은지
diff <(gh label list --limit 100 --json name --jq '.[].name' | sort) \
  <(grep -oE '^- `[a-z]+/[a-z0-9-]+`' .agents/skills/issue-creator/SKILL.md | tr -d '`- ' | sort -u)
```

3. `docs/`와 `AGENTS.md`의 상대 링크 대상 파일과 `#앵커`가 가리키는 제목이 존재하는지 확인한다.

## 결정 필요

없음
