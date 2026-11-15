package dev.intel.feed;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resolves a host name to all of its addresses. */
@FunctionalInterface
public interface HostResolver {

    HostResolver SYSTEM = InetAddress::getAllByName;

    InetAddress[] resolve(String host) throws UnknownHostException;
}
