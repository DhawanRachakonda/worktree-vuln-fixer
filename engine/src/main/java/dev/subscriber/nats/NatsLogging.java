package dev.subscriber.nats;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes connection events and errors to the log. Text from the server is shortened and stripped of control characters first, and
 * nothing here ever sees a credential.
 */
final class NatsLogging implements ConnectionListener, ErrorListener {

    private static final Logger log = LoggerFactory.getLogger(NatsLogging.class);

    /** Required by the interface but deprecated there; the four-argument form below carries the same event and more. */
    @Override
    @SuppressWarnings("deprecation")
    public void connectionEvent(Connection connection, Events type) {
        // Logged by the four-argument form, which the client calls as well.
    }

    @Override
    public void connectionEvent(Connection connection, Events type, Long time, String uriDetails) {
        log.info("NATS connection event: {} {}", type, printable(uriDetails));
    }

    @Override
    public void errorOccurred(Connection connection, String error) {
        log.warn("NATS reported an error: {}", printable(error));
    }

    @Override
    public void exceptionOccurred(Connection connection, Exception exception) {
        log.warn("NATS exception: {}", printable("%s: %s".formatted(exception.getClass().getSimpleName(), exception.getMessage())));
    }

    static String printable(String text) {
        if (text == null) {
            return "(none)";
        }
        String line = text.replaceAll("\\p{Cntrl}", " ").strip();
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }
}
