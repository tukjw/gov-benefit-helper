-- 시도의 교육청 코드(#68, 결정 0025). 교육청은 소관기관코드가 지역 코드와 달라, 운영 기관 지역을 정하려면 시도와 잇는 값이 필요하다.
-- 2026-10-01 수집분의 소관기관유형 교육청 16곳이며 시도마다 하나다. 시군구에는 값이 없다.
ALTER TABLE region
    ADD COLUMN education_office_code CHAR(7) NULL AFTER parent_code,
    ADD CONSTRAINT uk_region_education_office UNIQUE (education_office_code);

UPDATE region SET education_office_code = '7010000' WHERE code = '6110000';
UPDATE region SET education_office_code = '7150000' WHERE code = '6260000';
UPDATE region SET education_office_code = '7240000' WHERE code = '6270000';
UPDATE region SET education_office_code = '7310000' WHERE code = '6280000';
UPDATE region SET education_office_code = '7430000' WHERE code = '6300000';
UPDATE region SET education_office_code = '7480000' WHERE code = '6310000';
UPDATE region SET education_office_code = '9300000' WHERE code = '5690000';
UPDATE region SET education_office_code = '7530000' WHERE code = '6410000';
UPDATE region SET education_office_code = '7801000' WHERE code = '6530000';
UPDATE region SET education_office_code = '8000000' WHERE code = '6430000';
UPDATE region SET education_office_code = '8140000' WHERE code = '6440000';
UPDATE region SET education_office_code = '8321000' WHERE code = '6540000';
UPDATE region SET education_office_code = '7140000' WHERE code = '6130000';
UPDATE region SET education_office_code = '8750000' WHERE code = '6470000';
UPDATE region SET education_office_code = '9010000' WHERE code = '6480000';
UPDATE region SET education_office_code = '9290000' WHERE code = '6500000';
