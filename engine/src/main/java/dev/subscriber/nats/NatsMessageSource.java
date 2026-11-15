package dev.subscriber.nats;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeService;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the configured issue subjects and hands each message to the intake. The callback does nothing but record the message:
 * validating, copying the report and writing the record are quick, and launching is left to the dispatcher, so a slow session never
 * holds up the connection.
 *
 * <p>This is core NATS: a message published while the engine is not running, or not connected, is lost. The connection is kept up
 * for as long as the server is away, and starts even if the server is not there yet.
 */
@Component
public class NatsMessageSource {

    /** A path is short; anything longer is cut before it is even decoded, so a huge message cannot cost memory. */
    static final int MAX_PAYLOAD_BYTES = 8 * 1024;

    /** The name of the connector thread; tests look for it to make sure it ends. */
    static final String CONNECTOR_THREAD = "nats-connector";
    static final Duration INITIAL_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_DELAY = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(NatsMessageSource.class);

    private final SubscriberProperties properties;
    private final IntakeService intake;
    private volatile Connection connection;
    private volatile Thread connector;
    private volatile boolean started;
    private volatile boolean stopping;

    public NatsMessageSource(SubscriberProperties properties, IntakeService intake) {
        this.properties = properties;
        this.intake = intake;
    }

    /** Starts after recovery has sorted out the previous run (recovery runs first), so a message never meets half-recovered state. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    void onReady() {
        start();
    }

    /**
     * Connects on a thread of its own and returns at once. The first connection is retried here, with a delay that grows to half a
     * minute, until it works or the engine stops (the client's own connect-and-keep-trying call cannot be interrupted, so it is not
     * used). Once connected, the client itself reconnects for as long as the server is away.
     */
    void start() {
        started = true;
        NatsLogging logging = new NatsLogging();
        Options options = NatsOptionsFactory.build(properties.nats(), logging, logging);
        connector = Thread.ofPlatform().name(CONNECTOR_THREAD).daemon().start(() -> connectWithRetry(options));
    }

    private void connectWithRetry(Options options) {
        long delayMillis = INITIAL_DELAY.toMillis();
        while (!stopping) {
            try {
                Connection created = Nats.connect(options);
                Dispatcher dispatcher = created.createDispatcher(this::handle);
                for (Subject subject : properties.subjects()) {
                    dispatcher.subscribe(subject.subject());
                }
                connection = created;
                log.info("Subscribed to {} on {}", properties.subjects().stream().map(Subject::subject).toList(), properties.nats().url());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                log.warn("NATS is not reachable yet ({}); trying again in {} s", NatsLogging.printable(e.getMessage()), delayMillis / 1000);
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delayMillis = Math.min(delayMillis * 2, MAX_DELAY.toMillis());
            }
        }
    }

    private void handle(Message message) {
        handle(message.getSubject(), message.getData());
    }

    void handle(String subject, byte[] data) {
        try {
            byte[] bounded = data == null ? new byte[0] : data.length > MAX_PAYLOAD_BYTES ? Arrays.copyOf(data, MAX_PAYLOAD_BYTES) : data;
            intake.accept(subject, new String(bounded, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // The message cannot be put back (core NATS has no acknowledgement); say so, and keep the connection serving the rest.
            log.error("A message on {} could not be recorded and is lost: {}", NatsLogging.printable(subject), e.toString());
        }
    }

    /** The state of the connection: closed when it was never started, disconnected while the first connection is still being made. */
    public Connection.Status status() {
        Connection current = connection;
        if (current != null) {
            return current.getStatus();
        }
        return started ? Connection.Status.DISCONNECTED : Connection.Status.CLOSED;
    }

    @PreDestroy
    void stop() {
        stopping = true;
        Thread thread = connector;
        if (thread != null) {
            thread.interrupt();
        }
        Connection current = connection;
        if (current == null) {
            return;
        }
        try {
            current.drain(Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("The NATS connection did not drain cleanly: {}", e.toString());
        } finally {
            try {
                current.close();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
