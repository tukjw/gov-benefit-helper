package kr.govbenefit.region.domain;

/**
 * 혜택 운영 기관의 소재 지역(결정 0025). 대상 지역·거주 요건이 아니다.
 *
 * @param sidoCode    소재 시도. 지역이 없거나 모르면 null이다.
 * @param sigunguCode 소재 시군구. 시도 기관이면 null이다.
 */
public record AgencyRegion(Kind kind, String sidoCode, String sigunguCode) {

    public enum Kind {
        /** 지역이 없는 기관(중앙행정기관, 공공기관) */
        NONE,
        /** 시도 또는 시군구 기관 */
        LOCATED,
        /** 지자체 유형인데 코드를 찾지 못했거나 처음 보는 유형이다. ‘전국’으로 채우지 않는다. */
        UNKNOWN
    }

    public static final AgencyRegion NONE = new AgencyRegion(Kind.NONE, null, null);
    public static final AgencyRegion UNKNOWN = new AgencyRegion(Kind.UNKNOWN, null, null);

    public static AgencyRegion sido(String sidoCode) {
        return new AgencyRegion(Kind.LOCATED, sidoCode, null);
    }

    public static AgencyRegion sigungu(String sidoCode, String sigunguCode) {
        return new AgencyRegion(Kind.LOCATED, sidoCode, sigunguCode);
    }

    /**
     * 조건 비교 목록의 ‘내 지역’ 범위 안인지(DEC-MATCH). 지역 없는 기관은 들고 지역 모름은 들지 않는다.
     * 시도 기관은 그 시도 거주자에게, 시군구 기관은 같은 시군구 거주자와 그 시도까지만 입력한 거주자에게 든다.
     * 판정과 달리 시도만 입력한 거주자의 도내 시군구 기관도 범위에 넣는다.
     *
     * @param residenceSidoCode    거주 시도. null이면 범위를 정할 수 없어 호출하지 않는다.
     * @param residenceSigunguCode 거주 시군구. 미입력이면 null이다.
     */
    public boolean isWithin(String residenceSidoCode, String residenceSigunguCode) {
        return switch (kind) {
            case NONE -> true;
            case UNKNOWN -> false;
            case LOCATED -> sidoCode.equals(residenceSidoCode)
                    && (sigunguCode == null || residenceSigunguCode == null
                    || sigunguCode.equals(residenceSigunguCode));
        };
    }
}
