import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tests of {@code appmod setup} and {@code appmod start} (kit/install/AppmodSetup.java, AppmodStart.java, MachineConfig.java, Downloads.java).
 * Single file, JDK only. Both programs run as separate processes, as users run them, with APPMOD_HOME in a temporary folder. A stand-in
 * release server (an HttpServer on a loopback address) serves the archives, a stand-in nats-server and a stand-in engine replace the real
 * ones. No internet and no Docker. The commands are Windows only: on another operating system this prints SKIPPED and exits 0.
 *
 * <pre>
 *   java tests/setup/SetupStartTests.java [kit-root]        (kit-root defaults to the current directory)
 * </pre>
 */
public final class SetupStartTests {

    private static int passed;
    private static int failed;
    private static Path kit;
    private static Path scratch;
    private static int runs;
    private static Path engineJar;
    private static Path natsCmd;

    record Run(int exit, String out, String err) {
        String all() {
            return out + "\n" + err;
        }
    }

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            System.out.println("SKIPPED: appmod setup and appmod start are Windows only, and this is " + System.getProperty("os.name"));
            return;
        }
        kit = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        scratch = Files.createTempDirectory("setup-start-tests-");
        try {
            prepareStandIns();
            setupDownloadsVerifiesAndSaves();
            setupRunTwiceDownloadsOnce();
            setupRefusesWrongHashAndEvilArchive();
            setupQuestionsAndFlags();
            setupCheckChangesNothing();
            initWarnsOutsideTheSetupFolders();
            appmodCmdIsWiredToBothCommands();
            setupWithoutAnEngineFolderSaysSo();
            setupUsesANatsServerOnThePathAndBuildsTheEngine();
            startRunsBothAndStopsWhatItStarted();
            startReusesARunningNats();
            startRefusals();
            startNatsOnlyAndEngineOnly();
        } finally {
            deleteTree(scratch);
        }
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- setup ----

    static void setupDownloadsVerifiesAndSaves() throws Exception {
        byte[] zip = zip("nats-server-vTEST/nats-server.exe", "not a program");
        try (Release release = new Release(zip)) {
            Path home = fresh("home-download");
            Path roots = fresh("repos-a");
            Path pathFile = scratch.resolve("path-additions.txt");
            Run run = setup(home, release, hash(zip), "--roots", roots.toString(), "--tool", "claude", "--yes", "--engine-jar", engineJar.toString(), "--path-additions-file", pathFile.toString());

            Path program = home.resolve("tools/nats-server-vTEST/nats-server.exe");
            check("setup: exits 0 and downloads the pinned nats-server", run.exit == 0 && Files.isRegularFile(program), run.all());
            check("setup: the archive is deleted and only the program is unpacked", !Files.exists(home.resolve("tools/nats-server-vTEST.zip"))
                    && listing(home.resolve("tools/nats-server-vTEST")).equals(List.of("nats-server.exe")), String.valueOf(listing(home.resolve("tools/nats-server-vTEST"))));
            String config = Files.readString(home.resolve("machine.properties"));
            check("setup: the configuration names the JDK, the program, the engine, the folder and the tool", config.contains("claude")
                    && config.contains("engineJar") && config.contains("allowedRoots") && config.contains("natsServer") && config.contains("javaHome"), config);
            check("setup: it names the folder for the PATH and writes it to the file appmod.cmd reads, and never changes the PATH itself", run.out.contains("Folder for your PATH") && Files.exists(pathFile) && Files.readString(pathFile).startsWith(";") && Files.readString(pathFile).contains("nats-server-vTEST"), run.out);
            check("setup: no secret is stored", !config.toLowerCase().contains("token") && !config.toLowerCase().contains("password"));
        }
    }

    static void setupRunTwiceDownloadsOnce() throws Exception {
        byte[] zip = zip("nats-server-vTEST/nats-server.exe", "not a program");
        try (Release release = new Release(zip)) {
            Path home = fresh("home-twice");
            Path roots = fresh("repos-b");
            setup(home, release, hash(zip), "--roots", roots.toString(), "--tool", "copilot", "--yes", "--engine-jar", engineJar.toString());
            String first = Files.readString(home.resolve("machine.properties"));
            Run second = setup(home, release, hash(zip), "--yes", "--engine-jar", engineJar.toString());

            check("setup run again: exits 0, downloads nothing and keeps the earlier answers", second.exit == 0 && release.requests.get() == 1
                    && second.out.contains("already downloaded") && savedRoots(home).equals(roots.toString()), second.all());
            check("setup run again: the configuration is the same", first.lines().filter(l -> !l.startsWith("#")).toList()
                    .equals(Files.readString(home.resolve("machine.properties")).lines().filter(l -> !l.startsWith("#")).toList()));
        }
    }

    static void setupRefusesWrongHashAndEvilArchive() throws Exception {
        byte[] zip = zip("nats-server-vTEST/nats-server.exe", "not a program");
        try (Release release = new Release(zip)) {
            Path home = fresh("home-badhash");
            Path roots = fresh("repos-c");
            String wrong = "0".repeat(64);
            Run run = setup(home, release, wrong, "--roots", roots.toString(), "--tool", "copilot", "--yes", "--engine-jar", engineJar.toString());
            check("setup: a wrong hash stops with both hashes, deletes the download and unpacks nothing", run.exit != 0 && run.err.contains(wrong) && run.err.contains(hash(zip))
                    && !Files.exists(home.resolve("tools/nats-server-vTEST/nats-server.exe")) && !Files.exists(home.resolve("tools/nats-server-vTEST.zip")), run.all());
            check("setup: nothing is saved after a failed download", !Files.exists(home.resolve("machine.properties")));
        }
        byte[] evil = zip("..\\..\\evil.exe", "bad");
        try (Release release = new Release(evil)) {
            Path home = fresh("home-evil");
            Path roots = fresh("repos-d");
            Run run = setup(home, release, hash(evil), "--roots", roots.toString(), "--tool", "copilot", "--yes", "--engine-jar", engineJar.toString());
            check("setup: an archive entry that leaves the folder stops everything", run.exit != 0 && run.err.contains("would leave the target folder")
                    && !Files.exists(home.getParent().resolve("evil.exe")) && !Files.exists(home.resolve("machine.properties")), run.all());
        }
        Path home = fresh("home-nodl");
        Run unreachable = setup(home, "http://127.0.0.1:1", "a".repeat(64), "--roots", fresh("repos-e").toString(), "--yes", "--engine-jar", engineJar.toString());
        check("setup: an unreachable address is named, with the manual alternative", unreachable.exit != 0 && unreachable.err.contains("http://127.0.0.1:1")
                && unreachable.err.contains("on the PATH"), unreachable.all());
        Run noHash = run(home, Map.of("APPMOD_NATS_VERSION", "v9.9.9"), "--yes", "--roots", fresh("repos-f").toString(), "--engine-jar", engineJar.toString());
        check("setup: a version without a pinned hash is refused", noHash.exit != 0 && noHash.err.contains("no pinned SHA-256"), noHash.all());
    }

    static void setupQuestionsAndFlags() throws Exception {
        byte[] zip = zip("nats-server-vTEST/nats-server.exe", "x");
        try (Release release = new Release(zip)) {
            Path home = fresh("home-q");
            Run relative = setup(home, release, hash(zip), "--roots", "src", "--yes", "--engine-jar", engineJar.toString());
            check("setup: a relative folder by flag stops and saves nothing", relative.exit != 0 && relative.err.contains("absolute") && !Files.exists(home.resolve("machine.properties")), relative.all());
            Run missing = setup(home, release, hash(zip), "--roots", scratch.resolve("nope").toString(), "--yes", "--engine-jar", engineJar.toString());
            check("setup: a missing folder is named", missing.exit != 0 && missing.err.contains("does not exist"), missing.all());
            Run noRoots = setup(home, release, hash(zip), "--yes", "--engine-jar", engineJar.toString());
            check("setup: --yes cannot guess the folders on a first run", noRoots.exit != 0 && noRoots.err.contains("--roots"), noRoots.all());
            Run badTool = setup(home, release, hash(zip), "--roots", fresh("repos-g").toString(), "--tool", "vim", "--yes", "--engine-jar", engineJar.toString());
            check("setup: a tool other than copilot or claude is refused", badTool.exit != 0 && badTool.err.contains("copilot or claude"), badTool.all());

            Path good = fresh("repos-h");
            Path answers = scratch.resolve("answers-" + (++runs) + ".txt");
            Files.writeString(answers, "y" + System.lineSeparator() + "relative\\folder" + System.lineSeparator() + good + System.lineSeparator() + "nonsense" + System.lineSeparator() + "claude" + System.lineSeparator());
            Run asked = run(home, Map.of("APPMOD_NATS_BASE_URL", release.base(), "APPMOD_NATS_VERSION", "vTEST", "APPMOD_NATS_SHA256", hash(zip)),
                    List.of("-Dappmod.setup.answers=" + answers), "--engine-jar", engineJar.toString());
            check("setup: it asks, refuses a bad answer, asks again and saves the good ones", asked.exit == 0 && asked.out.contains("try again")
                    && Files.readString(home.resolve("machine.properties")).contains("claude"), asked.all());

            String before = Files.readString(home.resolve("machine.properties"));
            Run failing = setup(home, release, hash(zip), "--roots", "relative", "--yes", "--engine-jar", engineJar.toString());
            check("setup: a failed run leaves the earlier configuration as it was", failing.exit != 0 && Files.readString(home.resolve("machine.properties")).equals(before), failing.all());
        }
    }

    static void setupCheckChangesNothing() throws Exception {
        Path home = fresh("home-check");
        Run run = run(home, Map.of(), "--check", "--engine-jar", engineJar.toString());
        check("setup --check: exits 1 when something is missing, says what, and writes nothing", run.exit == 1 && run.out.contains("MISSING") && listing(home).isEmpty(), run.all());
    }

    // ---- init ----

    static void initWarnsOutsideTheSetupFolders() throws Exception {
        int port = freePort();
        Path home = configured("home-init", port, natsCmd);
        Path inside = Files.createDirectories(Path.of(savedRoots(home)).resolve("shop"));
        Path outside = fresh("elsewhere-repo");
        Run in = installKit(home, inside);
        Run out = installKit(home, outside);
        Run noConfig = installKit(fresh("home-init-none"), fresh("some-repo"));

        check("init: a repository inside the folders given to setup gets no warning", in.exit == 0 && !in.out.contains("outside the folders given to appmod setup"), in.all());
        check("init: a repository outside them gets a warning naming both, and the kit is still installed", out.exit == 0 && out.out.contains("outside the folders given to appmod setup")
                && out.out.contains(outside.toString()) && out.out.contains(savedRoots(home)) && Files.isDirectory(outside.resolve(".github")), out.all());
        check("init: with no machine configuration nothing is said about folders", noConfig.exit == 0 && !noConfig.out.contains("appmod setup"), noConfig.all());
    }

    static Run installKit(Path home, Path repository) throws Exception {
        return execute(List.of(javaExe(), kit.resolve("install/KitInstaller.java").toString(), "--target", repository.toString()), home, Map.of());
    }

    static void setupUsesANatsServerOnThePathAndBuildsTheEngine() throws Exception {
        // a nats-server already installed (any file of that name: setup only needs to find it), so nothing is downloaded
        Path bin = fresh("path-with-nats");
        Files.writeString(bin.resolve("nats-server.exe"), "not a program");
        Path home = fresh("home-onpath");
        Path roots = fresh("repos-onpath");
        // a kit folder with an engine folder next to it whose Gradle wrapper builds a jar (the real build is the recorded real run)
        Path clone = fresh("clone-ok");
        Path engine = Files.createDirectories(clone.resolve("engine"));
        Files.createDirectories(clone.resolve("kit"));
        Files.writeString(engine.resolve("gradlew.bat"), "@echo off" + System.lineSeparator() + "echo JAVA_HOME=%JAVA_HOME%> java-home-seen.txt" + System.lineSeparator()
                + "mkdir build\\libs 2> nul" + System.lineSeparator() + "echo jar> build\\libs\\subscriber-engine-1.0.jar" + System.lineSeparator() + "exit /b 0" + System.lineSeparator());
        String path = bin + File.pathSeparator + System.getenv("PATH");
        Run run = runWithKit(home, clone.resolve("kit"), Map.of("PATH", path), "--roots", roots.toString(), "--tool", "copilot", "--yes");
        check("setup: a nats-server already on the PATH is used and nothing is downloaded", run.exit == 0 && run.out.contains("found on the PATH") && !run.out.contains("Downloading")
                && !Files.exists(home.resolve("tools")), run.all());
        check("setup: the engine is built with the Gradle wrapper and the recorded JDK, and its program is saved", run.out.contains("(built)") && Files.readString(engine.resolve("java-home-seen.txt")).contains(System.getProperty("java.home"))
                && Files.readString(home.resolve("machine.properties")).contains("subscriber-engine-1.0.jar"), run.all());

        Path broken = fresh("clone-broken");
        Path brokenEngine = Files.createDirectories(broken.resolve("engine"));
        Files.createDirectories(broken.resolve("kit"));
        Files.writeString(brokenEngine.resolve("gradlew.bat"), "@echo off" + System.lineSeparator() + "echo BUILD BROKEN: no toolchain" + System.lineSeparator() + "exit /b 1" + System.lineSeparator());
        Path homeBroken = fresh("home-brokenbuild");
        Run failed = runWithKit(homeBroken, broken.resolve("kit"), Map.of("PATH", path), "--roots", roots.toString(), "--tool", "copilot", "--yes");
        check("setup: a failed engine build shows the build's own message, saves nothing and exits with an error", failed.exit != 0 && failed.err.contains("BUILD BROKEN") && !Files.exists(homeBroken.resolve("machine.properties")), failed.all());
    }

    static Run runWithKit(Path home, Path kitDir, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaExe(), kit.resolve("install/AppmodSetup.java").toString(), "--java-home", System.getProperty("java.home"), "--kit-dir", kitDir.toString()));
        command.addAll(List.of(args));
        return execute(command, home, env);
    }

    // ---- appmod.cmd ----

    static void appmodCmdIsWiredToBothCommands() throws Exception {
        Path home = fresh("home-cmd");
        Run help = cmd(home, "--help");
        check("appmod.cmd --help lists setup and start", help.exit == 0 && help.out.contains("appmod setup") && help.out.contains("appmod start") && help.out.contains("--nats"), help.all());
        Run check = cmd(home, "setup", "--check");
        check("appmod.cmd setup --check runs the setup program: exit 1 when something is missing, nothing written", check.exit == 1 && check.out.contains("MISSING") && listing(home).isEmpty(), check.all());
        Run start = cmd(home, "start", "--bogus");
        check("appmod.cmd start hands its options to the launcher (an unknown one is refused with exit 2)", start.exit == 2 && start.err.contains("--nats"), start.all());
        Run noConfig = cmd(home, "start");
        check("appmod.cmd start without a configuration says to run appmod setup", noConfig.exit == 1 && noConfig.err.contains("appmod setup"), noConfig.all());
    }

    static void setupWithoutAnEngineFolderSaysSo() throws Exception {
        Path home = fresh("home-noengine");
        Run run = run(home, Map.of(), "--roots", fresh("repos-noengine").toString(), "--yes");
        check("setup: no engine folder next to the kit is said, with --engine-jar as the way out", run.exit != 0 && run.err.contains("no engine folder") && run.err.contains("--engine-jar"), run.all());
    }

    static Run cmd(Path home, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("cmd", "/c", kit.resolve("appmod.cmd").toString()));
        command.addAll(List.of(args));
        return execute(command, home, Map.of());
    }

    // ---- start ----

    static void startRunsBothAndStopsWhatItStarted() throws Exception {
        int port = freePort();
        Path home = configured("home-start", port, natsCmd);
        Path log = scratch.resolve("nats-args-" + (++runs) + ".txt");
        Run run = start(home, Map.of("STANDIN_LOG", log.toString()));

        check("start: NATS is started, the engine runs in the foreground, and it ends with the engine's exit status", run.exit == 0
                && run.out.contains("NATS started on 127.0.0.1:" + port) && run.out.contains("ENGINE NATS: INFO"), run.all());
        check("start: the engine got only the saved settings", run.out.contains("ENGINE AGENT_TOOL=claude") && run.out.contains("ENGINE NATS_URL=nats://127.0.0.1:" + port)
                && run.out.contains("ENGINE ALLOWED_ROOTS=") && run.out.contains("ENGINE DATA_DIR=") && run.out.contains("ENGINE JAVA=" + System.getProperty("java.home")), run.out);
        check("start: NATS listens on the loopback address only", Files.exists(log) && Files.readString(log).contains("address=127.0.0.1 port=" + port), Files.exists(log) ? Files.readString(log) : "no log");
        check("start: a NATS it started is stopped when the engine ends, and the process id file is gone", !portOpen(port) && !Files.exists(home.resolve("run/nats.pid")));
        check("start: the log file of NATS exists", Files.exists(home.resolve("run/nats.log")));
    }

    static void startReusesARunningNats() throws Exception {
        int port = freePort();
        Path home = configured("home-reuse", port, natsCmd);
        Process existing = new ProcessBuilder(natsCmd.toString(), "-a", "127.0.0.1", "-p", String.valueOf(port)).redirectErrorStream(true).redirectOutput(scratch.resolve("existing.log").toFile()).start();
        try {
            waitForPort(port);
            Run run = start(home, Map.of());
            check("start: a NATS that already answers is reused, not started again", run.exit == 0 && run.out.contains("already answers") && !run.out.contains("NATS started"), run.all());
            check("start: a NATS it did not start is still running afterwards", portOpen(port));
            Run engineOnly = start(home, Map.of(), "--engine");
            check("start --engine with a NATS running elsewhere: only the engine starts and it connects to that server", engineOnly.exit == 0 && !engineOnly.out.contains("no NATS server answers")
                    && !engineOnly.out.contains("NATS started") && engineOnly.out.contains("ENGINE NATS: INFO"), engineOnly.all());
        } finally {
            existing.descendants().forEach(ProcessHandle::destroyForcibly);
            existing.destroyForcibly();
        }
        waitForPortClosed(port);
    }

    static void startRefusals() throws Exception {
        Run none = start(fresh("home-none"), Map.of());
        check("start: without a configuration nothing starts and it says to run appmod setup", none.exit != 0 && none.err.contains("appmod setup"), none.all());

        int port = freePort();
        Path goneHome = configured("home-gone", port, scratch.resolve("no-such-nats.cmd"));
        Run gone = start(goneHome, Map.of());
        check("start: a program named in the configuration that is gone is named", gone.exit != 0 && gone.err.contains("no-such-nats.cmd") && gone.err.contains("appmod setup"), gone.all());

        int busy = freePort();
        try (ServerSocket silent = new ServerSocket(busy, 5, InetAddress.getByName("127.0.0.1"))) {
            Thread accepting = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        silent.accept();
                    }
                } catch (IOException _) {
                    // closed
                }
            });
            Run other = start(configured("home-busy", busy, natsCmd), Map.of());
            check("start: something else on the port stops it and names the port", other.exit != 0 && other.err.contains(String.valueOf(busy)) && other.err.contains("not a NATS server"), other.all());
            accepting.interrupt();
        }

        Run unknown = start(configured("home-unknown", freePort(), natsCmd), Map.of(), "--bogus");
        check("start: an unknown option is refused with the known ones", unknown.exit == 2 && unknown.err.contains("--nats") && unknown.err.contains("--engine"), unknown.all());

        int stalePort = freePort();
        Path staleHome = configured("home-stale", stalePort, natsCmd);
        Files.createDirectories(staleHome.resolve("run"));
        Files.writeString(staleHome.resolve("run/nats.pid"), "999999");
        Run stale = start(staleHome, Map.of());
        check("start: a process id file whose process is gone is removed and the start goes on", stale.exit == 0 && stale.out.contains("stale nats.pid") && stale.out.contains("NATS started"), stale.all());

        Path silentNats = scratch.resolve("silent-nats.cmd");
        Files.writeString(silentNats, "@echo off" + System.lineSeparator() + "ping -n 40 127.0.0.1 > nul" + System.lineSeparator());
        int slowPort = freePort();
        Run slow = start(configured("home-slow", slowPort, silentNats), Map.of());
        check("start: a NATS that never answers is given up on after 15 seconds, stopped, and the exit status is not 0", slow.exit != 0 && slow.err.contains("did not answer") && !portOpen(slowPort), slow.all());
    }

    static void startNatsOnlyAndEngineOnly() throws Exception {
        int port = freePort();
        Path home = configured("home-only", port, natsCmd);
        Run engineOnly = start(home, Map.of(), "--engine");
        check("start --engine: says that no NATS answers, and runs the engine anyway", engineOnly.exit == 0 && engineOnly.out.contains("no NATS server answers") && engineOnly.out.contains("ENGINE AGENT_TOOL=claude")
                && !engineOnly.out.contains("NATS started") && !portOpen(port), engineOnly.all());

        Process only = startProcess(home, Map.of(), "--nats");
        try {
            waitForPort(port);
            check("start --nats: a server listens on the loopback address and no engine is started", portOpen(port));
            Run second = start(home, Map.of(), "--nats");
            check("start --nats: refuses when the port is already taken", second.exit != 0 && second.err.contains("already taken"), second.all());
        } finally {
            only.descendants().forEach(ProcessHandle::destroyForcibly);
            only.destroyForcibly();
        }
        waitForPortClosed(port);
        check("start --nats: ending the command ends the server", !portOpen(port));
    }

    // ---- stand-ins and helpers ----

    static void prepareStandIns() throws Exception {
        Path standIns = Files.createDirectories(scratch.resolve("standins"));
        for (String name : List.of("StandInNats.java", "StandInEngine.java")) {
            Files.copy(kit.resolve("tests/setup").resolve(name), standIns.resolve(name));
        }
        natsCmd = standIns.resolve("nats-server.cmd");
        Files.writeString(natsCmd, "@echo off" + System.lineSeparator() + "\"" + javaExe() + "\" \"%~dp0StandInNats.java\" %*" + System.lineSeparator());
        // the engine stand-in as a jar, compiled with the JDK that runs this test
        Path classes = Files.createDirectories(standIns.resolve("classes"));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null || compiler.run(null, null, null, "-d", classes.toString(), standIns.resolve("StandInEngine.java").toString()) != 0) {
            throw new IllegalStateException("the engine stand-in could not be compiled");
        }
        engineJar = standIns.resolve("engine.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "StandInEngine");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(engineJar), manifest)) {
            jar.putNextEntry(new ZipEntry("StandInEngine.class"));
            jar.write(Files.readAllBytes(classes.resolve("StandInEngine.class")));
            jar.closeEntry();
        }
    }

    /** A saved configuration written the way setup writes it, with the given NATS program. */
    static Path configured(String name, int port, Path nats) throws Exception {
        Path home = fresh(name);
        Path roots = fresh(name + "-repos");
        String text = "javaHome=" + esc(System.getProperty("java.home")) + System.lineSeparator() + "natsServer=" + esc(nats.toString()) + System.lineSeparator()
                + "natsPort=" + port + System.lineSeparator() + "engineJar=" + esc(engineJar.toString()) + System.lineSeparator() + "allowedRoots=" + esc(roots.toString())
                + System.lineSeparator() + "agentTool=claude" + System.lineSeparator() + "dataDir=" + esc(home.resolve("engine-data").toString()) + System.lineSeparator();
        Files.writeString(home.resolve("machine.properties"), text);
        return home;
    }

    /** The allowedRoots value as the properties file holds it (read the way Java reads it, so escapes do not matter). */
    static String savedRoots(Path home) throws IOException {
        java.util.Properties p = new java.util.Properties();
        try (var in = Files.newBufferedReader(home.resolve("machine.properties"), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p.getProperty("allowedRoots", "");
    }

    static String esc(String path) {
        return path.replace("\\", "\\\\");
    }

    static Run setup(Path home, Release release, String sha, String... args) throws Exception {
        return setup(home, release.base(), sha, args);
    }

    static Run setup(Path home, String baseUrl, String sha, String... args) throws Exception {
        return run(home, Map.of("APPMOD_NATS_BASE_URL", baseUrl, "APPMOD_NATS_VERSION", "vTEST", "APPMOD_NATS_SHA256", sha), args);
    }

    static Run run(Path home, Map<String, String> env, String... args) throws Exception {
        return run(home, env, List.of(), args);
    }

    static Run run(Path home, Map<String, String> env, List<String> jvmOptions, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaExe()));
        command.addAll(jvmOptions);
        command.add(kit.resolve("install/AppmodSetup.java").toString());
        command.addAll(List.of("--java-home", System.getProperty("java.home"), "--kit-dir", scratch.resolve("no-kit/kit").toString()));
        command.addAll(List.of(args));
        return execute(command, home, env);
    }

    static Run start(Path home, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaExe(), kit.resolve("install/AppmodStart.java").toString()));
        command.addAll(List.of(args));
        return execute(command, home, env);
    }

    static Process startProcess(Path home, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaExe(), kit.resolve("install/AppmodStart.java").toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(scratch.resolve("bg-" + (++runs) + ".out").toFile()).redirectError(scratch.resolve("bg-" + runs + ".err").toFile());
        builder.environment().put("APPMOD_HOME", home.toString());
        builder.environment().putAll(env);
        return builder.start();
    }

    static Run execute(List<String> command, Path home, Map<String, String> env) throws Exception {
        Path out = scratch.resolve("run-" + (++runs) + ".out");
        Path err = scratch.resolve("run-" + runs + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().put("APPMOD_HOME", home.toString());
        builder.environment().remove("APPMOD_NATS_VERSION");
        builder.environment().remove("APPMOD_NATS_SHA256");
        builder.environment().remove("APPMOD_NATS_BASE_URL");
        builder.environment().putAll(env);
        Process p = builder.start();
        if (!p.waitFor(150, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            throw new IllegalStateException("did not finish in 150 seconds: " + String.join(" ", command));
        }
        return new Run(p.exitValue(), lenient(out), lenient(err));
    }

    /** A release server that serves one archive for any path, and counts the requests. */
    static final class Release implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();

        Release(byte[] archive) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, archive.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(archive);
                }
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static byte[] zip(String entryName, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    static void waitForPort(int port) throws Exception {
        for (int i = 0; i < 80 && !portOpen(port); i++) {
            Thread.sleep(250);
        }
    }

    static void waitForPortClosed(int port) throws Exception {
        for (int i = 0; i < 40 && portOpen(port); i++) {
            Thread.sleep(250);
        }
    }

    static String javaExe() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }

    static Path fresh(String name) throws IOException {
        return Files.createDirectories(scratch.resolve(name));
    }

    static List<String> listing(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    static String lenient(Path file) throws IOException {
        return String.join("\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList());
    }

    static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (var paths = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        } catch (IOException _) {
            // a process that is still ending can hold a file for a moment; the temporary folder is cleaned by the system later
        }
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok    " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  -> " + detail.replace("\n", " | ")));
        }
    }
}
