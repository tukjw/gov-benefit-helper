"use client";

import { useState } from "react";
import type { SidoOption } from "@/lib/api/types";
import styles from "./RegionSelect.module.css";

// 시도를 바꾸면 그 시도의 시군구만 고를 수 있게 한다. 시군구가 없는 시도(세종·제주)는 시군구 선택을 감춘다.
export function RegionSelect({
  sidos,
  defaultSido,
  defaultSigungu,
}: {
  sidos: SidoOption[];
  defaultSido: string;
  defaultSigungu: string;
}) {
  const [sido, setSido] = useState(defaultSido);
  const [sigungu, setSigungu] = useState(defaultSigungu);
  const sigungus = sidos.find((option) => option.code === sido)?.sigungus ?? [];

  return (
    <div className={styles.selects}>
      <label className={styles.field}>
        <span className={styles.label}>시도</span>
        {/* '전체'면 이름을 빼서 빈 파라미터가 URL에 남지 않게 한다. */}
        <select
          name={sido ? "agencySidoCode" : undefined}
          value={sido}
          className={styles.select}
          onChange={(event) => {
            setSido(event.target.value);
            setSigungu("");
          }}
        >
          <option value="">전체</option>
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
            name={sigungu ? "agencySigunguCode" : undefined}
            value={sigungu}
            className={styles.select}
            onChange={(event) => setSigungu(event.target.value)}
          >
            <option value="">전체</option>
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
