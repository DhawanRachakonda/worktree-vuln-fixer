package dev.intel.feed;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a destination before it is contacted: https only, no credentials, host on the allowlist, and every address
 * the host resolves to acceptable under the {@link AddressPolicy}. It is applied to the configured feed URL and again
 * to the target of every redirect.
 *
 * <p>The JDK HTTP client resolves the host again when it connects, so the validated address cannot be pinned. The
 * window this leaves exists only if DNS for an allowlisted host is itself hostile.
 */
public final class DestinationValidator {

    private final Set<String> allowedHosts;
    private final HostResolver resolver;
    private final AddressPolicy policy;

    public DestinationValidator(List<String> allowedHosts, HostResolver resolver, AddressPolicy policy) {
        this.allowedHosts = Set.copyOf(allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList());
        this.resolver = resolver;
        this.policy = policy;
    }

    public static DestinationValidator forProduction(List<String> allowedHosts) {
        return new DestinationValidator(allowedHosts, HostResolver.SYSTEM, AddressPolicy.PUBLIC_ONLY);
    }

    /**
     * @param what what is being checked, used in the reason ("feed URL", "redirect target")
     * @throws FetchRefusedException if policy forbids the destination
     * @throws FetchFailedException  if the host cannot be resolved
     */
    public void validate(URI uri, String what) throws FetchException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new FetchRefusedException(what + " must use https: " + Safe.text(uri.toString(), 200));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new FetchRefusedException(what + " has no host: " + Safe.text(uri.toString(), 200));
        }
        if (uri.getUserInfo() != null) {
            throw new FetchRefusedException(what + " must not contain credentials (host '" + Safe.text(host, 100) + "')");
        }
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new FetchRefusedException(what + " host '" + Safe.text(host, 100) + "' is not in the allowed hosts");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new FetchFailedException("cannot resolve host '" + host + "'", e);
        }
        if (addresses.length == 0) {
            throw new FetchFailedException("host '" + host + "' resolved to no addresses");
        }
        for (InetAddress address : addresses) {
            if (!policy.permitted(address)) {
                throw new FetchRefusedException(
                        "host '" + host + "' resolves to a non-public address " + address.getHostAddress());
            }
        }
    }
}
