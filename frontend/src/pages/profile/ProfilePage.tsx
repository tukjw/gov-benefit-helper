import { useId, useState, type ReactNode } from "react";
import { Form, useActionData, useLoaderData, useNavigation } from "react-router";
import { FormError } from "@/components/form/FormError";
import { Breadcrumb } from "@/components/layout/Breadcrumb";
import { usePageTitle } from "@/components/usePageTitle";
import type { SidoOption } from "@/lib/api/types";
import { GUEST_NOTICE } from "@/lib/profile/guestProfile";
import { GENDERS } from "@/lib/profile/profileForm";
import { AttributeChips } from "./components/AttributeChips";
import type { ProfileData, ProfileResult } from "./profileRoute";
import styles from "./ProfilePage.module.css";

// 프로필 입력·수정(F3). 모든 항목이 선택이라 단계를 나누지 않고 한 화면의 카드로 묶는다(#74 설계 3).
export function ProfilePage() {
  const { member, options, profile } = useLoaderData() as ProfileData;
  const result = useActionData() as ProfileResult | undefined;
  const submitting = useNavigation().state === "submitting";
  const errors = result?.fieldErrors ?? {};
  usePageTitle(member ? "내 조건" : "조건 입력");
  const today = new Date().toLocaleDateString("sv-SE");

  return (
    <ProfileFrame member={member}>
      <Form method="post" noValidate className={styles.form}>
        {result?.formError && <FormError>{result.formError}</FormError>}
        {Object.keys(errors).length > 0 && <FormError>입력한 값을 확인해 주세요.</FormError>}

        <FieldCard title="기본 정보" errors={[errors.birthDate, errors.gender]}>
          {(describedBy) => (
            <>
              <label className={styles.field}>
                <span className={styles.label}>생년월일</span>
                <input
                  type="date"
                  name="birthDate"
                  max={today}
                  defaultValue={profile?.birthDate ?? ""}
                  className={styles.input}
                  aria-invalid={errors.birthDate ? true : undefined}
                  aria-describedby={errors.birthDate ? describedBy : undefined}
                />
                <span className={styles.hint}>만 나이를 계산해 대상 나이와 비교해요.</span>
              </label>
              <fieldset className={styles.fieldset} aria-describedby={errors.gender ? describedBy : undefined}>
                <legend className={styles.label}>성별</legend>
                <div className={styles.radios}>
                  {[...GENDERS, { value: "", label: "선택 안 함" }].map((option) => (
                    <label key={option.value} className={styles.radio}>
                      <input
                        type="radio"
                        name="gender"
                        value={option.value}
                        defaultChecked={(profile?.gender ?? "") === option.value}
                      />
                      <span>{option.label}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
            </>
          )}
        </FieldCard>

        <FieldCard
          title="거주지"
          description="운영 기관이 있는 지역과 비교하고, 조건 비교 목록의 '내 지역'에 써요. 거주 요건을 판정하지는 않아요."
          errors={[errors.sidoCode, errors.sigunguCode]}
        >
          {(describedBy) => (
            <ResidenceSelect
              sidos={options.sidos}
              defaultSido={profile?.sidoCode ?? ""}
              defaultSigungu={profile?.sigunguCode ?? ""}
              describedBy={errors.sidoCode || errors.sigunguCode ? describedBy : undefined}
            />
          )}
        </FieldCard>

        <FieldCard
          title="생애·직업·건강"
          description="해당하는 것을 모두 골라 주세요. 아무것도 해당하지 않으면 '해당 없음'을 고르세요."
          errors={[errors.lifeAttributes]}
        >
          {(describedBy) => (
            <AttributeChips
              name="lifeAttributes"
              options={options.lifeAttributes}
              defaultValue={profile?.lifeAttributes ?? null}
              describedBy={errors.lifeAttributes ? describedBy : undefined}
            />
          )}
        </FieldCard>

        <FieldCard
          title="가구"
          description="해당하는 것을 모두 골라 주세요. 아무것도 해당하지 않으면 '해당 없음'을 고르세요."
          errors={[errors.householdAttributes]}
        >
          {(describedBy) => (
            <AttributeChips
              name="householdAttributes"
              options={options.householdAttributes}
              defaultValue={profile?.householdAttributes ?? null}
              describedBy={errors.householdAttributes ? describedBy : undefined}
            />
          )}
        </FieldCard>

        <button type="submit" className={`button button-primary ${styles.submit}`} disabled={submitting}>
          {member ? "저장하고 비교하기" : "조건 비교하기"}
        </button>
        {!member && <p className={styles.guestNotice}>{GUEST_NOTICE}</p>}
      </Form>
    </ProfileFrame>
  );
}

export function ProfileFallback() {
  return (
    <ProfileFrame member={false}>
      <p className={styles.loading}>입력 항목을 불러오는 중입니다.</p>
    </ProfileFrame>
  );
}

function ProfileFrame({ member, children }: { member: boolean; children: ReactNode }) {
  return (
    <div className="container">
      <Breadcrumb items={[{ label: "조건 비교", href: "/match" }, { label: member ? "내 조건" : "조건 입력" }]} />
      <div className={styles.page}>
        <div className={styles.heading}>
          <h1 className={styles.title}>{member ? "내 조건" : "조건 입력"}</h1>
          <p className={styles.description}>
            모든 항목은 선택이에요. 입력한 항목만 혜택의 공개된 대상 조건과 비교해요.
          </p>
        </div>
        {children}
      </div>
    </div>
  );
}

// 항목 묶음 카드(시안 '폼 카드'). 서버의 항목별 오류를 카드 아래에 붙이고 입력과 연결한다.
function FieldCard({
  title,
  description,
  errors,
  children,
}: {
  title: string;
  description?: string;
  errors: (string | undefined)[];
  children: (describedBy: string) => ReactNode;
}) {
  const errorId = useId();
  const messages = errors.filter((error): error is string => Boolean(error));
  return (
    <section className={styles.card} aria-labelledby={`${errorId}-title`}>
      <h2 id={`${errorId}-title`} className={styles.cardTitle}>
        {title}
      </h2>
      {description && <p className={styles.cardDescription}>{description}</p>}
      {children(errorId)}
      {messages.length > 0 && (
        <div id={errorId} role="alert" className={styles.error}>
          {messages.map((message) => (
            <p key={message}>{message}</p>
          ))}
        </div>
      )}
    </section>
  );
}

// 시도를 바꾸면 시군구를 비운다. 시군구가 없는 시도(세종·제주)는 시군구 선택을 감춘다.
function ResidenceSelect({
  sidos,
  defaultSido,
  defaultSigungu,
  describedBy,
}: {
  sidos: SidoOption[];
  defaultSido: string;
  defaultSigungu: string;
  describedBy?: string;
}) {
  const [sido, setSido] = useState(defaultSido);
  const [sigungu, setSigungu] = useState(defaultSigungu);
  const sigungus = sidos.find((option) => option.code === sido)?.sigungus ?? [];
  return (
    <div className={styles.selects}>
      <label className={styles.field}>
        <span className={styles.label}>시도</span>
        <select
          name="sidoCode"
          value={sido}
          className={styles.input}
          aria-describedby={describedBy}
          onChange={(event) => {
            setSido(event.target.value);
            setSigungu("");
          }}
        >
          <option value="">선택 안 함</option>
          {sidos.map((option) => (
            <option key={option.code} value={option.code}>
              {option.name}
            </option>
          ))}
        </select>
      </label>
      {sigungus.length > 0 && (
        <label className={styles.field}>
          <span className={styles.label}>시군구</span>
          <select
            name="sigunguCode"
            value={sigungu}
            className={styles.input}
            aria-describedby={describedBy}
            onChange={(event) => setSigungu(event.target.value)}
          >
            <option value="">선택 안 함(시도 전체)</option>
            {sigungus.map((option) => (
              <option key={option.code} value={option.code}>
                {option.name}
              </option>
            ))}
          </select>
        </label>
      )}
    </div>
  );
}
