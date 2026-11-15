package dev.subscriber.nats;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import io.nats.client.Connection;
import io.nats.client.Options;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

class NatsOptionsAndHealthTest {

    @TempDir
    Path temp;

    private static Options build(SubscriberProperties.Nats nats) {
        NatsLogging logging = new NatsLogging();
        return NatsOptionsFactory.build(nats, logging, logging);
    }

    @Test
    void theServerIsTheConfiguredUrlAndTheClientNeverStopsReconnecting() {
        Options options = build(new SubscriberProperties.Nats("nats://nats.example:4333", null, null, null, null));
        assertThat(options.getServers()).containsExactly(URI.create("nats://nats.example:4333"));
        assertThat(options.getMaxReconnect()).isEqualTo(-1);
        assertThat(options.getConnectionName()).isEqualTo("subscriber-engine");
        assertThat(options.getTokenChars()).isNull();
        assertThat(options.getUsernameChars()).isNull();
        assertThat(options.getAuthHandler()).isNull();
    }

    @Test
    void aTokenIsUsedAsTheToken() {
        Options options = build(new SubscriberProperties.Nats("nats://h:4222", "tok-123", null, null, null));
        assertThat(new String(options.getTokenChars())).isEqualTo("tok-123");
        assertThat(options.getUsernameChars()).isNull();
    }

    @Test
    void aUserAndPasswordAreUsedAsUserInfo() {
        Options options = build(new SubscriberProperties.Nats("nats://h:4222", null, "alice", "pw-456", null));
        assertThat(new String(options.getUsernameChars())).isEqualTo("alice");
        assertThat(new String(options.getPasswordChars())).isEqualTo("pw-456");
        assertThat(options.getTokenChars()).isNull();
    }

    @Test
    void aCredentialsFileIsUsedThroughTheAuthHandler() throws Exception {
        Path creds = Files.writeString(temp.resolve("x.creds"), "creds");
        Options options = build(new SubscriberProperties.Nats("nats://h:4222", null, null, null, creds.toString()));
        assertThat(options.getAuthHandler()).isNotNull();
        assertThat(options.getTokenChars()).isNull();
    }

    @Test
    void healthIsUpOnlyWhileConnected() {
        assertThat(new NatsHealthIndicator(() -> Connection.Status.CONNECTED).health().getStatus()).isEqualTo(Status.UP);
        for (Connection.Status other : new Connection.Status[] {Connection.Status.DISCONNECTED, Connection.Status.RECONNECTING,
                Connection.Status.CLOSED, Connection.Status.CONNECTING}) {
            var health = new NatsHealthIndicator(() -> other).health();
            assertThat(health.getStatus()).as(other.name()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("status", other.name());
        }
    }
}
