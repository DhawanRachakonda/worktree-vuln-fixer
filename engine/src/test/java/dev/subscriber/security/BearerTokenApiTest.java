package dev.subscriber.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.subscriber.NeverLaunchConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "subscriber.nats.url=nats://127.0.0.1:1",
        "subscriber.access.token=correct-horse-battery"})
@AutoConfigureMockMvc
@Import(NeverLaunchConfig.class)
class BearerTokenApiTest {

    private static final Path DATA = tempDir();
    private static final Path ROOT = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("subscriber-token-test-");
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

    @Test
    void aMissingOrWrongTokenIsAnswered401BeforeAnyQuery() throws Exception {
        String[] urls = {"/api/sessions", "/api/sessions/00000000-0000-0000-0000-000000000000", "/api/sessions/x/events",
                "/actuator/health", "/actuator/info"};
        for (String url : urls) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.error").value("unauthorized"));
            mvc.perform(get(url).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", "Basic correct-horse-battery")).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", "correct-horse-battery")).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", "Bearer correct-horse-batter")).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", "Bearer correct-horse-battery-x")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theRightTokenIsAccepted() throws Exception {
        mvc.perform(get("/api/sessions").header("Authorization", "Bearer correct-horse-battery")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/sessions").header("Authorization", "bearer correct-horse-battery")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").header("Authorization", "Bearer correct-horse-battery"))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    @Test
    void theTwoOrchestratorProbesStayOpenAndShowNoDetails() throws Exception {
        for (String probe : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            String body = mvc.perform(get(probe)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("UP").doesNotContain("components").doesNotContain("nats");
        }
    }

    @Test
    void theTokenNeverAppearsInAResponse() throws Exception {
        for (String url : new String[] {"/api/sessions", "/actuator/health", "/actuator/info"}) {
            String ok = mvc.perform(get(url).header("Authorization", "Bearer correct-horse-battery")).andReturn().getResponse()
                    .getContentAsString();
            assertThat(ok).doesNotContain("correct-horse-battery");
            String denied = mvc.perform(get(url)).andReturn().getResponse().getContentAsString();
            assertThat(denied).doesNotContain("correct-horse-battery");
        }
    }
}
