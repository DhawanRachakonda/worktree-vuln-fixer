import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Dependency Security Advisor – deterministic core. Pure Java 21, no dependencies, runnable as a single file:
 *
 * <pre>
 *   java DependencyAdvisor.java scan &lt;projectDir&gt; [--out DIR] [--resolve] [--fail-on HIGH]
 *        [--extra-advisories DIR] [--nvd] [--osv-url URL] [--maven-repo URL] [--npm-registry URL] [--pypi-url URL]
 *   java DependencyAdvisor.java check &lt;Maven|npm|PyPI&gt; &lt;name&gt; &lt;version&gt;
 * </pre>
 *
 * <ol>
 * <li><b>Inventory</b> – Maven ({@code pom.xml}, optionally fully resolved with {@code mvn dependency:list}), Gradle
 * (declared), npm ({@code package-lock.json} incl. transitive, else {@code package.json}), PyPI
 * ({@code requirements*.txt}, {@code poetry.lock}, {@code Pipfile.lock}).</li>
 * <li><b>Vulnerabilities</b> – OSV.dev (aggregates GitHub Security Advisories, PyPA, npm, Maven advisories …),
 * optional NVD (National Vulnerability Database) CVSS enrichment, plus local advisories in OSV format (vendor or
 * internal security bulletins).</li>
 * <li><b>Safest upgrade</b> – lists real versions from the package registry, evaluates every candidate against
 * <i>all</i> known advisories of the package, and recommends the smallest non-vulnerable upgrade (prefers same
 * major → lowest compatibility risk), plus the latest safe version.</li>
 * <li><b>Impact</b> – counts source files that import the package (Java/Kotlin, JS/TS, Python) so reviewers and the
 * AI agent can judge reachability.</li>
 * </ol>
 * Output: {@code advisory.json} + {@code advisory.md}. The AI part (explaining impact, reading changelogs, applying and
 * verifying upgrades) is done by the {@code dependency-security-advisor} agent on top of this report.
 */
public final class DependencyAdvisor {

    // =================================================================== model

    public record Dependency(String ecosystem, String name, String version, boolean direct, String scope,
            String manifest) {
        public String key() {
            return ecosystem + ":" + name + "@" + version;
        }
    }

    public record Vulnerability(String id, List<String> aliases, String summary, String severity, Double cvssScore,
            String cvssVector, List<String> fixedVersions, List<String> references, String source) {
        public String cve() {
            return aliases.stream().filter(a -> a.startsWith("CVE-")).findFirst().orElse(id.startsWith("CVE-") ? id : null);
        }
    }

    public record Finding(Dependency dependency, List<Vulnerability> vulnerabilities, String maxSeverity,
            String recommendedVersion, String upgradeType, String compatibilityRisk, String latestSafeVersion,
            List<String> notes, int usageFiles, List<String> usageSamples) {
    }

    public record Report(String projectDir, int dependenciesScanned, List<Finding> findings, List<String> warnings) {

        public Map<String, Long> countsBySeverity() {
            Map<String, Long> m = new LinkedHashMap<>();
            for (String s : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")) {
                m.put(s, findings.stream().filter(f -> s.equals(f.maxSeverity())).count());
            }
            return m;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectDir", projectDir);
            m.put("dependenciesScanned", dependenciesScanned);
            m.put("vulnerableDependencies", findings.size());
            m.put("bySeverity", countsBySeverity());
            List<Object> fs = new ArrayList<>();
            for (Finding f : findings) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("ecosystem", f.dependency().ecosystem());
                fm.put("package", f.dependency().name());
                fm.put("currentVersion", f.dependency().version());
                fm.put("direct", f.dependency().direct());
                fm.put("scope", f.dependency().scope());
                fm.put("manifest", f.dependency().manifest());
                fm.put("maxSeverity", f.maxSeverity());
                fm.put("recommendedVersion", f.recommendedVersion());
                fm.put("upgradeType", f.upgradeType());
                fm.put("compatibilityRisk", f.compatibilityRisk());
                fm.put("latestSafeVersion", f.latestSafeVersion());
                fm.put("usageFiles", f.usageFiles());
                fm.put("usageSamples", f.usageSamples());
                fm.put("notes", f.notes());
                List<Object> vs = new ArrayList<>();
                for (Vulnerability v : f.vulnerabilities()) {
                    Map<String, Object> vm = new LinkedHashMap<>();
                    vm.put("id", v.id());
                    vm.put("cve", v.cve());
                    vm.put("aliases", v.aliases());
                    vm.put("severity", v.severity());
                    vm.put("cvssScore", v.cvssScore());
                    vm.put("cvssVector", v.cvssVector());
                    vm.put("summary", v.summary());
                    vm.put("fixedVersions", v.fixedVersions());
                    vm.put("references", v.references());
                    vm.put("source", v.source());
                    vs.add(vm);
                }
                fm.put("vulnerabilities", vs);
                fs.add(fm);
            }
            m.put("findings", fs);
            m.put("warnings", warnings);
            return m;
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder("# Dependency security advisory\n\n");
            sb.append("Scanned **").append(dependenciesScanned).append("** dependencies; **").append(findings.size())
                    .append("** vulnerable. ");
            countsBySeverity().forEach((k, v) -> {
                if (v > 0) {
                    sb.append(k).append(": ").append(v).append(". ");
                }
            });
            sb.append("\n\n");
            if (!findings.isEmpty()) {
                sb.append("| Severity | Package | Current | Advisories | Recommended | Upgrade | Compat. risk | Latest safe | Used in files |\n");
                sb.append("|---|---|---|---|---|---|---|---|---|\n");
                for (Finding f : findings) {
                    String ids = f.vulnerabilities().stream().map(v -> v.cve() != null ? v.cve() : v.id())
                            .distinct().limit(4).reduce((a, b) -> a + ", " + b).orElse("");
                    if (f.vulnerabilities().size() > 4) {
                        ids += " +" + (f.vulnerabilities().size() - 4);
                    }
                    sb.append("| ").append(f.maxSeverity()).append(" | `").append(f.dependency().name()).append("`")
                            .append(f.dependency().direct() ? "" : " _(transitive)_").append(" (")
                            .append(f.dependency().ecosystem()).append(") | ").append(f.dependency().version())
                            .append(" | ").append(ids).append(" | **")
                            .append(f.recommendedVersion() == null ? "none available" : f.recommendedVersion())
                            .append("** | ").append(nz(f.upgradeType())).append(" | ").append(nz(f.compatibilityRisk()))
                            .append(" | ").append(nz(f.latestSafeVersion())).append(" | ").append(f.usageFiles())
                            .append(" |\n");
                }
                sb.append("\n## Details\n");
                for (Finding f : findings) {
                    sb.append("\n### ").append(f.dependency().name()).append(' ').append(f.dependency().version())
                            .append(" → ").append(nz(f.recommendedVersion())).append("\n\n");
                    sb.append("- Manifest: `").append(f.dependency().manifest()).append("`, scope `")
                            .append(nz(f.dependency().scope())).append("`, ")
                            .append(f.dependency().direct() ? "direct" : "transitive").append(" dependency\n");
                    if (!f.usageSamples().isEmpty()) {
                        sb.append("- Imported in ").append(f.usageFiles()).append(" file(s), e.g. `")
                                .append(String.join("`, `", f.usageSamples())).append("`\n");
                    }
                    for (String n : f.notes()) {
                        sb.append("- ").append(n).append('\n');
                    }
                    for (Vulnerability v : f.vulnerabilities()) {
                        sb.append("- **").append(v.id()).append("**")
                                .append(v.cve() != null && !v.cve().equals(v.id()) ? " (" + v.cve() + ")" : "")
                                .append(" – ").append(v.severity())
                                .append(v.cvssScore() == null ? "" : " (CVSS " + v.cvssScore() + ")").append(": ")
                                .append(nz(v.summary())).append(v.fixedVersions().isEmpty() ? ""
                                        : " Fixed in: " + String.join(", ", v.fixedVersions()) + ".")
                                .append('\n');
                    }
                }
            }
            if (!warnings.isEmpty()) {
                sb.append("\n## Warnings\n\n");
                warnings.forEach(w -> sb.append("- ").append(w).append('\n'));
            }
            return sb.toString();
        }

