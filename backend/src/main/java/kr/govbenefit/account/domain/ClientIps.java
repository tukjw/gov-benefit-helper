package kr.govbenefit.account.domain;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 시도 제한에서 같은 요청자로 볼 IP 키(결정 0035). IPv6는 한 사용자가 대역 안의 여러 주소를 쓸 수 있어 앞 64비트로 묶는다.
 */
public final class ClientIps {

    private ClientIps() {
    }

    /**
     * @param remoteAddr {@code getRemoteAddr()}의 값. 운영에서는 Cloudflare가 넣은 요청자 주소다(결정 0034).
     * @return IPv4는 주소 그대로, IPv6는 {@code <앞 64비트>::/64}. 해석할 수 없으면 받은 값 그대로다.
     */
    public static String limitKey(String remoteAddr) {
        // ':'가 없으면 IPv4다. 호스트 이름에는 ':'가 없으므로 아래 해석은 DNS 조회를 하지 않는다.
        if (remoteAddr == null || remoteAddr.indexOf(':') < 0) {
            return remoteAddr;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(remoteAddr);
        } catch (UnknownHostException ex) {
            return remoteAddr;
        }
        // IPv4-mapped 주소(::ffff:a.b.c.d)는 IPv4로 해석된다.
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        byte[] bytes = address.getAddress();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            key.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
        }
        return key.append(":/64").toString();
    }
}
