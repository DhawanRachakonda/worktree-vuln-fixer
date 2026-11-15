package dev.intel.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressPolicyTest {

    private static boolean publicAddress(String literal) throws UnknownHostException {
        // Literals only: no DNS lookup happens for an IP literal.
        return AddressPolicy.PUBLIC_ONLY.permitted(InetAddress.getByName(literal));
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "1.1.1.1", "8.8.8.8", "104.18.2.55", "2606:4700:4700::1111",
            "2a00:1450:4001:81b::200e"})
    void publicAddressesAreAllowed(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "169.253.1.1",
            "11.0.0.1", "192.167.1.1", "198.17.1.1", "198.20.0.1"})
    void addressesJustOutsideReservedRangesAreAllowed(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.255.255.254", "0.0.0.0", "0.1.2.3"})
    void loopbackAndUnspecifiedV4AreRejected(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1",
            "192.168.255.255"})
    void privateV4RangesAreRejected(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"169.254.0.1", "169.254.169.254", "100.100.100.200", "100.64.0.1", "100.127.255.254"})
    void linkLocalAndMetadataAndSharedSpaceAreRejected(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255", "192.0.0.1", "192.0.2.1",
            "192.88.99.1", "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9"})
    void multicastReservedAndDocumentationRangesAreRejected(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "::", "fe80::1", "fe80::abcd:1", "fc00::1", "fd00::1", "fd00:ec2::254",
            "fec0::1", "ff02::1", "2001:db8::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "::10.0.0.1"})
    void nonPublicV6AreRejected(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"::ffff:10.0.0.1", "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:192.168.1.1"})
    void ipv4MappedAddressesAreCheckedAsTheirEmbeddedV4(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"64:ff9b::a00:1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "2002:a00:1::", "2002:7f00:1::",
            "2002:a9fe:a9fe::"})
    void nat64And6to4AreCheckedAsTheirEmbeddedV4(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"::ffff:93.184.216.34", "64:ff9b::5db8:d822", "2002:5db8:d822::"})
    void embeddedPublicV4RemainsAllowed(String literal) throws UnknownHostException {
        assertThat(publicAddress(literal)).as(literal).isTrue();
    }
}
