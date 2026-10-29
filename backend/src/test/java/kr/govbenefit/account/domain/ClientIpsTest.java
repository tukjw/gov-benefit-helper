package kr.govbenefit.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientIpsTest {

    @Test
    void IPv4는_주소_그대로다() {
        assertThat(ClientIps.limitKey("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void IPv6는_앞_64비트로_묶는다() {
        assertThat(ClientIps.limitKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientIps.limitKey("2001:0db8:0001:0002:0000:0000:0000:0001")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientIps.limitKey("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3::/64");
        assertThat(ClientIps.limitKey("::1")).isEqualTo("0:0:0:0::/64");
    }

    @Test
    void IPv4_mapped_주소는_IPv4로_본다() {
        assertThat(ClientIps.limitKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void 해석할_수_없으면_받은_값_그대로다() {
        assertThat(ClientIps.limitKey("not:an:address")).isEqualTo("not:an:address");
        assertThat(ClientIps.limitKey(null)).isNull();
    }
}
