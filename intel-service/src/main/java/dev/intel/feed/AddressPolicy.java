package dev.intel.feed;

import java.net.InetAddress;

/** Decides whether the fetcher may connect to a resolved address. */
@FunctionalInterface
public interface AddressPolicy {

    /** Only globally routable addresses: no loopback, private, link-local, metadata, shared or reserved ranges. */
    AddressPolicy PUBLIC_ONLY = AddressPolicy::isPublic;

    boolean permitted(InetAddress address);

    static boolean isPublic(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) {
            return publicV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
        }
        return publicV6(b);
    }

    private static boolean publicV4(int first, int second, int third) {
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false; // "this" network, private, loopback, multicast, reserved and broadcast
        }
        if (first == 100 && (second & 0xC0) == 64) {
            return false; // 100.64.0.0/10 shared address space (includes 100.100.100.200)
        }
        if (first == 169 && second == 254) {
            return false; // link-local, includes the 169.254.169.254 metadata service
        }
        if (first == 172 && (second & 0xF0) == 16) {
            return false; // 172.16.0.0/12
        }
        if (first == 192 && second == 168) {
            return false;
        }
        if (first == 192 && second == 0 && (third == 0 || third == 2)) {
            return false; // protocol assignments, TEST-NET-1
        }
        if (first == 192 && second == 88 && third == 99) {
            return false; // deprecated 6to4 relay anycast
        }
        if (first == 198 && (second & 0xFE) == 18) {
            return false; // benchmarking
        }
        if (first == 198 && second == 51 && third == 100) {
            return false; // TEST-NET-2
        }
        return !(first == 203 && second == 0 && third == 113); // TEST-NET-3
    }

    private static boolean publicV6(byte[] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        if ((b0 & 0xFE) == 0xFC) {
            return false; // fc00::/7 unique local, includes fd00:ec2::254
        }
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
            return false; // fe80::/10 link-local
        }
        if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) {
            return false; // fec0::/10 deprecated site-local
        }
        if (b0 == 0xFF) {
            return false; // multicast
        }
        if (allZero(b, 0, 12)) {
            // ::, ::1 and IPv4-compatible ::a.b.c.d (also covers an unspecified or loopback address)
            return false;
        }
        if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12)) {
            return publicV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff); // NAT64 64:ff9b::/96
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return publicV4(b[2] & 0xff, b[3] & 0xff, b[4] & 0xff); // 6to4 2002::/16
        }
        if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0) {
            return false; // Teredo 2001::/32
        }
        return !(b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8); // documentation
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
