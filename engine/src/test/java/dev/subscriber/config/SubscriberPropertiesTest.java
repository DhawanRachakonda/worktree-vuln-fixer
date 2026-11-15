package dev.subscriber.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties.PermissionMode;
import dev.subscriber.config.SubscriberProperties.Runner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class SubscriberPropertiesTest {

    @TempDir
    Path root;

    @TempDir
    Path otherRoot;

    private Map<String, Object> valid() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subscriber.allowed-roots", root.toString());
        return m;
    }

    private SubscriberProperties bind(Map<String, Object> props) {
        return new Binder(new MapConfigurationPropertySource(props)).bind("subscriber", SubscriberProperties.class).get();
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private String failureOf(Map<String, Object> props) {
        try {
            bind(props);
        } catch (RuntimeException e) {
            return chain(e);
        }
        throw new AssertionError("expected binding to fail for " + props);
    }

    @Test
    void defaultsAreSafeAndCompleteWhenOnlyTheRequiredSettingIsGiven() {
        SubscriberProperties p = bind(valid());
        assertThat(p.dataDir()).isEqualTo("./data");
        assertThat(p.runner()).isEqualTo(Runner.AUTO);
        assertThat(p.maxParallel()).isEqualTo(1);
        assertThat(p.copilot().command()).isEqualTo("copilot");
        assertThat(p.copilot().permissionMode()).isEqualTo(PermissionMode.ALLOW_ALL_TOOLS);
        assertThat(p.nats().url()).isEqualTo("nats://localhost:4222");
        assertThat(p.access().token()).isNull();
        assertThat(p.allowedRoots()).containsExactly(root.toString());
    }

    @Test
    void environmentVariablesOverrideTheDefaults() {
        var env = new SystemEnvironmentPropertySource("env", Map.<String, Object>of(
                "SUBSCRIBER_ALLOWED_ROOTS", root + "," + otherRoot,
                "SUBSCRIBER_MAX_PARALLEL", "3",
                "SUBSCRIBER_RUNNER", "headless",
                "SUBSCRIBER_DATA_DIR", "C:/state dir/engine"));
        SubscriberProperties p = new Binder(ConfigurationPropertySources.from(env))
                .bind("subscriber", SubscriberProperties.class).get();
        assertThat(p.allowedRoots()).containsExactly(root.toString(), otherRoot.toString());
        assertThat(p.maxParallel()).isEqualTo(3);
        assertThat(p.runner()).isEqualTo(Runner.HEADLESS);
        assertThat(p.dataDir()).isEqualTo("C:/state dir/engine");
    }

    @Test
    void autoPicksAWindowOnWindowsAndHeadlessElsewhereAndAnExplicitChoiceWins() {
        SubscriberProperties auto = bind(valid());
        assertThat(auto.effectiveRunner(true)).isEqualTo(Runner.WINDOW);
        assertThat(auto.effectiveRunner(false)).isEqualTo(Runner.HEADLESS);
        Map<String, Object> m = valid();
        m.put("subscriber.runner", "headless");
        assertThat(bind(m).effectiveRunner(true)).isEqualTo(Runner.HEADLESS);
        m.put("subscriber.runner", "window");
        assertThat(bind(m).effectiveRunner(false)).isEqualTo(Runner.WINDOW);
    }

    @Test
    void aSubjectGetsTheDefaultAgentAndBlankValuesCountAsUnset() {
        Map<String, Object> m = valid();
        m.put("subscriber.subjects[0].subject", "securityaudit.issuefixesAgent");
        m.put("subscriber.subjects[0].source", "Security audit");
        m.put("subscriber.subjects[0].report-location", ".appmod\\security-audit\\issues.csv");
        m.put("subscriber.subjects[0].repository", "");
        SubscriberProperties.Subject s = bind(m).subjects().get(0);
        assertThat(s.agent()).isEqualTo("appmod-harness");
        assertThat(s.repository()).isNull();
        assertThat(s.reportLocation()).isEqualTo(".appmod/security-audit/issues.csv");
    }

    static Stream<Arguments> invalidSettings() throws Exception {
        Path file = Files.createTempFile("not-a-folder", ".txt");
        return Stream.of(
                Arguments.of("data dir with &", "subscriber.data-dir", "C:/x&y", "subscriber.data-dir"),
                Arguments.of("data dir with %", "subscriber.data-dir", "C:/x%PATH%", "subscriber.data-dir"),
                Arguments.of("data dir with a quote", "subscriber.data-dir", "C:/x\"y", "subscriber.data-dir"),
                Arguments.of("no allowed roots", "subscriber.allowed-roots", "", "subscriber.allowed-roots is required"),
                Arguments.of("relative allowed root", "subscriber.allowed-roots", "relative/folder", "subscriber.allowed-roots"),
                Arguments.of("allowed root that is a file", "subscriber.allowed-roots", file.toString(), "subscriber.allowed-roots"),
                Arguments.of("max parallel zero", "subscriber.max-parallel", "0", "subscriber.max-parallel"),
                Arguments.of("max parallel too high", "subscriber.max-parallel", "17", "subscriber.max-parallel"),
                Arguments.of("unknown runner", "subscriber.runner", "popup", "subscriber.runner"),
                Arguments.of("unknown permission mode", "subscriber.copilot.permission-mode", "yolo", "permission-mode"),
                Arguments.of("copilot command with a path", "subscriber.copilot.command", "C:\\x\\copilot", "subscriber.copilot.command"),
                Arguments.of("nats url with another scheme", "subscriber.nats.url", "http://localhost:4222", "subscriber.nats.url"),
                Arguments.of("nats url with credentials in it", "subscriber.nats.url", "nats://alice:s3cret@localhost:4222", "must not contain credentials"),
                Arguments.of("user without password", "subscriber.nats.user", "u", "user and subscriber.nats.password"),
                Arguments.of("credentials file that is missing", "subscriber.nats.credentials-file", "C:/nope/x.creds",
                        "subscriber.nats.credentials-file"),
                Arguments.of("short access token", "subscriber.access.token", "short", "subscriber.access.token"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSettings")
    void anInvalidSettingStopsTheStartNamingTheSetting(String label, String key, String value, String expected) {
        Map<String, Object> m = valid();
        m.put(key, value);
        assertThat(failureOf(m)).contains(expected);
    }

    static Stream<Arguments> invalidSubjects() {
        return Stream.of(
                Arguments.of("wildcard subject", Map.of("subject", "securityaudit.*", "source", "x"), "subscriber.subjects[].subject"),
                Arguments.of("subject with a space", Map.of("subject", "a b", "source", "x"), "subscriber.subjects[].subject"),
                Arguments.of("agent with shell characters", Map.of("subject", "a.b", "source", "x", "agent", "x;y"),
                        "subscriber.subjects[].agent"),
                Arguments.of("source with a quote", Map.of("subject", "a.b", "source", "x\"y"), "subscriber.subjects[].source"),
                Arguments.of("repository and report location", Map.of("subject", "a.b", "source", "x",
                        "repository", "C:/r", "report-location", "a/b.csv"), "sets both repository and report-location"),
                Arguments.of("relative repository", Map.of("subject", "a.b", "source", "x", "repository", "rel/r"),
                        "subscriber.subjects[].repository"),
                Arguments.of("report location with ..", Map.of("subject", "a.b", "source", "x", "report-location", "../x.csv"),
                        "subscriber.subjects[].report-location"),
                Arguments.of("absolute report location", Map.of("subject", "a.b", "source", "x", "report-location", "/etc/x.csv"),
                        "subscriber.subjects[].report-location"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSubjects")
    void anInvalidSubjectStopsTheStartNamingTheSetting(String label, Map<String, String> subject, String expected) {
        Map<String, Object> m = valid();
        subject.forEach((k, v) -> m.put("subscriber.subjects[0]." + k, v));
        assertThat(failureOf(m)).contains(expected);
    }

    @Test
    void theSameSubjectTwiceIsRefused() {
        Map<String, Object> m = valid();
        for (int i = 0; i < 2; i++) {
            m.put("subscriber.subjects[" + i + "].subject", "a.b");
            m.put("subscriber.subjects[" + i + "].source", "x");
        }
        assertThat(failureOf(m)).contains("more than once: a.b");
    }

    @Test
    void secretsNeverAppearWhenTheSettingsArePrinted() {
        Map<String, Object> m = valid();
        m.put("subscriber.nats.user", "alice");
        m.put("subscriber.nats.password", "s3cret-password-value");
        m.put("subscriber.access.token", "an-access-token-of-sufficient-length");
        String printed = bind(m).toString() + bind(m).nats() + bind(m).access();
        assertThat(printed).doesNotContain("s3cret-password-value").doesNotContain("an-access-token-of-sufficient-length");

        Map<String, Object> t = valid();
        t.put("subscriber.nats.token", "nats-token-value-123456");
        assertThat(bind(t).nats().toString()).doesNotContain("nats-token-value-123456").contains("token");
    }

    @Test
    void onlyOneNatsAuthenticationMethodIsAllowed() {
        Map<String, Object> m = valid();
        m.put("subscriber.nats.token", "t");
        m.put("subscriber.nats.user", "u");
        m.put("subscriber.nats.password", "p");
        assertThat(failureOf(m)).contains("use only one of token, user and password, or credentials-file");
    }

    @Test
    void isolationIsAWorktreeUnlessSaidOtherwiseAndASubjectCanOverrideIt() {
        assertThat(bind(valid()).isolation()).isEqualTo(SubscriberProperties.Isolation.WORKTREE);

        Map<String, Object> props = valid();
        props.put("subscriber.subjects[0].subject", "securityaudit.issuefixesAgent");
        props.put("subscriber.subjects[0].source", "Security audit");
        props.put("subscriber.subjects[1].subject", "coverity.issuefixesAgent");
        props.put("subscriber.subjects[1].source", "Coverity");
        props.put("subscriber.subjects[1].isolation", "none");
        SubscriberProperties general = bind(props);
        assertThat(general.subjects()).extracting(SubscriberProperties.Subject::isolation)
                .containsExactly(SubscriberProperties.Isolation.WORKTREE, SubscriberProperties.Isolation.NONE);

        props.put("subscriber.isolation", "none");
        props.put("subscriber.subjects[0].isolation", "worktree");
        SubscriberProperties off = bind(props);
        assertThat(off.subjects()).extracting(SubscriberProperties.Subject::isolation)
                .containsExactly(SubscriberProperties.Isolation.WORKTREE, SubscriberProperties.Isolation.NONE);
        assertThat(off.isolation()).isEqualTo(SubscriberProperties.Isolation.NONE);
    }

    @Test
    void anInvalidIsolationStopsTheStartNamingTheSetting() {
        Map<String, Object> general = valid();
        general.put("subscriber.isolation", "sandbox");
        assertThat(failureOf(general)).contains("subscriber.isolation").contains("sandbox");

        Map<String, Object> subject = valid();
        subject.put("subscriber.subjects[0].subject", "securityaudit.issuefixesAgent");
        subject.put("subscriber.subjects[0].source", "Security audit");
        subject.put("subscriber.subjects[0].isolation", "branch");
        assertThat(failureOf(subject)).contains("isolation").contains("branch");
    }

    @Test
    void theShortConstructorsKeepTheCheckoutSoOlderCodeBehavesAsBefore() {
        SubscriberProperties.Subject subject = new SubscriberProperties.Subject("a.b", "Source", "agent", null, null);
        assertThat(subject.isolation()).isNull();
        SubscriberProperties p = new SubscriberProperties(root.toString(), java.util.List.of(root.toString()), java.util.List.of(subject),
                Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null));
        assertThat(p.isolation()).isEqualTo(SubscriberProperties.Isolation.NONE);
        assertThat(p.subjects().get(0).isolation()).isEqualTo(SubscriberProperties.Isolation.NONE);
    }

    // ---- permission allow-list ----

    @Test
    void theAllowListModeTakesItsRulesAsAListOrAsCommaSeparatedText() {
        Map<String, Object> indexed = valid();
        indexed.put("subscriber.copilot.permission-mode", "allow-list");
        indexed.put("subscriber.copilot.allowed-tools[0]", "shell(git:*)");
        indexed.put("subscriber.copilot.allowed-tools[1]", "write(C:/work/**)");
        indexed.put("subscriber.copilot.denied-tools[0]", "shell(curl:*)");
        SubscriberProperties.Copilot a = bind(indexed).copilot();
        assertThat(a.permissionMode()).isEqualTo(PermissionMode.ALLOW_LIST);
        assertThat(a.allowedTools()).containsExactly("shell(git:*)", "write(C:/work/**)");
        assertThat(a.deniedTools()).containsExactly("shell(curl:*)");

        Map<String, Object> text = valid();
        text.put("subscriber.copilot.permission-mode", "allow-list");
        text.put("subscriber.copilot.allowed-tools", " shell(git:*) , ,write(C:/work/**),shell(Test-Path) ");
        text.put("subscriber.copilot.denied-tools", "shell(git push:*)");
        SubscriberProperties.Copilot b = bind(text).copilot();
        assertThat(b.allowedTools()).as("blank entries dropped, spaces around a rule stripped")
                .containsExactly("shell(git:*)", "write(C:/work/**)", "shell(Test-Path)");
        assertThat(b.deniedTools()).containsExactly("shell(git push:*)");
    }

    @Test
    void rulesWithAnotherModeStopTheStartInsteadOfBeingIgnored() {
        for (String mode : new String[] {"ask", "allow-all-tools"}) {
            Map<String, Object> allowed = valid();
            allowed.put("subscriber.copilot.permission-mode", mode);
            allowed.put("subscriber.copilot.allowed-tools", "shell(git:*)");
            assertThat(failureOf(allowed)).as(mode).contains("subscriber.copilot.allowed-tools").contains("allow-list").contains("the mode is " + mode);

            Map<String, Object> denied = valid();
            denied.put("subscriber.copilot.permission-mode", mode);
            denied.put("subscriber.copilot.denied-tools", "shell(curl:*)");
            assertThat(failureOf(denied)).as(mode + " with only a deny rule").contains("denied-tools").contains("allow-list");
        }
        Map<String, Object> defaultMode = valid();
        defaultMode.put("subscriber.copilot.allowed-tools", "shell(git:*)");
        assertThat(failureOf(defaultMode)).as("the default mode allows everything, so a list would be ignored").contains("the mode is allow-all-tools");
    }

    @Test
    void theAllowListModeNeedsAtLeastOneAllowRule() {
        Map<String, Object> none = valid();
        none.put("subscriber.copilot.permission-mode", "allow-list");
        assertThat(failureOf(none)).contains("subscriber.copilot.allowed-tools is required").contains("SUBSCRIBER_COPILOT_ALLOWED_TOOLS");

        Map<String, Object> onlyDeny = valid();
        onlyDeny.put("subscriber.copilot.permission-mode", "allow-list");
        onlyDeny.put("subscriber.copilot.denied-tools", "shell(curl:*)");
        assertThat(failureOf(onlyDeny)).as("deny rules alone allow nothing").contains("allowed-tools is required");

        Map<String, Object> blank = valid();
        blank.put("subscriber.copilot.permission-mode", "allow-list");
        blank.put("subscriber.copilot.allowed-tools", " , ");
        assertThat(failureOf(blank)).contains("allowed-tools is required");
    }

    @Test
    void aRuleThatCouldRunAsPartOfACommandStopsTheStartAndIsNeverEchoed() {
        String[] hostile = {"shell(git:*); calc", "a'b", "a\"b", "a$(calc)", "a`b", "a&b", "a|b", "a<b", "a>b", "a%PATH%b", "a\nb", "a\rb",
                "x".repeat(201), "shell(git:*)\u0007", "shell(é)"};
        for (String rule : hostile) {
            Map<String, Object> allowed = valid();
            allowed.put("subscriber.copilot.permission-mode", "allow-list");
            allowed.put("subscriber.copilot.allowed-tools[0]", "shell(git:*)");
            allowed.put("subscriber.copilot.allowed-tools[1]", rule);
            String message = failureOf(allowed);
            assertThat(message).as(rule).contains("subscriber.copilot.allowed-tools[1] is not a plain tool rule");
            assertThat(message).as("the rule is not echoed").doesNotContain(rule.strip().isEmpty() ? "\u0000" : rule.strip());

            Map<String, Object> denied = valid();
            denied.put("subscriber.copilot.permission-mode", "allow-list");
            denied.put("subscriber.copilot.allowed-tools", "shell(git:*)");
            denied.put("subscriber.copilot.denied-tools[0]", rule);
            assertThat(failureOf(denied)).as("deny rule " + rule).contains("subscriber.copilot.denied-tools[0] is not a plain tool rule");
        }
    }

    @Test
    void theRulesTheCliDocumentsAreAccepted() {
        Map<String, Object> props = valid();
        props.put("subscriber.copilot.permission-mode", "allow-list");
        props.put("subscriber.copilot.allowed-tools",
                "shell(git:*),shell(git push),shell(gh pr create),write(C:/Users/me/work/**),write(.env),read(~/notes/todo.md),"
                        + "shell(Get-ChildItem),shell(npm:*),write(reports/*/output.txt),write(C:\\work\\**),memory");

        assertThat(bind(props).copilot().allowedTools()).hasSize(11);
    }

    @Test
    void publishingIsOffUnlessAskedFor() {
        SubscriberProperties p = bind(valid());
        assertThat(p.publish().mode()).isEqualTo(SubscriberProperties.PublishMode.NONE);
        assertThat(p.publish().pullRequestCommand()).isNull();
    }

    @Test
    void thePublishModeAndCommandAreRead() {
        Map<String, Object> m = valid();
        m.put("subscriber.publish.mode", "pull-request");
        m.put("subscriber.publish.pull-request-command", "gh pr create --base {base} --head {head}");
        SubscriberProperties p = bind(m);
        assertThat(p.publish().mode()).isEqualTo(SubscriberProperties.PublishMode.PULL_REQUEST);
        assertThat(p.publish().pullRequestCommand()).isEqualTo("gh pr create --base {base} --head {head}");

        Map<String, Object> push = valid();
        push.put("subscriber.publish.mode", "push");
        assertThat(bind(push).publish().mode()).isEqualTo(SubscriberProperties.PublishMode.PUSH);
    }

    @Test
    void anInvalidPublishSettingStopsTheStartNamingTheSetting() {
        Map<String, Object> unknown = valid();
        unknown.put("subscriber.publish.mode", "force");
        assertThat(failureOf(unknown)).contains("subscriber.publish.mode");

        Map<String, Object> noCommand = valid();
        noCommand.put("subscriber.publish.mode", "pull-request");
        assertThat(failureOf(noCommand)).contains("subscriber.publish.pull-request-command is required");

        Map<String, Object> blank = valid();
        blank.put("subscriber.publish.mode", "pull-request");
        blank.put("subscriber.publish.pull-request-command", "   ");
        assertThat(failureOf(blank)).contains("subscriber.publish.pull-request-command is required");

        Map<String, Object> multiline = valid();
        multiline.put("subscriber.publish.pull-request-command", "gh\nrm");
        assertThat(failureOf(multiline)).contains("one line");
    }
}
