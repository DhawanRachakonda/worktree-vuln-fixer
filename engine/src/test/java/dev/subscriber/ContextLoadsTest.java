package dev.subscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.config.SubscriberProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

class ContextLoadsTest {

    @TempDir
    Path dataDir;

    @TempDir
    Path root;

    @Test
    void theApplicationStartsOnAFreshH2FileInTheDataDirectory() {
        try (ConfigurableApplicationContext context = start()) {
            Integer one = context.getBean(JdbcClient.class).sql("select 1").query(Integer.class).single();
            assertThat(one).isEqualTo(1);
            assertThat(context.getBean(SubscriberProperties.class).subjects()).hasSize(3);
            assertThat(context.getBean(SubscriberProperties.class).subjects()).as("worktrees unless told otherwise")
                    .extracting(SubscriberProperties.Subject::isolation).containsOnly(SubscriberProperties.Isolation.WORKTREE);
            assertThat(context.getBean(SubscriberProperties.class).subjects()).extracting(SubscriberProperties.Subject::agent)
                    .as("the audit subject goes to the fixer by default, the other two to the front door")
                    .containsExactly("security-fixer", "appmod-harness", "appmod-harness");
        }
        assertThat(Files.exists(dataDir.resolve("subscriber.mv.db"))).isTrue();
    }

