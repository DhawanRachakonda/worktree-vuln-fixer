package dev.subscriber.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.subscriber.console.ConsoleRunnerConfig.ConsoleStarter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.shell.core.ShellRunner;

class ConsoleStarterTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ShellRunner> provider = mock(ObjectProvider.class);

    @Test
    void theShellRunsOnItsOwnThreadSoStartingTheEngineIsNotBlockedByThePrompt() throws Exception {
        CountDownLatch prompt = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicBoolean ended = new AtomicBoolean();
        ShellRunner runner = args -> {
            thread.set(Thread.currentThread().getName());
            inside.countDown();
            try {
                prompt.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread console = new ConsoleStarter(provider, true).start(runner, () -> ended.set(true));

        // start() has returned while the "prompt" is still open: that is what lets the engine finish starting.
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get()).isEqualTo("operator-console").isNotEqualTo(Thread.currentThread().getName());
        assertThat(ended).as("the engine is not stopped while the prompt is open").isFalse();

        prompt.countDown();
        console.join(10_000);
        assertThat(ended).as("when the shell ends the engine is stopped").isTrue();
    }

    @Test
    void theEngineStopsEvenWhenTheShellFails() throws Exception {
        AtomicBoolean ended = new AtomicBoolean();
        ShellRunner runner = args -> {
            throw new IllegalStateException("terminal gone\u001b[31m");
        };

        new ConsoleStarter(provider, true).start(runner, () -> ended.set(true)).join(10_000);

        assertThat(ended).isTrue();
    }

    @Test
    void theShellIsNotGivenAnyArgument() throws Exception {
        AtomicReference<String[]> given = new AtomicReference<>();

        new ConsoleStarter(provider, true).start(given::set, () -> {
        }).join(10_000);

        assertThat(given.get()).isEmpty();
    }
}
