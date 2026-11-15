package dev.subscriber.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.subscriber.NeverLaunchConfig;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionState;
import dev.subscriber.store.Worktree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "subscriber.nats.url=nats://127.0.0.1:1"})
@AutoConfigureMockMvc
@Import(NeverLaunchConfig.class)
class SessionApiTest {

    private static final Path DATA = tempDir();
    private static final Path ROOT = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("subscriber-api-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("subscriber.data-dir", () -> DATA.toString().replace('\\', '/'));
        registry.add("subscriber.allowed-roots", () -> ROOT.toString().replace('\\', '/'));
        // Where the runner is headless (Linux), the engine starts only with a permission decision; these tests do not run an agent.
        registry.add("subscriber.copilot.permission-mode", () -> "allow-all-tools");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FixSessionRepository sessions;

    @Autowired
    JdbcClient jdbc;

    private final List<String> ids = new ArrayList<>();

    @BeforeEach
    void seed() {
        // Oldest first: a queued one, one in progress, a done one, an error, a rejected one. The newest is the last.
        ids.add(insert(SessionState.QUEUED, "report-1", null));
        ids.add(insert(SessionState.QUEUED, "report-2", null));
        ids.add(insert(SessionState.REJECTED, "relative.csv", "the report path must be absolute"));
        ids.add(insert(SessionState.QUEUED, "report-4", null));
        sessions.setWorktree(ids.get(0), new Worktree("shop-fix-0a1b2c3d", "C:/work/shop-fix-0a1b2c3d", "appmod/fix-0a1b2c3d",
                "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432"));
        sessions.markStarted(ids.get(0), "window", Instant.parse("2026-11-10T10:01:00Z"), 4242L, Instant.parse("2026-11-10T10:00:59Z"),
                "headsha", false);
        sessions.markFinal(ids.get(0), SessionState.DONE, Instant.parse("2026-11-10T10:30:00Z"), 0, "endsha", "succeeded", null, 2);
        sessions.appendEvent(ids.get(0), Instant.parse("2026-11-10T10:00:00Z"), SessionState.QUEUED, "received");
        sessions.appendEvent(ids.get(0), Instant.parse("2026-11-10T10:01:00Z"), SessionState.IN_PROGRESS, "window started");
        sessions.appendEvent(ids.get(0), Instant.parse("2026-11-10T10:30:00Z"), SessionState.DONE, "succeeded");
        sessions.markFinal(ids.get(1), SessionState.ERROR, Instant.parse("2026-11-10T11:00:00Z"), 7, null, null,
                "line one\nline two with \"quotes\" and \u001b[31m escape");
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM fix_session_event").update();
        jdbc.sql("DELETE FROM fix_session").update();
        ids.clear();
    }

    private String insert(SessionState state, String report, String reason) {
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, state, "securityaudit.issuefixesAgent", "Security audit", report,
                state == SessionState.REJECTED ? null : "C:/work/shop", "security-fixer", "copilot", Instant.parse("2026-11-10T10:00:00Z"),
                reason));
        return id;
    }

    @Test
    void theListIsNewestFirstWithPagingInformation() throws Exception {
        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(3)))
                .andExpect(jsonPath("$.items[3].id").value(ids.get(0)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void theListCanBeFilteredByStateAndTheStateNameIsCaseInsensitive() throws Exception {
        mvc.perform(get("/api/sessions").param("state", "ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(1)))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/sessions").param("state", "queued"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(3)))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/sessions").param("state", "NEEDS_INPUT"))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void thePagesDoNotSkipOrRepeat() throws Exception {
        mvc.perform(get("/api/sessions").param("size", "3").param("page", "0"))
                .andExpect(jsonPath("$.items[*].id", contains(ids.get(3), ids.get(2), ids.get(1))))
                .andExpect(jsonPath("$.total").value(4));
        mvc.perform(get("/api/sessions").param("size", "3").param("page", "1"))
                .andExpect(jsonPath("$.items[*].id", contains(ids.get(0))))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3));
        mvc.perform(get("/api/sessions").param("size", "3").param("page", "2"))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void invalidParametersAreRefusedWithAMessageThatSaysWhatIsAllowed() throws Exception {
        mvc.perform(get("/api/sessions").param("state", "BOGUS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("QUEUED")));
        mvc.perform(get("/api/sessions").param("page", "-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("page")));
        mvc.perform(get("/api/sessions").param("size", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("size")));
        mvc.perform(get("/api/sessions").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions").param("page", "abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("whole number")));
        mvc.perform(get("/api/sessions").param("page", "100001")).andExpect(status().isBadRequest());
    }

    @Test
    void oneSessionShowsItsRecordWithoutTheInternalProcessDetails() throws Exception {
        mvc.perform(get("/api/sessions/" + ids.get(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ids.get(0)))
                .andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.subject").value("securityaudit.issuefixesAgent"))
                .andExpect(jsonPath("$.report").value("report-1"))
                .andExpect(jsonPath("$.repository").value("C:/work/shop"))
                .andExpect(jsonPath("$.agent").value("security-fixer"))
                .andExpect(jsonPath("$.runner").value("window"))
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.headStart").value("headsha"))
                .andExpect(jsonPath("$.headEnd").value("endsha"))
                .andExpect(jsonPath("$.outcome").value("succeeded"))
                .andExpect(jsonPath("$.dirtyAtStart").value(false))
                .andExpect(jsonPath("$.receivedAt").value("2026-11-10T10:00:00Z"))
                .andExpect(jsonPath("$.endedAt").value("2026-11-10T10:30:00Z"))
                .andExpect(jsonPath("$.pid").doesNotExist())
                .andExpect(jsonPath("$.pidStartedAt").doesNotExist())
                .andExpect(jsonPath("$.snapshotPath").doesNotExist());
    }

    @Test
    void aSessionWithAWorktreeShowsItsNameFolderBranchBaseCommitAndCommitCount() throws Exception {
        mvc.perform(get("/api/sessions/" + ids.get(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workName").value("shop-fix-0a1b2c3d"))
                .andExpect(jsonPath("$.workPath").value("C:/work/shop-fix-0a1b2c3d"))
                .andExpect(jsonPath("$.workBranch").value("appmod/fix-0a1b2c3d"))
                .andExpect(jsonPath("$.baseCommit").value("9f8e7d6c5b4a39281706f5e4d3c2b1a098765432"))
                .andExpect(jsonPath("$.fixCommits").value(2));
        mvc.perform(get("/api/sessions").param("state", "DONE"))
                .andExpect(jsonPath("$.items[0].workBranch").value("appmod/fix-0a1b2c3d"))
                .andExpect(jsonPath("$.items[0].fixCommits").value(2));
    }

    @Test
    void aSessionWithoutAWorktreeHasNullWorktreeFields() throws Exception {
        mvc.perform(get("/api/sessions/" + ids.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.workPath").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.workBranch").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.baseCommit").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.fixCommits").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void theEventsOfASessionComeInTheOrderTheyHappened() throws Exception {
        mvc.perform(get("/api/sessions/" + ids.get(0) + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].state", contains("QUEUED", "IN_PROGRESS", "DONE")))
                .andExpect(jsonPath("$[1].detail").value("window started"))
                .andExpect(jsonPath("$[0].occurredAt").value("2026-11-10T10:00:00Z"));
        mvc.perform(get("/api/sessions/" + ids.get(3) + "/events")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anUnknownOrMalformedIdIsNotFound() throws Exception {
        mvc.perform(get("/api/sessions/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no such session"));
        mvc.perform(get("/api/sessions/" + UUID.randomUUID() + "/events")).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/not-an-id")).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/%27%20OR%201=1--")).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/not-an-id/events")).andExpect(status().isNotFound());
    }

    @Test
    void thereIsNoWayToChangeAnythingOverHttp() throws Exception {
        String one = "/api/sessions/" + ids.get(0);
        for (String url : List.of("/api/sessions", one, one + "/events")) {
            mvc.perform(post(url).content("{}").contentType("application/json")).andExpect(status().isMethodNotAllowed());
            mvc.perform(put(url).content("{}").contentType("application/json")).andExpect(status().isMethodNotAllowed());
            mvc.perform(patch(url).content("{}").contentType("application/json")).andExpect(status().isMethodNotAllowed());
            mvc.perform(delete(url)).andExpect(status().isMethodNotAllowed());
        }
        mvc.perform(get(one)).andExpect(jsonPath("$.state").value("DONE"));
        org.assertj.core.api.Assertions.assertThat(sessions.count(null)).isEqualTo(4);
    }

    @Test
    void textFromTheReasonIsEncodedSoItCannotBreakOutOfTheJson() throws Exception {
        mvc.perform(get("/api/sessions/" + ids.get(1)))
                .andExpect(jsonPath("$.reason").value("line one\nline two with \"quotes\" and \u001b[31m escape"))
                .andExpect(jsonPath("$.state").value("ERROR"));
        String raw = mvc.perform(get("/api/sessions/" + ids.get(1))).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(raw).as("no raw control characters in the response")
                .doesNotContain("\n").doesNotContain("\u001b");
        mvc.perform(get("/api/sessions").param("state", "ERROR")).andExpect(jsonPath("$.items[0].reason", not(containsString("\u0000"))));
    }
}