    @Test
    void everyDocumentedEnvironmentNameReachesItsSetting() {
        // The names in application.yml are plain placeholders, so command-line arguments stand in for environment variables.
        SpringApplication app = new SpringApplication(SubscriberEngineApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                "--SUBSCRIBER_DATA_DIR=" + dataDir.toString().replace('\\', '/'),
                "--SUBSCRIBER_ALLOWED_ROOTS=" + root.toString().replace('\\', '/'),
                "--SUBSCRIBER_RUNNER=headless", "--SUBSCRIBER_MAX_PARALLEL=2",
                "--SUBSCRIBER_COPILOT_COMMAND=copilot-test", "--SUBSCRIBER_COPILOT_PERMISSION_MODE=allow-all-tools",
                "--NATS_URL=nats://nats.example:4333", "--NATS_TOKEN=a-token-value",
                "--SUBSCRIBER_ACCESS_TOKEN=an-access-token-long-enough",
                "--SUBSCRIBER_ISOLATION=none", "--SUBSCRIBER_AUDIT_AGENT=another-agent", "--SUBSCRIBER_COVERITY_REPOSITORY=" + root.toString().replace('\\', '/'))) {
            SubscriberProperties p = context.getBean(SubscriberProperties.class);
            assertThat(p.runner()).isEqualTo(SubscriberProperties.Runner.HEADLESS);
            assertThat(p.maxParallel()).isEqualTo(2);
            assertThat(p.isolation()).isEqualTo(SubscriberProperties.Isolation.NONE);
            assertThat(p.subjects()).extracting(SubscriberProperties.Subject::isolation)
                    .containsOnly(SubscriberProperties.Isolation.NONE);
            assertThat(p.copilot().command()).isEqualTo("copilot-test");
            assertThat(p.copilot().permissionMode()).isEqualTo(SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS);
            assertThat(p.nats().url()).isEqualTo("nats://nats.example:4333");
            assertThat(p.nats().token()).isEqualTo("a-token-value");
            assertThat(p.access().token()).isEqualTo("an-access-token-long-enough");
            assertThat(p.subjects()).extracting(SubscriberProperties.Subject::agent)
                    .as("the variable overrides the default of the audit subject only")
                    .containsExactly("another-agent", "appmod-harness", "appmod-harness");
            assertThat(p.subjects().get(1).repository()).isEqualTo(root.toString().replace('\\', '/'));
        }
    }

    @Test
    void theApplicationDoesNotStartWithoutAnAllowedRoot() {
        SpringApplication app = new SpringApplication(SubscriberEngineApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> app.run("--subscriber.data-dir=" + dataDir.toString().replace('\\', '/')).close())
                .hasStackTraceContaining("subscriber.allowed-roots is required");
    }

    private ConfigurableApplicationContext start() {
        // Command-line arguments outrank application.yml, so the temporary directories really are used.
        SpringApplication app = new SpringApplication(SubscriberEngineApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run("--subscriber.data-dir=" + dataDir.toString().replace('\\', '/'),
                "--subscriber.allowed-roots=" + root.toString().replace('\\', '/'),
                "--subscriber.copilot.permission-mode=allow-all-tools");
    }

    @Test
    void thePermissionListEnvironmentNamesReachTheirSettings() {
        SpringApplication app = new SpringApplication(SubscriberEngineApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                "--SUBSCRIBER_DATA_DIR=" + dataDir.toString().replace('\\', '/'),
                "--SUBSCRIBER_ALLOWED_ROOTS=" + root.toString().replace('\\', '/'),
                "--SUBSCRIBER_COPILOT_PERMISSION_MODE=allow-list",
                "--SUBSCRIBER_COPILOT_ALLOWED_TOOLS=shell(git:*),write(C:/work/**)",
                "--SUBSCRIBER_COPILOT_DENIED_TOOLS=shell(curl:*),shell(git push:*)")) {
            SubscriberProperties.Copilot copilot = context.getBean(SubscriberProperties.class).copilot();

            assertThat(copilot.permissionMode()).isEqualTo(SubscriberProperties.PermissionMode.ALLOW_LIST);
            assertThat(copilot.allowedTools()).containsExactly("shell(git:*)", "write(C:/work/**)");
            assertThat(copilot.deniedTools()).containsExactly("shell(curl:*)", "shell(git push:*)");
        }
    }

    @Test
    void aHeadlessStartWithoutAPermissionDecisionIsRefusedAndWithOneItStarts() {
        String data = "--subscriber.data-dir=" + dataDir.toString().replace('\\', '/');
        String roots = "--subscriber.allowed-roots=" + root.toString().replace('\\', '/');
        SpringApplication refused = new SpringApplication(SubscriberEngineApplication.class);
        refused.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> refused.run(data, roots, "--subscriber.runner=headless").close())
                .hasStackTraceContaining("subscriber.copilot.permission-mode is not set");

        SpringApplication allowed = new SpringApplication(SubscriberEngineApplication.class);
        allowed.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = allowed.run(data, roots, "--subscriber.runner=headless",
                "--SUBSCRIBER_COPILOT_PERMISSION_MODE=allow-list", "--SUBSCRIBER_COPILOT_ALLOWED_TOOLS=shell(git:*)")) {
            assertThat(context.getBean(SubscriberProperties.class).copilot().permissionMode())
                    .isEqualTo(SubscriberProperties.PermissionMode.ALLOW_LIST);
        }
    }

    @Test
    void theAgentToolAndTheNeutralPermissionNamesReachTheirSettingsAndTheNeutralNameWins() {
        SpringApplication app = new SpringApplication(SubscriberEngineApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                "--SUBSCRIBER_DATA_DIR=" + dataDir.toString().replace('\\', '/'),
                "--SUBSCRIBER_ALLOWED_ROOTS=" + root.toString().replace('\\', '/'), "--SUBSCRIBER_RUNNER=headless",
                "--SUBSCRIBER_AGENT_TOOL=claude", "--SUBSCRIBER_CLAUDE_COMMAND=claude-test",
                "--SUBSCRIBER_PERMISSION_MODE=allow-list", "--SUBSCRIBER_ALLOWED_TOOLS=Read,Bash(git *)",
                "--SUBSCRIBER_DENIED_TOOLS=Bash(curl *)", "--SUBSCRIBER_COPILOT_PERMISSION_MODE=ask")) {
            SubscriberProperties p = context.getBean(SubscriberProperties.class);

            assertThat(p.agentTool()).isEqualTo(SubscriberProperties.AgentTool.CLAUDE);
            assertThat(p.claude().command()).isEqualTo("claude-test");
            assertThat(p.agentCommand()).isEqualTo("claude-test");
            assertThat(p.copilot().permissionMode()).as("the neutral name wins over the SUBSCRIBER_COPILOT_ one")
                    .isEqualTo(SubscriberProperties.PermissionMode.ALLOW_LIST);
            assertThat(p.copilot().allowedTools()).containsExactly("Read", "Bash(git *)");
            assertThat(p.copilot().deniedTools()).containsExactly("Bash(curl *)");
        }
    }

    @Test
    void theHeadlessGuardSeesAModeSetUnderTheNeutralName() {
        SpringApplication app = new SpringApplication(SubscriberEngineApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = app.run(
                "--subscriber.data-dir=" + dataDir.toString().replace('\\', '/'),
                "--subscriber.allowed-roots=" + root.toString().replace('\\', '/'), "--subscriber.runner=headless",
                "--SUBSCRIBER_AGENT_TOOL=claude", "--SUBSCRIBER_PERMISSION_MODE=allow-all-tools")) {
            assertThat(context.getBean(SubscriberProperties.class).copilot().permissionMode())
                    .isEqualTo(SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS);
        }
    }

    @Test
    void anUnknownAgentToolOrAClaudeCommandWithShellCharactersStopsTheStartNamingTheSetting() {
        SpringApplication tool = new SpringApplication(SubscriberEngineApplication.class);
        tool.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> tool.run("--subscriber.data-dir=" + dataDir.toString().replace('\\', '/'),
                "--subscriber.allowed-roots=" + root.toString().replace('\\', '/'), "--subscriber.agent-tool=vim",
                "--subscriber.copilot.permission-mode=allow-all-tools").close()).hasStackTraceContaining("agent-tool");

        SpringApplication command = new SpringApplication(SubscriberEngineApplication.class);
        command.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> command.run("--subscriber.data-dir=" + dataDir.toString().replace('\\', '/'),
                "--subscriber.allowed-roots=" + root.toString().replace('\\', '/'), "--subscriber.claude.command=claude; calc",
                "--subscriber.copilot.permission-mode=allow-all-tools").close())
                .hasStackTraceContaining("subscriber.claude.command must be a command name");
    }

    @Test
    void withoutTheSettingTheToolIsCopilot() {
        try (ConfigurableApplicationContext context = start()) {
            SubscriberProperties p = context.getBean(SubscriberProperties.class);
            assertThat(p.agentTool()).isEqualTo(SubscriberProperties.AgentTool.COPILOT);
            assertThat(p.agentCommand()).isEqualTo("copilot");
            assertThat(p.claude().command()).isEqualTo("claude");
        }
    }
}
