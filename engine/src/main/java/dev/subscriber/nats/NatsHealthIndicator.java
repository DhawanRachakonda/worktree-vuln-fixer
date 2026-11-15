package dev.subscriber.nats;

import io.nats.client.Connection;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports the state of the NATS connection: up only while connected. The database is covered by the standard database indicator. */
@Component("nats")
public class NatsHealthIndicator implements HealthIndicator {

    private final Supplier<Connection.Status> status;

    @Autowired
    public NatsHealthIndicator(NatsMessageSource source) {
        this(source::status);
    }

    NatsHealthIndicator(Supplier<Connection.Status> status) {
        this.status = status;
    }

    @Override
    public Health health() {
        Connection.Status current = status.get();
        Health.Builder builder = current == Connection.Status.CONNECTED ? Health.up() : Health.down();
        return builder.withDetail("status", current.name()).build();
    }
}
