package kr.govbenefit.profile.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * 생년월일로 계산하는 나이. 나이는 저장하지 않고 계산할 때마다 기준일을 받는다.
 */
public final class Ages {

    private Ages() {
    }

    /**
     * 만 나이. 생일 당일에 한 살 오른다. 2월 29일생은 평년에 3월 1일부터 오른다({@link Period#between}의 동작이며
     * 말일이 없으면 그 달 말일에 기간이 끝나는 민법의 계산과 같다).
     */
    public static int fullAge(LocalDate birthDate, LocalDate today) {
        return Period.between(birthDate, today).getYears();
    }
}
