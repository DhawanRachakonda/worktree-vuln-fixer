package dev.subscriber.worktree;

import static dev.subscriber.worktree.WorktreeServiceTest.git;
import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.worktree.WorktreeProvisioner.SourceRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Small but realistic repositories of three kinds (Spring Boot, Node.js, Python), each with a bare {@code origin} whose default branch is
 * {@code develop} and a {@code release/1.0} branch, driven through the real {@link WorktreeService}. Nothing is simulated except that no
 * build tool is run: the projects are only files, because the engine only needs git.
 */
class SampleRepositoriesTest {

    private static final String ID = "0a1b2c3d-1111-2222-3333-444455556666";

    @TempDir
    Path temp;

    /** Project name to its files (relative path to content). */
    static Map<String, Map<String, String>> projects() {
        return Map.of(
                "spring-shop", Map.of(
                        "pom.xml", """
                                <project><modelVersion>4.0.0</modelVersion>
                                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                                    <version>3.3.0</version></parent>
                                  <groupId>demo</groupId><artifactId>spring-shop</artifactId><version>1.0.0</version>
                                  <dependencies><dependency><groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>
                                </project>
                                """,
                        "src/main/java/demo/ShopApplication.java", """
                                package demo;

                                import org.springframework.boot.SpringApplication;
                                import org.springframework.boot.autoconfigure.SpringBootApplication;

                                @SpringBootApplication
                                public class ShopApplication {
                                    public static void main(String[] args) {
                                        SpringApplication.run(ShopApplication.class, args);
                                    }
                                }
                                """,
                        ".gitignore", "target/\n*.log\n"),
                "node-blog", Map.of(
                        "package.json", """
                                {"name":"node-blog","version":"1.0.0","main":"index.js","dependencies":{"express":"4.17.1"}}
                                """,
                        "index.js", "const express = require('express');\nconst app = express();\napp.listen(3000);\n",
                        ".gitignore", "node_modules/\n*.log\n"),
                "python-api", Map.of(
                        "requirements.txt", "flask==2.0.1\nrequests==2.25.0\n",
                        "app.py", "from flask import Flask\napp = Flask(__name__)\n",
                        ".gitignore", "__pycache__/\n*.log\n"));
    }

    static Stream<String> names() {
        return projects().keySet().stream().sorted();
    }

    private record Sample(Path repo, Path bare, String developTip, String releaseTip) {
    }