        private static String nz(String s) {
            return s == null ? "–" : s;
        }
    }

    public record Options(boolean resolveMaven, boolean useNvd, String osvUrl, String mavenRepo, String npmRegistry,
            String pypiUrl, Path extraAdvisoriesDir, Duration httpTimeout) {

        public static Options defaults() {
            return new Options(false, System.getenv("NVD_API_KEY") != null, "https://api.osv.dev",
                    "https://repo1.maven.org/maven2", "https://registry.npmjs.org", "https://pypi.org/pypi", null,
                    Duration.ofSeconds(30));
        }

        public Options withResolveMaven(boolean v) {
            return new Options(v, useNvd, osvUrl, mavenRepo, npmRegistry, pypiUrl, extraAdvisoriesDir, httpTimeout);
        }

        public Options withExtraAdvisories(Path dir) {
            return new Options(resolveMaven, useNvd, osvUrl, mavenRepo, npmRegistry, pypiUrl, dir, httpTimeout);
        }
    }

    /** Pluggable sources (HTTP by default; tests use fakes). */
    public interface AdvisorySource {
        /** All advisories (OSV JSON objects) for a package, regardless of version. */
        List<Map<String, Object>> advisoriesFor(String ecosystem, String name);
    }

    public interface VersionSource {
        /** Published (non-yanked) versions of a package. */
        List<String> versions(String ecosystem, String name);
    }

    // =================================================================== CLI

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("scan")) {
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Options o = Options.defaults();
            Path out = root.resolve(".appmod").resolve("advisory");
            String failOn = null;
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Path.of(args[++i]);
                    case "--resolve" -> o = o.withResolveMaven(true);
                    case "--fail-on" -> failOn = args[++i].toUpperCase(Locale.ROOT);
                    case "--extra-advisories" -> o = o.withExtraAdvisories(Path.of(args[++i]));
                    case "--nvd" -> o = new Options(o.resolveMaven(), true, o.osvUrl(), o.mavenRepo(), o.npmRegistry(), o.pypiUrl(), o.extraAdvisoriesDir(), o.httpTimeout());
                    case "--osv-url" -> o = new Options(o.resolveMaven(), o.useNvd(), args[++i], o.mavenRepo(), o.npmRegistry(), o.pypiUrl(), o.extraAdvisoriesDir(), o.httpTimeout());
                    case "--maven-repo" -> o = new Options(o.resolveMaven(), o.useNvd(), o.osvUrl(), args[++i], o.npmRegistry(), o.pypiUrl(), o.extraAdvisoriesDir(), o.httpTimeout());
                    case "--npm-registry" -> o = new Options(o.resolveMaven(), o.useNvd(), o.osvUrl(), o.mavenRepo(), args[++i], o.pypiUrl(), o.extraAdvisoriesDir(), o.httpTimeout());
                    case "--pypi-url" -> o = new Options(o.resolveMaven(), o.useNvd(), o.osvUrl(), o.mavenRepo(), o.npmRegistry(), args[++i], o.extraAdvisoriesDir(), o.httpTimeout());
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            Report r = scan(root, o, System.err::println);
            write(r, out);
            System.out.println(r.toMarkdown());
            System.err.println("Report written to " + out.toAbsolutePath());
            if (failOn != null && SEVERITY_RANK.getOrDefault(maxSeverity(r), 0) >= SEVERITY_RANK.getOrDefault(failOn, 9)) {
                System.exit(1);
            }
            return;
        }
        if (args.length == 4 && args[0].equals("check")) {
            Options o = Options.defaults();
            Http http = new Http(o.httpTimeout());
            Dependency d = new Dependency(args[1], args[2], args[3], true, "", "cli");
            Finding f = analyse(d, new OsvHttp(http, o), new RegistryHttp(http, o), o, null, List.of(), null);
            System.out.println(f == null ? "No known vulnerabilities for " + d.key()
                    : new Report("-", 1, List.of(f), List.of()).toMarkdown());
            return;
        }
        System.err.println("""
                usage:
                  java DependencyAdvisor.java scan <projectDir> [--out DIR] [--resolve] [--fail-on CRITICAL|HIGH|MEDIUM|LOW]
                       [--extra-advisories DIR] [--nvd] [--osv-url URL] [--maven-repo URL] [--npm-registry URL] [--pypi-url URL]
                  java DependencyAdvisor.java check <Maven|npm|PyPI> <name> <version>
                """);
        System.exit(2);
    }

    // =================================================================== scan

    public static Report scan(Path root, Options options, java.util.function.Consumer<String> log) {
        Http http = new Http(options.httpTimeout());
        return scan(root, options, new OsvHttp(http, options), new RegistryHttp(http, options),
                options.useNvd() ? new Nvd(http) : null, log);
    }

    public static Report scan(Path root, Options options, AdvisorySource advisories, VersionSource versions, Nvd nvd,
            java.util.function.Consumer<String> log) {
        List<String> warnings = new ArrayList<>();
        List<Dependency> deps = Inventory.collect(root, options, warnings, log);
        log.accept("[advisor] " + deps.size() + " dependencies found");
        List<Map<String, Object>> extra = loadExtraAdvisories(options.extraAdvisoriesDir(), warnings);
        SourceIndex sources = SourceIndex.build(root);
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Dependency d : deps) {
            if (!seen.add(d.key()) || d.version() == null || d.version().isBlank()) {
                continue;
            }
            try {
                Finding f = analyse(d, advisories, versions, options, nvd, extra, sources);
                if (f != null) {
                    findings.add(f);
                    log.accept("[advisor] " + f.maxSeverity() + " " + d.key() + " -> " + f.recommendedVersion());
                }
            } catch (RuntimeException e) {
                warnings.add("Could not analyse " + d.key() + ": " + e.getMessage());
            }
        }
        findings.sort(Comparator.comparing((Finding f) -> -SEVERITY_RANK.getOrDefault(f.maxSeverity(), 0))
                .thenComparing(f -> f.dependency().name()));
        return new Report(root.toString(), seen.size(), findings, warnings);
    }

    /** Dependency inventory only (no network): Maven, Gradle, npm and PyPI manifests/lock files. */
    public static List<Dependency> inventory(Path root, Options options, List<String> warnings) {
        return Inventory.collect(root, options, warnings, line -> { });
    }

    public static void write(Report r, Path outDir) {
        try {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("advisory.json"), Json.write(r.toMap(), true));
            Files.writeString(outDir.resolve("advisory.md"), r.toMarkdown());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Finding analyse(Dependency d, AdvisorySource advisories, VersionSource versions, Options options, Nvd nvd,
            List<Map<String, Object>> extra, SourceIndex sources) {
        List<Map<String, Object>> all = new ArrayList<>(advisories.advisoriesFor(d.ecosystem(), d.name()));
        for (Map<String, Object> e : extra) {
            if (!affectedEntries(e, d.ecosystem(), d.name()).isEmpty()) {
                all.add(e);
            }
        }
        List<Map<String, Object>> affecting = all.stream()
                .filter(a -> isAffected(a, d.ecosystem(), d.name(), d.version())).toList();
        if (affecting.isEmpty()) {
            return null;
        }
        List<Vulnerability> vulns = new ArrayList<>();
        for (Map<String, Object> a : affecting) {
            vulns.add(toVulnerability(a, d, nvd));
        }
        vulns.sort(Comparator.comparing((Vulnerability v) -> -SEVERITY_RANK.getOrDefault(v.severity(), 0)));
        String maxSeverity = vulns.get(0).severity();

        List<String> notes = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        try {
            for (String v : versions.versions(d.ecosystem(), d.name())) {
                if (!Versions.isPrerelease(v) && Versions.compare(v, d.version()) > 0) {
                    candidates.add(v);
                }
            }
        } catch (RuntimeException e) {
            notes.add("Registry lookup failed (" + e.getMessage() + "); recommendation based on advisory fix versions only.");
        }
        candidates.sort(Versions::compare);
        String recommended = null;
        String latestSafe = null;
        String currentMajor = Versions.major(d.version());
        for (String c : candidates) {
            boolean safe = all.stream().noneMatch(a -> isAffected(a, d.ecosystem(), d.name(), c));
            if (safe) {
                latestSafe = c;
                if (recommended == null && Versions.major(c).equals(currentMajor)) {
                    recommended = c;
                }
            }
        }
        if (recommended == null && latestSafe != null) {
            // no safe version in the current major line: smallest safe version overall
            for (String c : candidates) {
                if (all.stream().noneMatch(a -> isAffected(a, d.ecosystem(), d.name(), c))) {
                    recommended = c;
                    break;
                }
            }
            notes.add("No non-vulnerable release exists in major version " + currentMajor
                    + " – a major upgrade is required; expect breaking API changes.");
        }
        if (candidates.isEmpty()) {
            // fall back to advisory data
            recommended = vulns.stream().flatMap(v -> v.fixedVersions().stream()).max(Versions::compare).orElse(null);
            if (recommended != null) {
                notes.add("Recommended version taken from advisory fix data (registry versions unavailable).");
            }
        }
        if (recommended == null) {
            notes.add("No fixed version is published. Consider removing/replacing the package or mitigating.");
        }
        String upgradeType = recommended == null ? null : Versions.delta(d.version(), recommended);
        String risk = upgradeType == null ? null : switch (upgradeType) {
            case "patch" -> "LOW";
            case "minor" -> "MEDIUM";
            default -> "HIGH";
        };
        if (!d.direct()) {
            notes.add(switch (d.ecosystem()) {
                case "Maven" -> "Transitive: pin it in <dependencyManagement> (or upgrade the direct dependency that brings it in).";
                case "npm" -> "Transitive: add an \"overrides\" entry in package.json or upgrade the parent package.";
                default -> "Transitive: add a constraint/pin or upgrade the parent package.";
            });
        }
        int usage = 0;
        List<String> samples = List.of();
        if (sources != null) {
            List<String> files = sources.filesImporting(d);
            usage = files.size();
            samples = files.stream().limit(3).toList();
            if (usage == 0 && d.direct()) {
                notes.add("No source file imports this package directly – it may be unused (candidate for removal) "
                        + "or used via reflection/configuration.");
            }
        }
        return new Finding(d, vulns, maxSeverity, recommended, upgradeType, risk, latestSafe, notes, usage, samples);
    }

    // =================================================================== OSV evaluation

    private static final Map<String, Integer> SEVERITY_RANK = Map.of("CRITICAL", 4, "HIGH", 3, "MEDIUM", 2,
            "MODERATE", 2, "LOW", 1, "UNKNOWN", 0);

    static String maxSeverity(Report r) {
        return r.findings().isEmpty() ? "NONE" : r.findings().get(0).maxSeverity();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> affectedEntries(Map<String, Object> advisory, String ecosystem, String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) advisory.getOrDefault("affected", List.of())) {
            Map<String, Object> a = (Map<String, Object>) o;
            Map<String, Object> pkg = (Map<String, Object>) a.getOrDefault("package", Map.of());
            if (ecosystem.equalsIgnoreCase(String.valueOf(pkg.get("ecosystem")))
                    && normaliseName(ecosystem, name).equals(normaliseName(ecosystem, String.valueOf(pkg.get("name"))))) {
                out.add(a);
            }
        }
        return out;
    }

    /** OSV semantics: explicit {@code versions} list, or ECOSYSTEM/SEMVER ranges with introduced/fixed/last_affected. */
    @SuppressWarnings("unchecked")
    static boolean isAffected(Map<String, Object> advisory, String ecosystem, String name, String version) {
        if (advisory.get("withdrawn") != null) {
            return false;
        }
        for (Map<String, Object> a : affectedEntries(advisory, ecosystem, name)) {
            List<Object> versions = (List<Object>) a.getOrDefault("versions", List.of());
            if (versions.stream().anyMatch(v -> String.valueOf(v).equals(version))) {
                return true;
            }
            for (Object ro : (List<Object>) a.getOrDefault("ranges", List.of())) {
                Map<String, Object> range = (Map<String, Object>) ro;
                String type = String.valueOf(range.get("type"));
                if (!type.equals("ECOSYSTEM") && !type.equals("SEMVER")) {
                    continue;
                }
                String introduced = null;
                for (Object eo : (List<Object>) range.getOrDefault("events", List.of())) {
                    Map<String, Object> ev = (Map<String, Object>) eo;
                    if (ev.containsKey("introduced")) {
                        introduced = String.valueOf(ev.get("introduced"));
                    } else if (ev.containsKey("fixed") && introduced != null) {
                        if (geIntroduced(version, introduced) && Versions.compare(version, String.valueOf(ev.get("fixed"))) < 0) {
                            return true;
                        }
                        introduced = null;
                    } else if (ev.containsKey("last_affected") && introduced != null) {
                        if (geIntroduced(version, introduced)
                                && Versions.compare(version, String.valueOf(ev.get("last_affected"))) <= 0) {
                            return true;
                        }
                        introduced = null;
                    } else if (ev.containsKey("limit")) {
                        // limit only bounds the search for git ranges; ignore
                        continue;
                    }
                }
                if (introduced != null && geIntroduced(version, introduced)) {
                    return true; // open range: no fix yet
                }
            }
        }
        return false;
    }

    private static boolean geIntroduced(String version, String introduced) {
        return introduced.equals("0") || Versions.compare(version, introduced) >= 0;
    }

    @SuppressWarnings("unchecked")
    static Vulnerability toVulnerability(Map<String, Object> a, Dependency d, Nvd nvd) {
        String id = String.valueOf(a.get("id"));
        List<String> aliases = ((List<Object>) a.getOrDefault("aliases", List.of())).stream().map(String::valueOf).toList();
        String summary = a.get("summary") != null ? String.valueOf(a.get("summary"))
                : a.get("details") == null ? "" : firstSentence(String.valueOf(a.get("details")));
        String vector = null;
        Double score = null;
        for (Object so : (List<Object>) a.getOrDefault("severity", List.of())) {
            Map<String, Object> s = (Map<String, Object>) so;
            String sv = String.valueOf(s.get("score"));
            if (String.valueOf(s.get("type")).equals("CVSS_V3") && sv.startsWith("CVSS:3")) {
                vector = sv;
                score = Cvss3.baseScore(sv);
            } else if (vector == null && String.valueOf(s.get("type")).startsWith("CVSS")) {
                vector = sv;
            }
        }
        String severity = null;
        Object dbs = ((Map<String, Object>) a.getOrDefault("database_specific", Map.of())).get("severity");
        if (dbs != null) {
            severity = String.valueOf(dbs).toUpperCase(Locale.ROOT);
        }
        for (Map<String, Object> aff : affectedEntries(a, d.ecosystem(), d.name())) {
            Object es = ((Map<String, Object>) aff.getOrDefault("ecosystem_specific", Map.of())).get("severity");
            if (severity == null && es != null) {
                severity = String.valueOf(es).toUpperCase(Locale.ROOT);
            }
        }
        if (score == null && nvd != null) {
            String cve = aliases.stream().filter(x -> x.startsWith("CVE-")).findFirst().orElse(id.startsWith("CVE-") ? id : null);
            if (cve != null) {
                double[] nv = nvd.cvss(cve);
                if (nv != null) {
                    score = nv[0];
                }
            }
        }
        if (severity == null && score != null) {
            severity = Cvss3.rating(score); // advisory database rating wins; CVSS only fills gaps
        }
        if (severity == null) {
            severity = "UNKNOWN";
        }
        if (severity.equals("MODERATE")) {
            severity = "MEDIUM";
        }
        List<String> fixed = new ArrayList<>();
        for (Map<String, Object> aff : affectedEntries(a, d.ecosystem(), d.name())) {
            for (Object ro : (List<Object>) aff.getOrDefault("ranges", List.of())) {
                for (Object eo : (List<Object>) ((Map<String, Object>) ro).getOrDefault("events", List.of())) {
                    Object f = ((Map<String, Object>) eo).get("fixed");
                    if (f != null && !fixed.contains(String.valueOf(f))) {
                        fixed.add(String.valueOf(f));
                    }
                }
            }
        }
        List<String> refs = ((List<Object>) a.getOrDefault("references", List.of())).stream()
                .map(r -> String.valueOf(((Map<String, Object>) r).get("url"))).limit(5).toList();
        String source = String.valueOf(((Map<String, Object>) a.getOrDefault("database_specific", Map.of()))
                .getOrDefault("source", id.startsWith("GHSA") ? "GitHub Security Advisories" : "OSV"));
        return new Vulnerability(id, aliases, summary, severity, score, vector, fixed, refs, source);
    }

    private static String firstSentence(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        int dot = t.indexOf(". ");
        return dot > 0 && dot < 300 ? t.substring(0, dot + 1) : t.substring(0, Math.min(300, t.length()));
    }

    static String normaliseName(String ecosystem, String name) {
        return ecosystem.equalsIgnoreCase("PyPI") ? name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-") : name;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> loadExtraAdvisories(Path dir, List<String> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    out.add((Map<String, Object>) Json.parse(Files.readString(p)));
                } catch (RuntimeException e) {
                    warnings.add("Invalid advisory file " + p.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            warnings.add("Cannot read " + dir + ": " + e.getMessage());
        }
        return out;
    }

    // =================================================================== versions

    /** Ecosystem-agnostic version ordering close to Maven ComparableVersion / semver / PEP 440 for common cases. */
    public static final class Versions {
        private static final Pattern PRE = Pattern.compile(
                "(?i).*([-.]?(alpha|beta|milestone|rc|cr|snapshot|preview|ea|dev|pre|next|canary|nightly)[-.]?\\d*|[-.]m\\d+|\\d(a|b|rc)\\d+|-\\w+\\.\\d+).*");

        public static boolean isPrerelease(String v) {
            return PRE.matcher(v).matches();
        }

        public static String major(String v) {
            String[] p = v.replaceFirst("^[vV]", "").split("[.\\-+]");
            return p.length == 0 ? v : p[0];
        }

        public static String delta(String from, String to) {
            List<Object> a = tokens(from);
            List<Object> b = tokens(to);
            if (!Objects.equals(get(a, 0), get(b, 0))) {
                return "major";
            }
            if (!Objects.equals(get(a, 1), get(b, 1))) {
                return "minor";
            }
            return "patch";
        }

        private static Object get(List<Object> l, int i) {
            return i < l.size() ? l.get(i) : 0L;
        }

        public static int compare(String a, String b) {
            List<Object> x = tokens(a);
            List<Object> y = tokens(b);
            int n = Math.max(x.size(), y.size());
            for (int i = 0; i < n; i++) {
                Object p = i < x.size() ? x.get(i) : null;
                Object q = i < y.size() ? y.get(i) : null;
                int c = compareToken(p, q);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }

        private static int compareToken(Object p, Object q) {
            if (p == null && q == null) {
                return 0;
            }
            if (p == null) {
                // missing segment: behaves like 0 for numbers and like a plain release for qualifiers
                return q instanceof Long l ? Long.compare(0, l) : Integer.compare(0, qualifierRank((String) q));
            }
            if (q == null) {
                return -compareToken(null, p);
            }
            if (p instanceof Long l1 && q instanceof Long l2) {
                return Long.compare(l1, l2);
            }
            if (p instanceof Long) {
                return 1; // 1.0.1 > 1.0-rc
            }
            if (q instanceof Long) {
                return -1;
            }
            int r = Integer.compare(qualifierRank((String) p), qualifierRank((String) q));
            return r != 0 ? r : ((String) p).compareTo((String) q);
        }

        /** Negative = before a release (alpha, rc…), 0 = release markers, positive = after (sp, post). */
        private static int qualifierRank(String s) {
            return switch (s) {
                case "dev", "snapshot" -> -6;
                case "alpha", "a" -> -5;
                case "beta", "b" -> -4;
                case "milestone", "m" -> -3;
                case "rc", "cr", "preview", "pre" -> -2;
                case "ga", "final", "release" -> 0;
                case "sp", "post", "p" -> 1;
                default -> -1;
            };
        }

        static List<Object> tokens(String v) {
            List<Object> out = new ArrayList<>();
            Matcher m = Pattern.compile("(\\d+)|([a-zA-Z]+)").matcher(v.replaceFirst("^[vV]", "").replaceAll("\\+.*$", ""));
            while (m.find()) {
                if (m.group(1) != null) {
                    out.add(Long.parseLong(m.group(1).length() > 18 ? m.group(1).substring(0, 18) : m.group(1)));
                } else {
                    String q = m.group(2).toLowerCase(Locale.ROOT);
                    if (!q.equals("ga") && !q.equals("final") && !q.equals("release")) {
                        out.add(q);
                    }
                }
            }
            // trim trailing zeros so 1.0 == 1.0.0
            while (out.size() > 1 && out.get(out.size() - 1) instanceof Long l && l == 0) {
                out.remove(out.size() - 1);
            }
            return out;
        }
    }

    // =================================================================== CVSS 3.x calculator

    /** CVSS (Common Vulnerability Scoring System) v3.0/3.1 base score from a vector string. */
    public static final class Cvss3 {
        public static Double baseScore(String vector) {
            Map<String, String> m = new HashMap<>();
            for (String part : vector.split("/")) {
                String[] kv = part.split(":");
                if (kv.length == 2) {
                    m.put(kv[0], kv[1]);
                }
            }
            try {
                double av = switch (m.get("AV")) { case "N" -> 0.85; case "A" -> 0.62; case "L" -> 0.55; default -> 0.2; };
                double ac = m.get("AC").equals("L") ? 0.77 : 0.44;
                boolean changed = m.get("S").equals("C");
                double pr = switch (m.get("PR")) {
                    case "N" -> 0.85;
                    case "L" -> changed ? 0.68 : 0.62;
                    default -> changed ? 0.5 : 0.27;
                };
                double ui = m.get("UI").equals("N") ? 0.85 : 0.62;
                double c = cia(m.get("C"));
                double i = cia(m.get("I"));
                double a = cia(m.get("A"));
                double iss = 1 - (1 - c) * (1 - i) * (1 - a);
                double impact = changed ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15) : 6.42 * iss;
                double exploitability = 8.22 * av * ac * pr * ui;
                if (impact <= 0) {
                    return 0.0;
                }
                double raw = changed ? Math.min(1.08 * (impact + exploitability), 10) : Math.min(impact + exploitability, 10);
                return roundUp(raw);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static double cia(String v) {
            return switch (v) { case "H" -> 0.56; case "L" -> 0.22; default -> 0.0; };
        }

        private static double roundUp(double d) {
            long i = Math.round(d * 100000);
            return i % 10000 == 0 ? i / 100000.0 : (Math.floor(i / 10000.0) + 1) / 10.0;
        }

        public static String rating(double score) {
            if (score >= 9.0) {
                return "CRITICAL";
            }
            if (score >= 7.0) {
                return "HIGH";
            }
            if (score >= 4.0) {
                return "MEDIUM";
            }
            return score > 0 ? "LOW" : "NONE";
        }
    }

    // =================================================================== inventory

    static final class Inventory {
        private static final Set<String> SKIP = Set.of(".git", "node_modules", "target", "build", ".gradle", ".venv",
                "venv", "__pycache__", ".appmod", "dist", ".idea");

        static List<Dependency> collect(Path root, Options options, List<String> warnings,
                java.util.function.Consumer<String> log) {
            List<Path> poms = new ArrayList<>();
            List<Path> gradles = new ArrayList<>();
            List<Path> npmLocks = new ArrayList<>();
            List<Path> npmManifests = new ArrayList<>();
            List<Path> pyReqs = new ArrayList<>();
            List<Path> pyLocks = new ArrayList<>();
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return !dir.equals(root) && SKIP.contains(dir.getFileName().toString())
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                        String n = f.getFileName().toString();
                        if (n.equals("pom.xml")) {
                            poms.add(f);
                        } else if (n.equals("build.gradle") || n.equals("build.gradle.kts")) {
                            gradles.add(f);
                        } else if (n.equals("package-lock.json")) {
                            npmLocks.add(f);
                        } else if (n.equals("package.json")) {
                            npmManifests.add(f);
                        } else if (n.matches("requirements.*\\.txt")) {
                            pyReqs.add(f);
                        } else if (n.equals("poetry.lock") || n.equals("Pipfile.lock")) {
                            pyLocks.add(f);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            List<Dependency> out = new ArrayList<>();
            boolean resolved = false;
            if (options.resolveMaven() && !poms.isEmpty()) {
                List<Dependency> r = mavenResolved(root, warnings, log);
                if (!r.isEmpty()) {
                    out.addAll(r);
                    resolved = true;
                }
            }
            if (!resolved) {
                Set<String> internal = new LinkedHashSet<>();
                List<Dependency> declared = new ArrayList<>();
                for (Path p : poms) {
                    declared.addAll(mavenDeclared(root, p, internal, warnings));
                }
                declared.stream().filter(d -> !internal.contains(d.name())).forEach(out::add);
            }
            for (Path g : gradles) {
                out.addAll(gradleDeclared(root, g));
            }
            Set<Path> lockDirs = new LinkedHashSet<>();
            for (Path l : npmLocks) {
                lockDirs.add(l.getParent());
                out.addAll(npmLock(root, l));
            }
            for (Path m : npmManifests) {
                if (!lockDirs.contains(m.getParent())) {
                    out.addAll(npmManifest(root, m, warnings));
                }
            }
            for (Path l : pyLocks) {
                out.addAll(pyLock(root, l));
            }
            for (Path r : pyReqs) {
                out.addAll(requirements(root, r, warnings));
            }
            return out;
        }

        static List<Dependency> mavenResolved(Path root, List<String> warnings, java.util.function.Consumer<String> log) {
            try {
                Path outFile = Files.createTempFile("appmod-deps", ".txt");
                boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                String mvn = Files.exists(root.resolve(win ? "mvnw.cmd" : "mvnw"))
                        ? root.resolve(win ? "mvnw.cmd" : "mvnw").toString() : "mvn";
                List<String> cmd = new ArrayList<>();
                if (win) {
                    cmd.addAll(List.of("cmd", "/c"));
                }
                cmd.addAll(List.of(mvn, "-B", "-q", "dependency:list", "-DoutputFile=" + outFile,
                        "-DappendOutput=true", "-DexcludeTransitive=false"));
                log.accept("[advisor] $ " + String.join(" ", cmd));
                Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!p.waitFor(15, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0) {
                    warnings.add("mvn dependency:list failed; falling back to declared dependencies");
                    return List.of();
                }
                List<Dependency> out = new ArrayList<>();
                Pattern line = Pattern.compile("^\\s*([\\w.\\-]+):([\\w.\\-]+):[\\w.\\-]+:(?:[\\w.\\-]+:)?([\\w.\\-]+):(\\w+)");
                for (String l : Files.readAllLines(outFile)) {
                    Matcher m = line.matcher(l);
                    if (m.find()) {
                        out.add(new Dependency("Maven", m.group(1) + ":" + m.group(2), m.group(3), false, m.group(4),
                                "pom.xml (resolved)"));
                    }
                }
                Files.deleteIfExists(outFile);
                // mark direct ones using declared deps
                Set<String> direct = new LinkedHashSet<>();
                Files.walk(root, 6).filter(f -> f.getFileName().toString().equals("pom.xml"))
                        .forEach(f -> mavenDeclared(root, f, new LinkedHashSet<>(), new ArrayList<>())
                                .forEach(d -> direct.add(d.name())));
                return out.stream().map(d -> direct.contains(d.name())
                        ? new Dependency(d.ecosystem(), d.name(), d.version(), true, d.scope(), d.manifest()) : d).toList();
            } catch (IOException e) {
                warnings.add("mvn dependency:list not available: " + e.getMessage());
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }

        static List<Dependency> mavenDeclared(Path root, Path pom, Set<String> internalModules, List<String> warnings) {
            List<Dependency> out = new ArrayList<>();
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                Document doc = f.newDocumentBuilder().parse(pom.toFile());
                Element project = doc.getDocumentElement();
                Map<String, String> props = new HashMap<>();
                Element pe = child(project, "properties");
                if (pe != null) {
                    NodeList nl = pe.getChildNodes();
                    for (int i = 0; i < nl.getLength(); i++) {
                        if (nl.item(i) instanceof Element e) {
                            props.put(e.getTagName(), e.getTextContent().trim());
                        }
                    }
                }
                Element parent = child(project, "parent");
                String groupId = text(project, "groupId");
                if (groupId == null && parent != null) {
                    groupId = text(parent, "groupId");
                }
                String version = text(project, "version");
                if (version == null && parent != null) {
                    version = text(parent, "version");
                }
                props.put("project.version", version);
                internalModules.add(groupId + ":" + text(project, "artifactId"));
                String rel = root.relativize(pom).toString().replace('\\', '/');
                if (parent != null && text(parent, "version") != null) {
                    out.add(new Dependency("Maven", text(parent, "groupId") + ":" + text(parent, "artifactId"),
                            text(parent, "version"), true, "parent", rel));
                }
                for (String section : List.of("dependencies", "dependencyManagement")) {
                    Element deps = child(project, section);
                    if (deps != null && section.equals("dependencyManagement")) {
                        deps = child(deps, "dependencies");
                    }
                    if (deps == null) {
                        continue;
                    }
                    NodeList dl = deps.getChildNodes();
                    for (int i = 0; i < dl.getLength(); i++) {
                        if (!(dl.item(i) instanceof Element d) || !d.getTagName().equals("dependency")) {
                            continue;
                        }
                        String v = resolve(text(d, "version"), props);
                        if (v == null || v.contains("${")) {
                            continue; // managed by a BOM/parent – resolve with --resolve for full coverage
                        }
                        String scope = text(d, "scope");
                        out.add(new Dependency("Maven", resolve(text(d, "groupId"), props) + ":" + resolve(text(d, "artifactId"), props),
                                v, true, scope == null ? (section.equals("dependencies") ? "compile" : "managed") : scope, rel));
                    }
                }
            } catch (Exception e) {
                warnings.add("Cannot parse " + pom + ": " + e.getMessage());
            }
            return out;
        }

        private static Element child(Element parent, String tag) {
            NodeList nl = parent.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n instanceof Element e && e.getTagName().equals(tag)) {
                    return e;
                }
            }
            return null;
        }

        private static String text(Element parent, String tag) {
            Element e = child(parent, tag);
            return e == null ? null : e.getTextContent().trim();
        }

        private static String resolve(String v, Map<String, String> props) {
            if (v == null) {
                return null;
            }
            Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(v);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group(0))));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        static List<Dependency> gradleDeclared(Path root, Path file) {
            List<Dependency> out = new ArrayList<>();
            Matcher m = Pattern.compile("(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|compile)\\s*\\(?\\s*['\"]([^'\":]+):([^'\":]+):([^'\"@]+)['\"]")
                    .matcher(readString(file));
            String rel = root.relativize(file).toString().replace('\\', '/');
            while (m.find()) {
                if (!m.group(4).contains("$")) {
                    out.add(new Dependency("Maven", m.group(2) + ":" + m.group(3), m.group(4), true,
                            m.group(1).startsWith("test") ? "test" : "compile", rel));
                }
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        static List<Dependency> npmLock(Path root, Path lock) {
            List<Dependency> out = new ArrayList<>();
            Map<String, Object> json = (Map<String, Object>) Json.parse(readString(lock));
            String rel = root.relativize(lock).toString().replace('\\', '/');
            Map<String, Object> packages = (Map<String, Object>) json.get("packages");
            if (packages != null) { // lockfile v2/v3
                Map<String, Object> rootPkg = (Map<String, Object>) packages.getOrDefault("", Map.of());
                Set<String> direct = new LinkedHashSet<>();
                for (String k : List.of("dependencies", "devDependencies", "optionalDependencies")) {
                    direct.addAll(((Map<String, Object>) rootPkg.getOrDefault(k, Map.of())).keySet());
                }
                Set<String> dev = ((Map<String, Object>) rootPkg.getOrDefault("devDependencies", Map.of())).keySet();
                for (Map.Entry<String, Object> e : packages.entrySet()) {
                    String path = e.getKey();
                    int idx = path.lastIndexOf("node_modules/");
                    if (idx < 0) {
                        continue;
                    }
                    String name = path.substring(idx + "node_modules/".length());
                    Map<String, Object> p = (Map<String, Object>) e.getValue();
                    Object v = p.get("version");
                    if (v == null || Boolean.TRUE.equals(p.get("link"))) {
                        continue;
                    }
                    boolean isDirect = direct.contains(name) && path.equals("node_modules/" + name);
                    out.add(new Dependency("npm", name, String.valueOf(v), isDirect,
                            Boolean.TRUE.equals(p.get("dev")) || dev.contains(name) ? "dev" : "prod", rel));
                }
            } else { // lockfile v1
                Map<String, Object> deps = (Map<String, Object>) json.getOrDefault("dependencies", Map.of());
                deps.forEach((name, o) -> {
                    Map<String, Object> p = (Map<String, Object>) o;
                    out.add(new Dependency("npm", name, String.valueOf(p.get("version")), true,
                            Boolean.TRUE.equals(p.get("dev")) ? "dev" : "prod", rel));
                });
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        static List<Dependency> npmManifest(Path root, Path manifest, List<String> warnings) {
            List<Dependency> out = new ArrayList<>();
            Map<String, Object> json = (Map<String, Object>) Json.parse(readString(manifest));
            String rel = root.relativize(manifest).toString().replace('\\', '/');
            boolean approximated = false;
            for (String k : List.of("dependencies", "devDependencies")) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) json.getOrDefault(k, Map.of())).entrySet()) {
                    String range = String.valueOf(e.getValue());
                    Matcher m = Pattern.compile("(\\d+(?:\\.\\d+){0,2}(?:-[\\w.]+)?)").matcher(range);
                    if (m.find()) {
                        approximated |= !range.equals(m.group(1));
                        out.add(new Dependency("npm", e.getKey(), m.group(1), true, k.startsWith("dev") ? "dev" : "prod", rel));
                    }
                }
            }
            if (approximated) {
                warnings.add(rel + " has no package-lock.json: version ranges were approximated by their lower bound.");
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        static List<Dependency> pyLock(Path root, Path lock) {
            List<Dependency> out = new ArrayList<>();
            String rel = root.relativize(lock).toString().replace('\\', '/');
            String text = readString(lock);
            if (lock.getFileName().toString().equals("Pipfile.lock")) {
                Map<String, Object> json = (Map<String, Object>) Json.parse(text);
                for (String section : List.of("default", "develop")) {
                    ((Map<String, Object>) json.getOrDefault(section, Map.of())).forEach((name, o) -> {
                        Object v = ((Map<String, Object>) o).get("version");
                        if (v != null) {
                            out.add(new Dependency("PyPI", name, String.valueOf(v).replaceFirst("^==", ""), false,
                                    section.equals("default") ? "prod" : "dev", rel));
                        }
                    });
                }
            } else { // poetry.lock (TOML) – [[package]] blocks
                Matcher m = Pattern.compile("(?ms)^\\[\\[package]]\\s*\\R(.*?)(?=^\\[\\[package]]|\\z)").matcher(text);
                while (m.find()) {
                    Matcher n = Pattern.compile("(?m)^name\\s*=\\s*\"([^\"]+)\"").matcher(m.group(1));
                    Matcher v = Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"").matcher(m.group(1));
                    if (n.find() && v.find()) {
                        out.add(new Dependency("PyPI", n.group(1), v.group(1), false, "prod", rel));
                    }
                }
            }
            return out;
        }

        static List<Dependency> requirements(Path root, Path req, List<String> warnings) {
            List<Dependency> out = new ArrayList<>();
            String rel = root.relativize(req).toString().replace('\\', '/');
            int unpinned = 0;
            for (String line : readString(req).split("\\R")) {
                String l = line.replaceAll("#.*", "").trim();
                if (l.isEmpty() || l.startsWith("-")) {
                    continue;
                }
                Matcher m = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[[^]]*])?\\s*==\\s*([\\w.!+-]+)").matcher(l);
                if (m.find()) {
                    out.add(new Dependency("PyPI", m.group(1), m.group(2), true, "prod", rel));
                } else {
                    unpinned++;
                }
            }
            if (unpinned > 0) {
                warnings.add(rel + ": " + unpinned + " requirement(s) are not pinned with '==' and were skipped "
                        + "(pin them or provide a lock file for complete coverage).");
            }
            return out;
        }
    }

    // =================================================================== usage / impact

    /** Indexes imports in source files so findings can say where a package is used. */
    static final class SourceIndex {
        private final Map<String, Set<String>> filesByImport = new HashMap<>();

        static SourceIndex build(Path root) {
            SourceIndex idx = new SourceIndex();
            Pattern javaImport = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)");
            Pattern jsImport = Pattern.compile("(?:from\\s+|require\\(\\s*|import\\s*\\(\\s*|import\\s+)['\"]([^'\"./][^'\"]*)['\"]");
            Pattern pyImport = Pattern.compile("(?m)^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))");
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return !dir.equals(root) && Inventory.SKIP.contains(dir.getFileName().toString())
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                        String n = f.getFileName().toString();
                        Pattern p = n.endsWith(".java") || n.endsWith(".kt") ? javaImport
                                : n.matches(".*\\.(js|jsx|ts|tsx|mjs|cjs)") ? jsImport : n.endsWith(".py") ? pyImport : null;
                        if (p == null || attrs.size() > 2_000_000) {
                            return FileVisitResult.CONTINUE;
                        }
                        String rel = root.relativize(f).toString().replace('\\', '/');
                        Matcher m = p.matcher(readString(f));
                        while (m.find()) {
                            String imp = m.group(1) != null ? m.group(1) : m.group(2);
                            if (imp != null) {
                                idx.filesByImport.computeIfAbsent(imp, k -> new LinkedHashSet<>()).add(rel);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return idx;
        }

        List<String> filesImporting(Dependency d) {
            List<String> prefixes = new ArrayList<>();
            switch (d.ecosystem()) {
                case "Maven" -> {
                    // groupId is usually the package root; try it and its parents (min 2 segments)
                    String g = d.name().substring(0, d.name().indexOf(':'));
                    String[] parts = g.split("\\.");
                    for (int n = parts.length; n >= 2; n--) {
                        prefixes.add(String.join(".", java.util.Arrays.copyOf(parts, n)) + ".");
                    }
                }
                case "npm" -> {
                    prefixes.add(d.name());
                    prefixes.add(d.name() + "/");
                }
                default -> {
                    String mod = d.name().toLowerCase(Locale.ROOT).replace('-', '_');
                    prefixes.add(PY_IMPORT_NAMES.getOrDefault(mod, mod));
                }
            }
            Set<String> files = new LinkedHashSet<>();
            for (Map.Entry<String, Set<String>> e : filesByImport.entrySet()) {
                String imp = e.getKey();
                for (String p : prefixes) {
                    boolean match = d.ecosystem().equals("Maven") ? (imp + ".").startsWith(p)
                            : d.ecosystem().equals("npm") ? imp.equals(p) || imp.startsWith(p)
                            : imp.equals(p) || imp.startsWith(p + ".");
                    if (match) {
                        files.addAll(e.getValue());
                        break;
                    }
                }
                if (d.ecosystem().equals("Maven") && !files.isEmpty()) {
                    // first (most specific) matching prefix wins
                }
            }
            return new ArrayList<>(files);
        }

        private static final Map<String, String> PY_IMPORT_NAMES = Map.of("pyyaml", "yaml", "pillow", "PIL",
                "beautifulsoup4", "bs4", "scikit_learn", "sklearn", "python_dateutil", "dateutil", "pyjwt", "jwt",
                "opencv_python", "cv2", "protobuf", "google.protobuf");
    }

    // =================================================================== HTTP sources

    static final class Http {
        private final HttpClient client;
        private final Duration timeout;

        Http(Duration timeout) {
            this.timeout = timeout;
            this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        String get(String url, Map<String, String> headers) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
            headers.forEach(b::header);
            return send(b.build());
        }

        String post(String url, String json) {
            return send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build());
        }

        private String send(HttpRequest r) {
            try {
                HttpResponse<String> resp = client.send(r, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 404) {
                    return null;
                }
                if (resp.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + resp.statusCode() + " from " + r.uri());
                }
                return resp.body();
            } catch (IOException e) {
                throw new UncheckedIOException("Request to " + r.uri() + " failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** OSV.dev API: one query per package returns every advisory for it (paged). */
    static final class OsvHttp implements AdvisorySource {
        private final Http http;
        private final String base;
        private final Map<String, List<Map<String, Object>>> cache = new ConcurrentHashMap<>();

        OsvHttp(Http http, Options o) {
            this.http = http;
            this.base = o.osvUrl();
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> advisoriesFor(String ecosystem, String name) {
            return cache.computeIfAbsent(ecosystem + ":" + name, k -> {
                List<Map<String, Object>> out = new ArrayList<>();
                String token = null;
                int pages = 0;
                do {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("package", Map.of("name", name, "ecosystem", ecosystem));
                    if (token != null) {
                        q.put("page_token", token);
                    }
                    String body = http.post(base + "/v1/query", Json.write(q, false));
                    Map<String, Object> resp = body == null ? Map.of() : (Map<String, Object>) Json.parse(body);
                    for (Object v : (List<Object>) resp.getOrDefault("vulns", List.of())) {
                        out.add((Map<String, Object>) v);
                    }
                    token = (String) resp.get("next_page_token");
                } while (token != null && ++pages < 20);
                return out;
            });
        }
    }

    static final class RegistryHttp implements VersionSource {
        private final Http http;
        private final Options o;

        RegistryHttp(Http http, Options o) {
            this.http = http;
            this.o = o;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<String> versions(String ecosystem, String name) {
            switch (ecosystem) {
                case "Maven" -> {
                    String[] ga = name.split(":");
                    String xml = http.get(o.mavenRepo() + "/" + ga[0].replace('.', '/') + "/" + ga[1]
                            + "/maven-metadata.xml", Map.of());
                    if (xml == null) {
                        return List.of();
                    }
                    try {
                        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                        NodeList nl = doc.getElementsByTagName("version");
                        List<String> out = new ArrayList<>();
                        for (int i = 0; i < nl.getLength(); i++) {
                            out.add(nl.item(i).getTextContent().trim());
                        }
                        return out;
                    } catch (Exception e) {
                        throw new IllegalStateException("Bad maven-metadata.xml for " + name, e);
                    }
                }
                case "npm" -> {
                    String body = http.get(o.npmRegistry() + "/" + name.replace("/", "%2F"),
                            Map.of("Accept", "application/vnd.npm.install-v1+json"));
                    if (body == null) {
                        return List.of();
                    }
                    Map<String, Object> json = (Map<String, Object>) Json.parse(body);
                    Map<String, Object> versions = (Map<String, Object>) json.getOrDefault("versions", Map.of());
                    return versions.entrySet().stream()
                            .filter(e -> !(e.getValue() instanceof Map<?, ?> m) || m.get("deprecated") == null)
                            .map(Map.Entry::getKey).toList();
                }
                case "PyPI" -> {
                    String body = http.get(o.pypiUrl() + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/json", Map.of());
                    if (body == null) {
                        return List.of();
                    }
                    Map<String, Object> json = (Map<String, Object>) Json.parse(body);
                    Map<String, Object> releases = (Map<String, Object>) json.getOrDefault("releases", Map.of());
                    return releases.entrySet().stream().filter(e -> {
                        List<Object> files = (List<Object>) e.getValue();
                        return !files.isEmpty() && files.stream().noneMatch(f -> Boolean.TRUE.equals(((Map<String, Object>) f).get("yanked")));
                    }).map(Map.Entry::getKey).toList();
                }
                default -> {
                    return List.of();
                }
            }
        }
    }

    /** Optional NVD (National Vulnerability Database) CVSS lookup. Set NVD_API_KEY for higher rate limits. */
    public static final class Nvd {
        private final Http http;
        private final Map<String, double[]> cache = new ConcurrentHashMap<>();

        Nvd(Http http) {
            this.http = http;
        }

        @SuppressWarnings("unchecked")
        double[] cvss(String cve) {
            return cache.computeIfAbsent(cve, k -> {
                try {
                    String key = System.getenv("NVD_API_KEY");
                    String body = http.get("https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=" + k,
                            key == null ? Map.of() : Map.of("apiKey", key));
                    Map<String, Object> json = (Map<String, Object>) Json.parse(body);
                    List<Object> vulns = (List<Object>) json.getOrDefault("vulnerabilities", List.of());
                    if (vulns.isEmpty()) {
                        return null;
                    }
                    Map<String, Object> metrics = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) vulns.get(0))
                            .get("cve")).getOrDefault("metrics", Map.of());
                    for (String m : List.of("cvssMetricV31", "cvssMetricV30")) {
                        List<Object> l = (List<Object>) metrics.get(m);
                        if (l != null && !l.isEmpty()) {
                            Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) l.get(0)).get("cvssData");
                            return new double[] {((Number) data.get("baseScore")).doubleValue()};
                        }
                    }
                    return null;
                } catch (RuntimeException e) {
                    return null;
                }
            });
        }
    }

    // =================================================================== helpers (kept local so this file runs standalone)

    static String readString(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                return Files.readString(p, StandardCharsets.ISO_8859_1);
            } catch (IOException e2) {
                throw new UncheckedIOException(e2);
            }
        }
    }


    /** Minimal JSON reader/writer for maps, lists, strings, numbers, booleans and null. */
    public static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        public static Object parse(String text) {
            Json p = new Json(text);
            p.ws();
            Object v = p.value();
            p.ws();
            return v;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    i += 4;
                    return Boolean.TRUE;
                case 'f':
                    i += 5;
                    return Boolean.FALSE;
                case 'n':
                    i += 4;
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // :
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(start, i);
            if (n.contains(".") || n.contains("e") || n.contains("E")) {
                return Double.parseDouble(n);
            }
            long l = Long.parseLong(n);
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        public static String write(Object v, boolean pretty) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, pretty, 0);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(StringBuilder sb, Object v, boolean pretty, int indent) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof String str) {
                quote(sb, str);
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<Object, Object>) m).entrySet()) {
                    sb.append(first ? "" : ",");
                    first = false;
                    nl(sb, pretty, indent + 1);
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(pretty ? ": " : ":");
                    write(sb, e.getValue(), pretty, indent + 1);
                }
                if (!first) {
                    nl(sb, pretty, indent);
                }
                sb.append('}');
            } else if (v instanceof Iterable<?> it) {
                sb.append('[');
                boolean first = true;
                for (Object o : it) {
                    sb.append(first ? "" : ",");
                    first = false;
                    nl(sb, pretty, indent + 1);
                    write(sb, o, pretty, indent + 1);
                }
                if (!first) {
                    nl(sb, pretty, indent);
                }
                sb.append(']');
            } else if (v instanceof Object[] arr) {
                write(sb, List.of(arr), pretty, indent);
            } else {
                quote(sb, v.toString());
            }
        }

        private static void nl(StringBuilder sb, boolean pretty, int indent) {
            if (pretty) {
                sb.append('\n').append("  ".repeat(indent));
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }
    }
}
