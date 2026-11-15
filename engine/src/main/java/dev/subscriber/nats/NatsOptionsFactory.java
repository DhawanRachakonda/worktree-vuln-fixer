package dev.subscriber.nats;

import dev.subscriber.config.SubscriberProperties;
import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.Options;

/**
 * Builds the connection options from the settings. The credentials go straight into the options and are never written anywhere else:
 * not to a log, not into an error message (the URL itself may not contain any, see the settings).
 */
final class NatsOptionsFactory {

    private NatsOptionsFactory() {
    }

    static Options build(SubscriberProperties.Nats nats, ConnectionListener connectionListener, ErrorListener errorListener) {
        return builder(nats, connectionListener, errorListener).build();
    }

    /** The same options before they are built, for a caller that needs a setting of its own (the console command that publishes). */
    static Options.Builder builder(SubscriberProperties.Nats nats, ConnectionListener connectionListener, ErrorListener errorListener) {
        Options.Builder builder = new Options.Builder()
                .server(nats.url())
                .connectionName("subscriber-engine")
                // Keep trying for as long as the server is away; the engine is useful again the moment it comes back.
                .maxReconnects(-1)
                .connectionListener(connectionListener)
                .errorListener(errorListener);
        if (nats.token() != null) {
            builder.token(nats.token().toCharArray());
        } else if (nats.user() != null) {
            builder.userInfo(nats.user().toCharArray(), nats.password().toCharArray());
        } else if (nats.credentialsFile() != null) {
            builder.credentialPath(nats.credentialsFile());
        }
        return builder;
    }
}