    private Sample create(String name) throws Exception {
        Path root = temp.toRealPath();
        Path repo = Files.createDirectories(root.resolve(name));
        git(repo, "init", "-q", "-b", "main");
        for (Map.Entry<String, String> file : projects().get(name).entrySet()) {
            Path target = repo.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "initial " + name);
        git(repo, "branch", "release/1.0");
        git(repo, "checkout", "-q", "-b", "develop");
        Files.writeString(repo.resolve("CHANGELOG.md"), "develop work\n");
        git(repo, "add", "CHANGELOG.md");
        git(repo, "commit", "-q", "-m", "develop work");
        Path bare = root.resolve(name + "-origin.git");
        git(root, "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());
        git(repo, "push", "-q", "origin", "main", "develop", "release/1.0");
        git(bare, "symbolic-ref", "HEAD", "refs/heads/develop");
        git(repo, "fetch", "-q", "origin");
        git(repo, "remote", "set-head", "origin", "--auto");
        // the person is on a feature branch with work in progress
        git(repo, "checkout", "-q", "-b", "feature/wip");
        return new Sample(repo, bare, git(repo, "rev-parse", "develop"), git(repo, "rev-parse", "release/1.0"));
    }

    private WorktreeService service() {
        return new WorktreeService(List.of(realTemp()), new GitRunner(temp.resolve("no-hooks"), 60));
    }

    private Path realTemp() {
        try {
            return temp.toRealPath();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @MethodSource("names")
    void withNothingSaidTheRemotesDefaultBranchIsTheBaseNotTheFeatureBranchTheCheckoutIsOn(String name) throws Exception {
        Sample s = create(name);
        var created = service().create(ID, s.repo(), null, SourceRequest.NONE);

        assertThat(created.worktree().baseCommit()).isEqualTo(s.developTip());
        assertThat(created.source().choice()).isEqualTo("default-branch");
        assertThat(created.source().branch()).isEqualTo("develop");
        assertThat(created.notes()).anyMatch(n -> n.contains("remote default"));
        assertThat(Path.of(created.worktree().path()).resolve("CHANGELOG.md")).exists();
        assertThat(git(s.repo(), "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("feature/wip");
    }

    @ParameterizedTest
    @MethodSource("names")
    void aBranchGivenAtPublishWinsOverTheSavedOne(String name) throws Exception {
        Sample s = create(name);
        Files.createDirectories(s.repo().resolve(".appmod"));
        Files.writeString(s.repo().resolve(RepositorySettings.LOCATION), "{\"sourceBranch\":\"develop\"}");

        var saved = service().create(ID, s.repo(), null, SourceRequest.NONE);
        assertThat(saved.source().choice()).isEqualTo("saved");
        assertThat(saved.worktree().baseCommit()).isEqualTo(s.developTip());

        var given = service().create("1b2c3d4e-1111-2222-3333-444455556666", s.repo(), null, new SourceRequest("release/1.0", false));
        assertThat(given.source().choice()).isEqualTo("publish");
        assertThat(given.worktree().baseCommit()).isEqualTo(s.releaseTip());
        assertThat(Path.of(given.worktree().path()).resolve("CHANGELOG.md")).doesNotExist();
    }

    @ParameterizedTest
    @MethodSource("names")
    void localChangesOfTheCheckoutAreSnapshottedAndTheCheckoutStaysAsItWas(String name) throws Exception {
        Sample s = create(name);
        String first = projects().get(name).keySet().stream().filter(k -> !k.equals(".gitignore")).sorted().findFirst().orElseThrow();
        Files.writeString(s.repo().resolve(first), Files.readString(s.repo().resolve(first)) + "// edited by hand\n");
        Files.writeString(s.repo().resolve("notes.txt"), "untracked\n");
        Files.writeString(s.repo().resolve("debug.log"), "ignored\n");
        String before = git(s.repo(), "status", "--porcelain", "-uall");

        var created = service().create(ID, s.repo(), null, new SourceRequest(null, true));

        Path work = Path.of(created.worktree().path());
        assertThat(work.resolve(first)).content().contains("edited by hand");
        assertThat(work.resolve("notes.txt")).hasContent("untracked\n");
        assertThat(work.resolve("debug.log")).doesNotExist();
        assertThat(created.source().localChanges()).isTrue();
        assertThat(created.source().branch()).isEqualTo("feature/wip");
        assertThat(git(s.repo(), "status", "--porcelain", "-uall")).isEqualTo(before);
        assertThat(git(s.repo(), "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("feature/wip");
    }

    @ParameterizedTest
    @MethodSource("names")
    void aBranchThatDoesNotExistFailsBeforeAnythingIsCreated(String name) throws Exception {
        Sample s = create(name);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().create(ID, s.repo(), null, new SourceRequest("release/9.9", false)))
                .hasMessageContaining("release/9.9").hasMessageContaining("does not exist");
        assertThat(git(s.repo(), "worktree", "list", "--porcelain")).doesNotContain("-fix-");
    }

    @Test
    void theThreeRepositoriesKeepSeparateSavedBranches() throws Exception {
        Sample a = create("spring-shop");
        Sample b = create("node-blog");
        Sample c = create("python-api");
        save(a, "develop");
        save(b, "release/1.0");
        save(c, "main");

        assertThat(service().create(ID, a.repo(), null, SourceRequest.NONE).worktree().baseCommit()).isEqualTo(a.developTip());
        assertThat(service().create(ID, b.repo(), null, SourceRequest.NONE).worktree().baseCommit()).isEqualTo(b.releaseTip());
        assertThat(service().create(ID, c.repo(), null, SourceRequest.NONE).worktree().baseCommit())
                .isEqualTo(git(c.repo(), "rev-parse", "main"));
    }

    private static void save(Sample s, String branch) throws Exception {
        Files.createDirectories(s.repo().resolve(".appmod"));
        Files.writeString(s.repo().resolve(RepositorySettings.LOCATION), "{\"sourceBranch\":\"%s\"}".formatted(branch));
    }
}
