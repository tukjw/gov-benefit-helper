-- 최소 프로필(#49, DEC-PROFILE). 사용자당 한 행이며 모든 값은 선택 입력이라 NULL이 미입력이다.
-- 시군구가 시도에 속하는지는 애플리케이션이 검증한다. FK는 각 코드가 region에 있는지만 본다.
CREATE TABLE user_profile (
    user_id      BIGINT      NOT NULL,
    birth_date   DATE        NULL,
    gender       VARCHAR(10) NULL,
    sido_code    CHAR(7)     NULL,
    sigungu_code CHAR(7)     NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_profile_sido FOREIGN KEY (sido_code) REFERENCES region (code),
    CONSTRAINT fk_user_profile_sigungu FOREIGN KEY (sigungu_code) REFERENCES region (code),
    CONSTRAINT ck_user_profile_gender CHECK (gender IN ('MALE', 'FEMALE'))
);

-- 생애·가구 속성(결정 0022). 지원조건 코드로 저장하며 해당사항없음(JA0322, JA0410)도 한 행이다. 그룹에 행이 없으면 미입력이다.
CREATE TABLE user_profile_attribute (
    user_id        BIGINT  NOT NULL,
    condition_code CHAR(6) NOT NULL,
    PRIMARY KEY (user_id, condition_code),
    CONSTRAINT fk_user_profile_attribute_profile FOREIGN KEY (user_id) REFERENCES user_profile (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_user_profile_attribute_code CHECK (condition_code IN (
        'JA0301', 'JA0302', 'JA0303', 'JA0313', 'JA0314', 'JA0315', 'JA0316', 'JA0317', 'JA0318', 'JA0319',
        'JA0320', 'JA0322', 'JA0326', 'JA0327', 'JA0328', 'JA0329', 'JA0330',
        'JA0401', 'JA0402', 'JA0403', 'JA0404', 'JA0410', 'JA0411', 'JA0412', 'JA0413', 'JA0414'))
);
