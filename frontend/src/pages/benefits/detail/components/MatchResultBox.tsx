import { Suspense } from "react";
import { Await, Link } from "react-router";
import { Badge } from "@/components/ui/Badge";
import type {
  AttributeOption,
  Gender,
  MatchItemKey,
  MatchResponse,
  ProfileOptionsResponse,
  RegionValue,
} from "@/lib/api/types";
import {
  ITEM_KEYS,
  ITEM_LABELS,
  NOTICE_TEXTS,
  reasonText,
  RESULT_DESCRIPTIONS,
  RESULT_LABELS,
  RESULT_TONES,
  STATUS_LABELS,
  UNCOMPARED_LABELS,
} from "@/lib/match/labels";
import { attributeLabel, GENDERS } from "@/lib/profile/profileForm";
import type { DetailMatch } from "../benefitDetailLoader";
import { ExternalLink } from "./ExternalLink";
import styles from "./MatchResultBox.module.css";

const STATUS_TONES = { MET: "success", NEEDS_CHECK: "warning", NOT_MET: "neutral", NOT_EVALUATED: "neutral" } as const;

// 상세의 조건 비교 결과 박스(시안 '자격 판정 박스'를 F3의 조건 비교 결과로 바꾼 것). 상세 본문을 먼저 그리고
// 결과는 받는 대로 채운다. 받지 못해도 상세 본문은 그대로 둔다.
export function MatchResultBox({ match }: { match: Promise<DetailMatch> }) {
  return (
    <section className={styles.box} aria-labelledby="match-title">
      <h2 id="match-title" className={styles.title}>
        조건 비교 결과
      </h2>
      <Suspense fallback={<p className={styles.muted}>조건 비교 결과를 불러오는 중입니다.</p>}>
        <Await resolve={match} errorElement={<p className={styles.muted}>조건 비교 결과를 불러오지 못했어요.</p>}>
          {(resolved: DetailMatch) => <MatchResultBody {...resolved} />}
        </Await>
      </Suspense>
    </section>
  );
}

function MatchResultBody({ match, options, entered }: DetailMatch) {
  const mismatch = match.result === "MISMATCH";
  const keys: MatchItemKey[] = mismatch ? ["personalTarget"] : ITEM_KEYS;
  // 해당의 설명은 NOT_FINAL 안내와 같고, 지역 안내는 운영 기관 지역 항목에 붙이므로 겹쳐 보이지 않게 한다.
  const notices = match.notices.filter(
    (notice) => !(notice === "NOT_FINAL" && match.result === "MATCH") && notice !== "AGENCY_REGION_BASED",
  );

  return (
    <>
      <div className={`${styles.result} ${styles[match.result]}`}>
        <Badge tone={RESULT_TONES[match.result]}>{RESULT_LABELS[match.result]}</Badge>
        <p>{RESULT_DESCRIPTIONS[match.result]}</p>
      </div>

      {!entered && !mismatch && (
        <p className={styles.prompt}>
          입력한 조건이 없어 대부분 확인 필요로 보여요.{" "}
          <Link to="/profile" className="text-link">
            조건 입력하기
          </Link>
        </p>
      )}

      <ul className={styles.items}>
        {keys.map((key) => {
          const item = match.items[key];
          const values = comparedValues(key, match, options);
          return (
            <li key={key} className={styles.item}>
              <div className={styles.itemHead}>
                <strong>{ITEM_LABELS[key]}</strong>
                <Badge tone={STATUS_TONES[item.status]}>{STATUS_LABELS[item.status]}</Badge>
              </div>
              <p>{reasonText(key, item.reason)}</p>
              {values && <p className={styles.values}>{values}</p>}
              {key === "agencyRegion" && (
                <p className={styles.values}>{NOTICE_TEXTS.AGENCY_REGION_BASED}</p>
              )}
            </li>
          );
        })}
      </ul>

      {notices.length > 0 && (
        <ul className={styles.notices}>
          {notices.map((notice) => (
            <li key={notice}>{NOTICE_TEXTS[notice]}</li>
          ))}
        </ul>
      )}

      {!mismatch && (
        <p className={styles.uncompared}>
          <strong>비교하지 않은 조건</strong>{" "}
          {match.uncomparedConditions.map((condition) => UNCOMPARED_LABELS[condition]).join(", ")}. 지원 대상과 선정
          기준 원문을 함께 확인해 주세요.
        </p>
      )}

      <div className={styles.actions}>
        {match.source.guideUrl && (
          <ExternalLink href={match.source.guideUrl} className="button">
            공식 안내에서 확인하기
          </ExternalLink>
        )}
        <Link to="/profile" className="button">
          조건 수정
        </Link>
      </div>
    </>
  );
}

// 항목에서 비교한 값(혜택 쪽과 입력한 값). 비교하지 않은 항목은 없다.
function comparedValues(key: MatchItemKey, match: MatchResponse, options: ProfileOptionsResponse): string | null {
  const { items } = match;
  switch (key) {
    case "personalTarget":
      return items.personalTarget.userType ? `대상 구분: ${items.personalTarget.userType.split("||").join(", ")}` : null;
    case "age": {
      const { ageStart, ageEnd, profileAge } = items.age;
      // 시작 0은 하한 없음, 종료 120은 상한 없음이다(DEC-MATCH).
      const lower = ageStart !== null && ageStart > 0 ? `${ageStart}세` : null;
      const upper = ageEnd !== null && ageEnd < 120 ? `${ageEnd}세` : null;
      const range =
        ageStart === null && ageEnd === null
          ? null
          : lower || upper
            ? `대상 ${lower ?? ""}~${upper ?? ""}`
            : "대상 나이 제한 없음";
      return join([range, profileAge !== null ? `내 나이 만 ${profileAge}세` : null]);
    }
    case "gender": {
      const { allowedGenders, profileGender } = items.gender;
      return join([
        allowedGenders && allowedGenders.length > 0 ? `대상 ${allowedGenders.map(genderLabel).join(", ")}` : null,
        profileGender ? `내 성별 ${genderLabel(profileGender)}` : null,
      ]);
    }
    case "lifeAttributes":
    case "householdAttributes": {
      const { includedAttributes, profileAttributes } = items[key];
      const choices = options[key];
      return join([
        includedAttributes && includedAttributes.length > 0 ? `대상 ${labels(includedAttributes, choices)}` : null,
        profileAttributes ? `내 입력 ${labels(profileAttributes, choices)}` : null,
      ]);
    }
    case "agencyRegion": {
      const { agencyRegion, profileRegion } = items.agencyRegion;
      return join([
        agencyRegion ? `운영 기관 ${regionLabel(agencyRegion)}` : null,
        profileRegion ? `내 거주지 ${regionLabel(profileRegion)}` : null,
      ]);
    }
  }
}

function join(parts: (string | null)[]): string | null {
  const present = parts.filter((part): part is string => Boolean(part));
  return present.length > 0 ? present.join(" · ") : null;
}

function genderLabel(gender: Gender): string {
  return GENDERS.find((option) => option.value === gender)?.label ?? gender;
}

function labels(values: string[], choices: AttributeOption[]): string {
  return values
    .map((value) => {
      const choice = choices.find((item) => item.value === value);
      return choice ? attributeLabel(choice) : value;
    })
    .join(", ");
}

function regionLabel(region: RegionValue): string {
  return [region.sido.name, region.sigungu?.name].filter(Boolean).join(" ");
}
