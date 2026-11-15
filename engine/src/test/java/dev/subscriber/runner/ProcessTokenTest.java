package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProcessTokenTest {

    private static ProcessToken own() {
        ProcessHandle me = ProcessHandle.current();
        return new ProcessToken(me.pid(), me.info().startInstant().orElseThrow());
    }

    @Test
    void aLiveProcessWithItsOwnStartTimeIsAlive() {
        assertThat(own().isAlive()).isTrue();
    }

    @Test
    void aStartTimeStoredWithLessPrecisionStillMatches() {
        ProcessToken own = own();
        Instant coarse = own.startedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        assertThat(new ProcessToken(own.pid(), coarse).isAlive()).isTrue();
        assertThat(new ProcessToken(own.pid(), own.startedAt().plusSeconds(1)).isAlive()).isTrue();
    }

    @Test
    void theSameIdWithAnotherStartTimeIsADifferentProcess() {
        ProcessToken own = own();
        assertThat(new ProcessToken(own.pid(), own.startedAt().plusSeconds(3600)).isAlive()).isFalse();
        assertThat(new ProcessToken(own.pid(), own.startedAt().minusSeconds(3600)).isAlive()).isFalse();
        assertThat(new ProcessToken(own.pid(), null).isAlive()).isFalse();
    }

    @Test
    void aProcessThatHasEndedIsNotAliveAndNeitherIsAnIdThatDoesNotExist() throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd.exe", "/c", "exit", "0") : List.of("sh", "-c", "exit 0");
        Process p = new ProcessBuilder(command).start();
        ProcessToken token = new ProcessToken(p.pid(), p.info().startInstant().orElse(Instant.now()));
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(token.isAlive()).isFalse();
        assertThat(new ProcessToken(Integer.MAX_VALUE - 7, Instant.now()).isAlive()).isFalse();
    }
}
