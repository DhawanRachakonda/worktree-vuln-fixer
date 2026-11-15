package dev.intel.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.intel.store.IngestionOutcome;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DestinationValidatorTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DestinationValidator validator(HostResolver resolver) {
        return new DestinationValidator(List.of("radar.cloudflare.com", "Mirror.Example.org"), resolver,
                AddressPolicy.PUBLIC_ONLY);
    }

    private static final HostResolver PUBLIC = host -> new InetAddress[] {ip("104.18.2.55"), ip("2606:4700::1")};

    @Test
    void allowedHttpsHostWithPublicAddressesPasses() {
        assertThatCode(() -> validator(PUBLIC).validate(
                URI.create("https://radar.cloudflare.com/security/zero-wire/rss"), "feed URL"))
                .doesNotThrowAnyException();
    }

    @Test
    void hostComparisonIgnoresCase() {
        assertThatCode(() -> validator(PUBLIC).validate(URI.create("https://MIRROR.example.ORG/rss"), "feed URL"))
                .doesNotThrowAnyException();
    }

    @Test
    void httpIsRefused() {
        assertThatThrownBy(() -> validator(PUBLIC).validate(URI.create("http://radar.cloudflare.com/rss"), "feed URL"))
                .isInstanceOfSatisfying(FetchRefusedException.class, e -> {
                    assertThat(e.reason()).isEqualTo("refused: feed URL must use https: http://radar.cloudflare.com/rss");
                    assertThat(e.outcome()).isEqualTo(IngestionOutcome.REFUSED);
                });
    }

    @Test
    void otherSchemesAreRefused() {
        assertThatThrownBy(() -> validator(PUBLIC).validate(URI.create("file:///etc/passwd"), "redirect target"))
                .isInstanceOf(FetchRefusedException.class)
                .hasMessageContaining("redirect target must use https");
    }

    @Test
    void hostOutsideTheAllowlistIsRefused() {
        assertThatThrownBy(() -> validator(PUBLIC).validate(URI.create("https://evil.example.net/rss"), "redirect target"))
                .isInstanceOfSatisfying(FetchRefusedException.class, e ->
                        assertThat(e.reason()).isEqualTo(
                                "refused: redirect target host 'evil.example.net' is not in the allowed hosts"));
    }

    @Test
    void lookalikeHostsAreNotMatchedBySuffixOrPrefix() {
        for (String uri : List.of("https://radar.cloudflare.com.evil.net/x", "https://evilradar.cloudflare.com/x",
                "https://radar.cloudflare.com./x", "https://sub.radar.cloudflare.com/x")) {
            assertThatThrownBy(() -> validator(PUBLIC).validate(URI.create(uri), "feed URL"))
                    .as(uri).isInstanceOf(FetchRefusedException.class).hasMessageContaining("not in the allowed hosts");
        }
    }

    @Test
    void ipLiteralHostIsRefusedUnlessAllowlisted() {
        assertThatThrownBy(() -> validator(PUBLIC).validate(URI.create("https://169.254.169.254/latest"), "feed URL"))
                .isInstanceOf(FetchRefusedException.class).hasMessageContaining("not in the allowed hosts");
    }

    @Test
    void credentialsInTheUrlAreRefusedWithoutEchoingThem() {
        assertThatThrownBy(() -> validator(PUBLIC).validate(
                URI.create("https://user:secret@radar.cloudflare.com/rss"), "feed URL"))
                .isInstanceOfSatisfying(FetchRefusedException.class, e ->
                        assertThat(e.reason()).contains("must not contain credentials").doesNotContain("secret"));
    }

    @Test
    void urlWithoutAHostIsRefused() {
        assertThatThrownBy(() -> validator(PUBLIC).validate(URI.create("https:///rss"), "feed URL"))
                .isInstanceOf(FetchRefusedException.class).hasMessageContaining("has no host");
    }

    @Test
    void allowedHostResolvingToAPrivateAddressIsRefused() {
        DestinationValidator v = validator(host -> new InetAddress[] {ip("10.0.0.5")});

        assertThatThrownBy(() -> v.validate(URI.create("https://radar.cloudflare.com/rss"), "redirect target"))
                .isInstanceOfSatisfying(FetchRefusedException.class, e ->
                        assertThat(e.reason()).isEqualTo(
                                "refused: host 'radar.cloudflare.com' resolves to a non-public address 10.0.0.5"));
    }

    @Test
    void oneNonPublicAddressAmongPublicOnesIsEnoughToRefuse() {
        DestinationValidator v = validator(host -> new InetAddress[] {ip("104.18.2.55"), ip("169.254.169.254")});

        assertThatThrownBy(() -> v.validate(URI.create("https://radar.cloudflare.com/rss"), "feed URL"))
                .isInstanceOf(FetchRefusedException.class)
                .hasMessageContaining("169.254.169.254");
    }

    @Test
    void anUnresolvableHostIsAFailureNotARefusal() {
        DestinationValidator v = validator(host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> v.validate(URI.create("https://radar.cloudflare.com/rss"), "feed URL"))
                .isInstanceOfSatisfying(FetchFailedException.class, e -> {
                    assertThat(e.reason()).isEqualTo("failed: cannot resolve host 'radar.cloudflare.com'");
                    assertThat(e.outcome()).isEqualTo(IngestionOutcome.FAILED);
                });
    }

    @Test
    void aHostThatResolvesToNothingIsAFailure() {
        DestinationValidator v = validator(host -> new InetAddress[0]);

        assertThatThrownBy(() -> v.validate(URI.create("https://radar.cloudflare.com/rss"), "feed URL"))
                .isInstanceOf(FetchFailedException.class).hasMessageContaining("resolved to no addresses");
    }

    @Test
    void aCustomPolicyCanPermitLoopbackForTests() {
        DestinationValidator v = new DestinationValidator(List.of("localhost"),
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()},
                address -> address.isLoopbackAddress() || AddressPolicy.PUBLIC_ONLY.permitted(address));

        assertThatCode(() -> v.validate(URI.create("https://localhost:8443/rss"), "feed URL"))
                .doesNotThrowAnyException();
    }
}
