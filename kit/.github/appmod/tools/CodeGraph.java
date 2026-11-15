import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Code knowledge graph for JVM projects – pure Java, no dependencies, runnable as a single source file:
 *
 * <pre>
 *   java CodeGraph.java build  &lt;projectDir&gt; [outDir]          # default outDir: &lt;projectDir&gt;/.appmod/knowledge-graph
 *   java CodeGraph.java query  &lt;graph.json&gt; impact &lt;Class|fqcn&gt;  # who is affected if this class changes
 *   java CodeGraph.java query  &lt;graph.json&gt; uses-package &lt;prefix&gt; # which classes import e.g. com.fasterxml.jackson
 *   java CodeGraph.java query  &lt;graph.json&gt; endpoints | entities | layer &lt;name&gt; | deps &lt;module&gt; | node &lt;id&gt;
 * </pre>
 *
 * Output {@code knowledge-graph.json} follows the node/edge schema of GitHub Copilot app modernization's
 * {@code building-java-knowledge-graph} skill (node types system/module/class/interface/enum/annotation; edge types
 * contains/depends_on/extends/implements/aggregates) so its agents can consume it, and adds harness extras:
 * {@code record} nodes, {@code library}, {@code endpoint} and {@code table} nodes and {@code imports}, {@code references}, {@code injects},
 * {@code exposes}, {@code maps_to} edges. It also writes {@code module-dependencies.dot}, {@code project.mmd}
 * (Mermaid) and {@code summary.md}.
 *
 * <p>Parsing is declaration-level and regex based (no full AST): it is designed for navigation, impact analysis
 * and prompt context, not for compilation-grade precision. Kotlin files get type declarations and imports only.
 */
public final class CodeGraph {

    // ------------------------------------------------------------------ public model

    public static final class Graph {
        public final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        public final List<Map<String, Object>> edges = new ArrayList<>();
        private final Set<String> edgeKeys = new HashSet<>();

        Map<String, Object> node(String id, String type, String name) {
            return nodes.computeIfAbsent(id, k -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", id);
                n.put("type", type);
                n.put("name", name);
                return n;
            });
        }

        void edge(String from, String to, String type, Map<String, Object> extra) {
            if (from.equals(to) || !edgeKeys.add(from + "|" + to + "|" + type)) {
                return;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("from", from);
            e.put("to", to);
            e.put("type", type);
            if (extra != null) {
                e.putAll(extra);
            }
            edges.add(e);
        }

        public List<Map<String, Object>> nodesOfType(String type) {
            return nodes.values().stream().filter(n -> type.equals(n.get("type"))).toList();
        }

        /** Resolves a fully-qualified or simple class name to node ids. */
        public List<String> resolve(String nameOrId) {
            if (nodes.containsKey(nameOrId)) {
                return List.of(nameOrId);
            }
            return nodes.values().stream()
                    .filter(n -> nameOrId.equals(n.get("fqcn")) || nameOrId.equals(n.get("name")))
                    .map(n -> (String) n.get("id")).toList();
        }

        /** Reverse transitive closure over code-dependency edges: everything that may break if {@code target} changes. */
        public List<Map<String, Object>> impact(String target) {
            Set<String> codeEdges = Set.of("imports", "injects", "extends", "implements", "references");
            Map<String, List<String>> incoming = new HashMap<>();
            for (Map<String, Object> e : edges) {
                if (codeEdges.contains(e.get("type"))) {
                    incoming.computeIfAbsent((String) e.get("to"), k -> new ArrayList<>()).add((String) e.get("from"));
                }
            }
            Map<String, Integer> depth = new LinkedHashMap<>();
            Deque<String> queue = new ArrayDeque<>(resolve(target));
            queue.forEach(id -> depth.put(id, 0));
            while (!queue.isEmpty()) {
                String id = queue.poll();
                for (String from : incoming.getOrDefault(id, List.of())) {
                    if (!depth.containsKey(from)) {
                        depth.put(from, depth.get(id) + 1);
                        queue.add(from);
                    }
                }
            }
            List<Map<String, Object>> out = new ArrayList<>();
            depth.forEach((id, d) -> {
                if (d > 0) {
                    Map<String, Object> n = new LinkedHashMap<>(nodes.get(id));
                    n.put("distance", d);
                    out.add(n);
                }
            });
            out.sort(Comparator.comparingInt(n -> (Integer) n.get("distance")));
            return out;
        }

        /** Classes importing anything under a package prefix (e.g. {@code org.apache.logging.log4j}). */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> usesPackage(String prefix) {
            return nodes.values().stream()
                    .filter(n -> n.get("imports") instanceof List<?> l
                            && ((List<String>) l).stream().anyMatch(i -> i.startsWith(prefix)))
                    .toList();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaVersion", "appmod-harness/1 (compatible with app-modernization knowledge-graph.json)");
            m.put("nodes", new ArrayList<>(nodes.values()));
            m.put("edges", edges);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Graph fromMap(Map<String, Object> map) {
            Graph g = new Graph();
            for (Object o : (List<Object>) map.getOrDefault("nodes", List.of())) {
                Map<String, Object> n = (Map<String, Object>) o;
                g.nodes.put((String) n.get("id"), n);
            }
            for (Object o : (List<Object>) map.getOrDefault("edges", List.of())) {
                Map<String, Object> e = (Map<String, Object>) o;
                g.edges.add(e);
                g.edgeKeys.add(e.get("from") + "|" + e.get("to") + "|" + e.get("type"));
            }
            return g;
        }

        public static Graph read(Path json) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) MiniJson.parse(readString(json));
            return fromMap(m);
        }
    }

    // ------------------------------------------------------------------ CLI

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "build".equals(args[0])) {
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            Path out = args.length > 2 ? Path.of(args[2]) : root.resolve(".appmod").resolve("knowledge-graph");
            Graph g = build(root);
            write(g, out);
            System.out.println("Knowledge graph: " + g.nodes.size() + " nodes, " + g.edges.size() + " edges -> "
                    + out.toAbsolutePath());
            return;
        }
        if (args.length >= 3 && "query".equals(args[0])) {
            Graph g = Graph.read(Path.of(args[1]));
            String arg = args.length > 3 ? args[3] : "";
            Object result = switch (args[2]) {
                case "impact" -> g.impact(arg);
                case "uses-package" -> g.usesPackage(arg);
                case "endpoints" -> g.nodesOfType("endpoint");
                case "entities" -> g.nodes.values().stream().filter(n -> "model".equals(n.get("layer"))).toList();
                case "layer" -> g.nodes.values().stream().filter(n -> arg.equals(n.get("layer"))).toList();
                case "deps" -> g.edges.stream().filter(e -> "depends_on".equals(e.get("type"))
                        && String.valueOf(e.get("from")).contains(arg)).toList();
                case "node" -> g.nodes.get(arg);
                default -> throw new IllegalArgumentException("unknown query " + args[2]);
            };
            System.out.println(MiniJson.write(result, true));
            return;
        }
        System.err.println("""
                usage:
                  java CodeGraph.java build <projectDir> [outDir]
                  java CodeGraph.java query <graph.json> impact <Class|fqcn>
                  java CodeGraph.java query <graph.json> uses-package <package.prefix>
                  java CodeGraph.java query <graph.json> endpoints | entities | layer <name> | deps <module> | node <id>
                """);
        System.exit(2);
    }

    // ------------------------------------------------------------------ build

    private static final Set<String> SKIP = Set.of(".git", "node_modules", "target", "build", ".gradle", ".idea",
            ".appmod", "out", "bin", ".mvn");

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;?");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;?");
    private static final Pattern TYPE_DECL = Pattern.compile(
            "((?:@[\\w.]+(?:\\s*\\((?:[^()]|\\([^()]*\\))*\\))?\\s*)*)"
                    + "(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp|open|data|inner)\\s+)*"
                    + "(class|interface|enum|record|@interface)\\s+(\\w+)"
                    + "(?:\\s*<[^{]*?>)?(?:\\s*\\([^)]*\\))?"
                    + "((?:\\s+(?:extends|implements|permits)\\s+[^{]+?)*)\\s*\\{");
    private static final Pattern ANNOTATION = Pattern.compile("@([\\w.]+)(?:\\s*\\(((?:[^()]|\\([^()]*\\))*)\\))?");
    private static final Pattern MAPPING = Pattern.compile(
            "@(?:[\\w.]+\\.)?(Get|Post|Put|Delete|Patch|Request)Mapping\\s*(?:\\(((?:[^()]|\\([^()]*\\))*)\\))?[\\s\\S]{0,400}?"
                    + "(?:public|protected|private)?\\s*[\\w<>\\[\\],.? ]+\\s+(\\w+)\\s*\\(");
    private static final Pattern FIRST_STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.(\\w+)");
    private static final Pattern TABLE_NAME = Pattern.compile("@(?:[\\w.]+\\.)?Table\\s*\\([^)]*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern AUTOWIRED_FIELD = Pattern.compile(
            "@(?:[\\w.]+\\.)?(?:Autowired|Inject|Resource)\\s+(?:private|protected|public)?\\s*(?:final\\s+)?([\\w.]+)(?:<[^>]*>)?\\s+\\w+\\s*;");
    private static final Pattern FINAL_FIELD = Pattern.compile(
            "(?m)^\\s*private\\s+final\\s+([A-Z][\\w.]*)(?:<[^>]*>)?\\s+\\w+\\s*;");
    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/");

    public static Graph build(Path root) {
        Graph g = new Graph();
        String systemId = "system:" + root.getFileName();
        g.node(systemId, "system", String.valueOf(root.getFileName())).put("path", root.toString());

        List<Path> buildFiles = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> configs = new ArrayList<>();
        walk(root, buildFiles, sources, configs);

        // ---- modules (Maven / Gradle)
        Map<Path, String> moduleByDir = new TreeMap<>(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed()
                .thenComparing(Path::toString));
        Map<String, String> moduleIdByCoordinate = new HashMap<>();
        List<String[]> pendingModuleDeps = new ArrayList<>();
        for (Path bf : buildFiles) {
            Path dir = bf.getParent();
            String fileName = bf.getFileName().toString();
            Map<String, Object> mod;
            String moduleId;
            if (fileName.equals("pom.xml")) {
                Pom pom = Pom.parse(bf);
                moduleId = "module:" + pom.artifactId + ":" + (pom.version == null ? "?" : pom.version);
                mod = g.node(moduleId, "module", pom.artifactId);
                mod.put("groupId", pom.groupId);
                mod.put("version", pom.version);
                mod.put("packaging", pom.packaging);
                mod.put("buildSystem", "maven");
                if (pom.javaVersion != null) {
                    mod.put("javaVersion", pom.javaVersion);
                }
                if (pom.parent != null) {
                    mod.put("parent", pom.parent);
                }
                moduleIdByCoordinate.put(pom.groupId + ":" + pom.artifactId, moduleId);
                for (String[] d : pom.dependencies) {
                    pendingModuleDeps.add(new String[] {moduleId, d[0], d[1], d[2], d[3]});
                }
            } else {
                String name = dir.equals(root) ? String.valueOf(root.getFileName()) : dir.getFileName().toString();
                moduleId = "module:" + name + ":gradle";
                mod = g.node(moduleId, "module", name);
                mod.put("buildSystem", "gradle");
                moduleIdByCoordinate.put(":" + name, moduleId);
                for (String[] d : GradleDeps.parse(readString(bf))) {
                    pendingModuleDeps.add(new String[] {moduleId, d[0], d[1], d[2], d[3]});
                }
            }
            mod.put("path", root.relativize(dir).toString().replace('\\', '/'));
            moduleByDir.put(dir, moduleId);
            g.edge(systemId, moduleId, "contains", null);
        }
        // parent/child aggregation by directory nesting
        for (Map.Entry<Path, String> child : moduleByDir.entrySet()) {
            Path parentDir = child.getKey().getParent();
            while (parentDir != null && parentDir.startsWith(root)) {
                String parentId = moduleByDir.get(parentDir);
                if (parentId != null) {
                    g.edge(parentId, child.getValue(), "aggregates", null);
                    break;
                }
                parentDir = parentDir.getParent();
            }
        }
        for (String[] d : pendingModuleDeps) {
            // d = moduleId, groupId, artifactId, version, scope
            String internal = moduleIdByCoordinate.get(d[1] + ":" + d[2]);
            if (internal == null && d[1].isEmpty()) {
                internal = moduleIdByCoordinate.get(":" + d[2]);
            }
            Map<String, Object> scope = Map.of("scope", d[4] == null || d[4].isBlank() ? "compile" : d[4]);
            if (internal != null) {
                g.edge(d[0], internal, "depends_on", scope);
            } else {
                String libId = "library:" + d[1] + ":" + d[2];
                Map<String, Object> lib = g.node(libId, "library", d[2]);
                lib.put("groupId", d[1]);
                lib.put("artifactId", d[2]);
                if (d[3] != null && !d[3].isBlank()) {
                    lib.put("version", d[3]);
                }
                g.edge(d[0], libId, "depends_on", scope);
            }
        }

        // ---- source declarations (first pass) and bodies (second pass)
        List<Decl> decls = new ArrayList<>();
        for (Path src : sources) {
            decls.addAll(parseSource(root, src));
        }
        Map<String, String> idByFqcn = new HashMap<>();
        Map<String, List<String>> fqcnsBySimple = new HashMap<>();
        Map<String, List<Decl>> declsByPackage = decls.stream().collect(Collectors.groupingBy(Decl::pkg));
        for (Decl d : decls) {
            String id = typeNodeType(d.kind) + ":" + d.fqcn;
            idByFqcn.put(d.fqcn, id);
            fqcnsBySimple.computeIfAbsent(d.name, k -> new ArrayList<>()).add(d.fqcn);
        }
        for (Decl d : decls) {
            String id = idByFqcn.get(d.fqcn);
            Map<String, Object> n = g.node(id, typeNodeType(d.kind), d.name);
            n.put("fqcn", d.fqcn);
            n.put("package", d.pkg);
            n.put("language", d.language);
            n.put("file", d.file);
            n.put("annotations", d.annotations);
            n.put("imports", d.imports);
            String moduleId = moduleFor(moduleByDir, root.resolve(d.file));
            if (moduleId != null) {
                n.put("moduleName", g.nodes.get(moduleId).get("name"));
                g.edge(moduleId, id, "contains", null);
            } else {
                g.edge(systemId, id, "contains", null);
            }
            String layer = layer(d);
            n.put("layer", layer);
            if (d.superclass != null) {
                n.put("superclass", d.superclass);
                String target = resolveType(d.superclass, d, idByFqcn, fqcnsBySimple);
                if (target != null) {
                    g.edge(id, target, "extends", null);
                }
            }
            if (!d.interfaces.isEmpty()) {
                n.put("interfaces", d.interfaces);
                for (String itf : d.interfaces) {
                    String target = resolveType(itf, d, idByFqcn, fqcnsBySimple);
                    if (target != null) {
                        g.edge(id, target, d.kind.equals("interface") ? "extends" : "implements", null);
                    }
                }
            }
            for (String imp : d.imports) {
                String target = idByFqcn.get(imp);
                if (target != null) {
                    g.edge(id, target, "imports", null);
                }
            }
            // same-package usages need no import statement
            for (Decl other : declsByPackage.getOrDefault(d.pkg, List.of())) {
                if (other != d && Pattern.compile("\\b" + other.name + "\\b").matcher(d.body).find()) {
                    g.edge(id, idByFqcn.get(other.fqcn), "references", null);
                }
            }
            for (String injected : d.injectedTypes) {
                String target = resolveType(injected, d, idByFqcn, fqcnsBySimple);
                if (target != null) {
                    g.edge(id, target, "injects", null);
                }
            }
            if (d.table != null) {
                String tableId = "table:" + d.table.toLowerCase(Locale.ROOT);
                g.node(tableId, "table", d.table);
                g.edge(id, tableId, "maps_to", null);
            } else if (d.annotations.contains("@Entity")) {
                String tableId = "table:" + d.name.toLowerCase(Locale.ROOT);
                g.node(tableId, "table", d.name).put("inferred", true);
                g.edge(id, tableId, "maps_to", null);
            }
            for (String[] ep : d.endpoints) {
                String epId = "endpoint:" + ep[0] + " " + ep[1];
                Map<String, Object> e = g.node(epId, "endpoint", ep[0] + " " + ep[1]);
                e.put("httpMethod", ep[0]);
                e.put("path", ep[1]);
                e.put("handler", d.fqcn + "#" + ep[2]);
                g.edge(id, epId, "exposes", null);
            }
        }
        for (Path cfg : configs) {
            String rel = root.relativize(cfg).toString().replace('\\', '/');
            g.node("config:" + rel, "config", cfg.getFileName().toString()).put("file", rel);
            String moduleId = moduleFor(moduleByDir, cfg);
            g.edge(moduleId != null ? moduleId : systemId, "config:" + rel, "contains", null);
        }
        return g;
    }

    public static void write(Graph g, Path outDir) {
        try {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("knowledge-graph.json"), MiniJson.write(g.toMap(), true),
                    StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("module-dependencies.dot"), moduleDot(g), StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("project.mmd"), mermaid(g, 150), StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("summary.md"), summary(g), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ reports

    public static String summary(Graph g) {
        StringBuilder sb = new StringBuilder("# Code knowledge graph\n\n");
        Map<String, Long> byType = g.nodes.values().stream()
                .collect(Collectors.groupingBy(n -> (String) n.get("type"), TreeMap::new, Collectors.counting()));
        sb.append("| Node type | Count |\n|---|---|\n");
        byType.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        Map<String, Long> byLayer = g.nodes.values().stream().filter(n -> n.get("layer") != null)
                .collect(Collectors.groupingBy(n -> (String) n.get("layer"), TreeMap::new, Collectors.counting()));
        sb.append("\n## Layers\n\n| Layer | Types |\n|---|---|\n");
        byLayer.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));

        List<Map<String, Object>> modules = g.nodesOfType("module");
        if (!modules.isEmpty()) {
            sb.append("\n## Modules\n\n| Module | Build | Java |\n|---|---|---|\n");
            for (Map<String, Object> m : modules) {
                sb.append("| ").append(m.get("name")).append(" | ").append(m.get("buildSystem")).append(" | ")
                        .append(m.getOrDefault("javaVersion", "")).append(" |\n");
            }
        }
        List<Map<String, Object>> eps = g.nodesOfType("endpoint");
        if (!eps.isEmpty()) {
            sb.append("\n## HTTP endpoints (").append(eps.size()).append(")\n\n| Method | Path | Handler |\n|---|---|---|\n");
            eps.stream().limit(200).forEach(e -> sb.append("| ").append(e.get("httpMethod")).append(" | `")
                    .append(e.get("path")).append("` | ").append(e.get("handler")).append(" |\n"));
        }
        List<Map<String, Object>> tables = g.nodesOfType("table");
        if (!tables.isEmpty()) {
            sb.append("\n## Persistence (JPA entities → tables)\n\n");
            for (Map<String, Object> e : g.edges) {
                if ("maps_to".equals(e.get("type"))) {
                    sb.append("- `").append(g.nodes.get(e.get("from")).get("fqcn")).append("` → `")
                            .append(g.nodes.get(e.get("to")).get("name")).append("`\n");
                }
            }
        }
        // most depended-upon types (fan-in) – the riskiest to change
        Map<String, Long> fanIn = g.edges.stream()
                .filter(e -> Set.of("imports", "injects", "extends", "implements", "references").contains(e.get("type")))
                .collect(Collectors.groupingBy(e -> (String) e.get("to"), Collectors.counting()));
        sb.append("\n## Highest fan-in types (change with care)\n\n");
        fanIn.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(15)
                .forEach(e -> sb.append("- `").append(g.nodes.get(e.getKey()).get("fqcn")).append("` – ")
                        .append(e.getValue()).append(" dependents\n"));
        List<Map<String, Object>> libs = g.nodesOfType("library");
        if (!libs.isEmpty()) {
            sb.append("\n## External libraries (declared)\n\n");
            libs.stream().limit(200).forEach(l -> sb.append("- `").append(l.get("groupId")).append(':')
                    .append(l.get("artifactId")).append(l.get("version") == null ? "" : ":" + l.get("version"))
                    .append("`\n"));
        }
        return sb.toString();
    }

    static String moduleDot(Graph g) {
        StringBuilder sb = new StringBuilder("digraph modules {\n  rankdir=LR;\n  node [shape=box];\n");
        for (Map<String, Object> e : g.edges) {
            String type = (String) e.get("type");
            if (("depends_on".equals(type) && String.valueOf(e.get("to")).startsWith("module:"))
                    || "aggregates".equals(type)) {
                sb.append("  \"").append(g.nodes.get(e.get("from")).get("name")).append("\" -> \"")
                        .append(g.nodes.get(e.get("to")).get("name")).append('"')
                        .append("aggregates".equals(type) ? " [style=dashed,color=gray]" : "").append(";\n");
            }
        }
        return sb.append("}\n").toString();
    }

    static String mermaid(Graph g, int maxNodes) {
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        Set<String> included = new LinkedHashSet<>();
        for (Map<String, Object> n : g.nodes.values()) {
            Object layer = n.get("layer");
            if (layer != null && !"other".equals(layer) && !"util".equals(layer) && included.size() < maxNodes) {
                included.add((String) n.get("id"));
            }
        }
        Map<String, String> alias = new HashMap<>();
        int i = 0;
        for (String id : included) {
            String a = "n" + i++;
            alias.put(id, a);
            Map<String, Object> n = g.nodes.get(id);
            sb.append("  ").append(a).append("[\"").append(n.get("name")).append("<br/><i>").append(n.get("layer"))
                    .append("</i>\"]\n");
        }
        for (Map<String, Object> e : g.edges) {
            String from = alias.get(e.get("from"));
            String to = alias.get(e.get("to"));
            if (from != null && to != null && Set.of("injects", "extends", "implements").contains(e.get("type"))) {
                sb.append("  ").append(from).append(" -->|").append(e.get("type")).append("| ").append(to).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ parsing helpers

    private record Decl(String kind, String name, String fqcn, String pkg, String file, String language,
            List<String> annotations, List<String> imports, String superclass, List<String> interfaces,
            List<String> injectedTypes, String table, List<String[]> endpoints, String body) {
    }

    private static void walk(Path root, List<Path> buildFiles, List<Path> sources, List<Path> configs) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && SKIP.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String n = file.getFileName().toString();
                    if (n.equals("pom.xml") || n.equals("build.gradle") || n.equals("build.gradle.kts")) {
                        buildFiles.add(file);
                    } else if (n.endsWith(".java") || n.endsWith(".kt")) {
                        sources.add(file);
                    } else if (n.matches("application.*\\.(properties|ya?ml)") || n.equals("bootstrap.yml")) {
                        configs.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        buildFiles.sort(Comparator.comparingInt(Path::getNameCount));
        sources.sort(Comparator.naturalOrder());
    }

    private static List<Decl> parseSource(Path root, Path file) {
        String raw = readString(file);
        String text = COMMENTS.matcher(raw).replaceAll(" ");
        String language = file.toString().endsWith(".kt") ? "kotlin" : "java";
        Matcher pm = PACKAGE.matcher(text);
        String pkg = pm.find() ? pm.group(1) : "";
        List<String> imports = new ArrayList<>();
        Matcher im = IMPORT.matcher(text);
        while (im.find()) {
            imports.add(im.group(1));
        }
        String rel = root.relativize(file).toString().replace('\\', '/');
        List<Decl> out = new ArrayList<>();
        Matcher m = TYPE_DECL.matcher(text);
        int count = 0;
        while (m.find() && count < 50) {
            count++;
            String annotationsBlock = m.group(1) == null ? "" : m.group(1);
            String kind = m.group(2);
            String name = m.group(3);
            String heritage = m.group(4) == null ? "" : m.group(4);
            List<String> annotations = new ArrayList<>();
            Matcher am = ANNOTATION.matcher(annotationsBlock);
            String table = null;
            String classPath = "";
            while (am.find()) {
                String an = am.group(1);
                annotations.add("@" + an.substring(an.lastIndexOf('.') + 1));
                if (an.endsWith("RequestMapping") && am.group(2) != null) {
                    Matcher s = FIRST_STRING.matcher(am.group(2));
                    if (s.find()) {
                        classPath = s.group(1);
                    }
                }
            }
            Matcher tm = TABLE_NAME.matcher(annotationsBlock);
            if (tm.find()) {
                table = tm.group(1);
            }
            String superclass = null;
            List<String> interfaces = new ArrayList<>();
            Matcher hm = Pattern.compile("(extends|implements)\\s+([^{]+?)(?=\\s+(?:extends|implements|permits)\\b|$)")
                    .matcher(heritage.trim());
            while (hm.find()) {
                List<String> types = splitTypes(hm.group(2));
                if (hm.group(1).equals("extends") && !kind.equals("interface") && !types.isEmpty()) {
                    superclass = types.get(0);
                } else {
                    interfaces.addAll(types);
                }
            }
            // body = up to next top-level type declaration (good enough for injection & mapping scan)
            int bodyStart = m.end();
            int bodyEnd = text.length();
            Matcher next = TYPE_DECL.matcher(text);
            if (next.find(bodyStart)) {
                bodyEnd = next.start();
            }
            String body = text.substring(bodyStart, bodyEnd);
            List<String> injected = new ArrayList<>();
            Matcher af = AUTOWIRED_FIELD.matcher(body);
            while (af.find()) {
                injected.add(af.group(1));
            }
            Matcher ff = FINAL_FIELD.matcher(body);
            while (ff.find()) {
                injected.add(ff.group(1));
            }
            List<String[]> endpoints = new ArrayList<>();
            if (annotations.contains("@RestController") || annotations.contains("@Controller")) {
                Matcher mm = MAPPING.matcher(body);
                while (mm.find()) {
                    String verb = mm.group(1).toUpperCase(Locale.ROOT);
                    String args = mm.group(2) == null ? "" : mm.group(2);
                    if (verb.equals("REQUEST")) {
                        Matcher rm = REQUEST_METHOD.matcher(args);
                        verb = rm.find() ? rm.group(1) : "ANY";
                    }
                    Matcher s = FIRST_STRING.matcher(args);
                    String path = joinPath(classPath, s.find() ? s.group(1) : "");
                    endpoints.add(new String[] {verb, path, mm.group(3)});
                }
            }
            String fqcn = pkg.isEmpty() ? name : pkg + "." + name;
            out.add(new Decl(kind, name, fqcn, pkg, rel, language, annotations, imports, superclass, interfaces,
                    injected, table, endpoints, body));
        }
        return out;
    }

    private static List<String> splitTypes(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                add(out, cur);
                cur.setLength(0);
                continue;
            }
            if (depth == 0 && c != '>') {
                cur.append(c);
            }
        }
        add(out, cur);
        return out;
    }

    private static void add(List<String> out, StringBuilder cur) {
        String t = cur.toString().replaceAll("\\(.*", "").trim();
        if (!t.isEmpty()) {
            out.add(t);
        }
    }

    private static String joinPath(String a, String b) {
        String p = ("/" + a + "/" + b).replaceAll("/+", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private static String resolveType(String type, Decl from, Map<String, String> idByFqcn,
            Map<String, List<String>> fqcnsBySimple) {
        String t = type.replaceAll("<.*", "").trim();
        if (idByFqcn.containsKey(t)) {
            return idByFqcn.get(t);
        }
        for (String imp : from.imports) {
            if (imp.endsWith("." + t) && idByFqcn.containsKey(imp)) {
                return idByFqcn.get(imp);
            }
        }
        String samePkg = from.pkg.isEmpty() ? t : from.pkg + "." + t;
        if (idByFqcn.containsKey(samePkg)) {
            return idByFqcn.get(samePkg);
        }
        List<String> candidates = fqcnsBySimple.getOrDefault(t, List.of());
        return candidates.size() == 1 ? idByFqcn.get(candidates.get(0)) : null;
    }

    private static String typeNodeType(String kind) {
        return switch (kind) {
            case "@interface" -> "annotation";
            default -> kind;
        };
    }

    private static String layer(Decl d) {
        List<String> a = d.annotations;
        if (a.contains("@RestController") || a.contains("@Controller") || a.contains("@ControllerAdvice")
                || a.contains("@RestControllerAdvice")) {
            return "controller";
        }
        if (a.contains("@Repository") || d.interfaces.stream().anyMatch(i -> i.matches(".*(Jpa|Crud|PagingAndSorting|Mongo|Reactive\\w*)Repository.*"))) {
            return "repository";
        }
        if (a.contains("@Service")) {
            return "service";
        }
        if (a.contains("@Entity") || a.contains("@Embeddable") || a.contains("@Document") || a.contains("@MappedSuperclass")) {
            return "model";
        }
        if (a.contains("@Configuration") || a.contains("@SpringBootApplication") || a.contains("@ConfigurationProperties")) {
            return "config";
        }
        String p = d.pkg.toLowerCase(Locale.ROOT);
        if (p.matches(".*\\.(controller|controllers|web|rest|api|resource|resources)(\\..*)?")) {
            return "controller";
        }
        if (p.matches(".*\\.(service|services)(\\..*)?")) {
            return "service";
        }
        if (p.matches(".*\\.(repository|repositories|dao|persistence)(\\..*)?")) {
            return "repository";
        }
        if (p.matches(".*\\.(model|models|domain|entity|entities|dto)(\\..*)?")) {
            return "model";
        }
        if (p.matches(".*\\.(config|configuration)(\\..*)?")) {
            return "config";
        }
        if (p.matches(".*\\.(util|utils|common|helper|helpers)(\\..*)?")) {
            return "util";
        }
        return "other";
    }

    private static String moduleFor(Map<Path, String> moduleByDir, Path file) {
        // moduleByDir iterates deepest directories first
        for (Map.Entry<Path, String> e : moduleByDir.entrySet()) {
            if (file.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

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

    // ------------------------------------------------------------------ build-file parsing

    /** Minimal Maven POM reader (JDK DOM): coordinates, parent, Java version, dependencies with property resolution. */
    static final class Pom {
        String groupId;
        String artifactId;
        String version;
        String packaging;
        String parent;
        String javaVersion;
        final List<String[]> dependencies = new ArrayList<>();

        static Pom parse(Path file) {
            Pom pom = new Pom();
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setExpandEntityReferences(false);
                Document doc = f.newDocumentBuilder().parse(file.toFile());
                Element project = doc.getDocumentElement();
                Map<String, String> props = new HashMap<>();
                Element propsEl = child(project, "properties");
                if (propsEl != null) {
                    NodeList pl = propsEl.getChildNodes();
                    for (int i = 0; i < pl.getLength(); i++) {
                        if (pl.item(i) instanceof Element pe) {
                            props.put(pe.getTagName(), pe.getTextContent().trim());
                        }
                    }
                }
                Element parent = child(project, "parent");
                String parentGroup = parent == null ? null : text(parent, "groupId");
                String parentVersion = parent == null ? null : text(parent, "version");
                if (parent != null) {
                    pom.parent = parentGroup + ":" + text(parent, "artifactId") + ":" + parentVersion;
                }
                pom.groupId = orElse(text(project, "groupId"), parentGroup);
                pom.artifactId = text(project, "artifactId");
                pom.version = orElse(text(project, "version"), parentVersion);
                pom.packaging = orElse(text(project, "packaging"), "jar");
                props.put("project.version", pom.version);
                props.put("project.groupId", pom.groupId);
                pom.javaVersion = firstNonNull(props.get("java.version"), props.get("maven.compiler.release"),
                        props.get("maven.compiler.target"), props.get("maven.compiler.source"));
                Element deps = child(project, "dependencies");
                if (deps != null) {
                    NodeList dl = deps.getElementsByTagName("dependency");
                    for (int i = 0; i < dl.getLength(); i++) {
                        Element d = (Element) dl.item(i);
                        pom.dependencies.add(new String[] {
                            resolve(text(d, "groupId"), props), resolve(text(d, "artifactId"), props),
                            resolve(text(d, "version"), props), orElse(text(d, "scope"), "compile")});
                    }
                }
            } catch (Exception e) {
                pom.artifactId = orElse(pom.artifactId, file.getParent().getFileName().toString());
            }
            if (pom.artifactId == null) {
                pom.artifactId = file.getParent().getFileName().toString();
            }
            return pom;
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
                return "";
            }
            Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(v);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group(0))));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String orElse(String a, String b) {
            return a == null || a.isBlank() ? b : a;
        }

        private static String firstNonNull(String... values) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
            return null;
        }
    }

    /** Gradle dependency declarations (Groovy and Kotlin DSL): group:name:version and project(':x'). */
    static final class GradleDeps {
        private static final Pattern COORD = Pattern.compile(
                "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|annotationProcessor|compile)\\s*\\(?\\s*['\"]([^'\":]+):([^'\":]+)(?::([^'\"]+))?['\"]");
        private static final Pattern PROJECT = Pattern.compile(
                "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation)\\s*\\(?\\s*project\\s*\\(\\s*['\"]:?([^'\"]+)['\"]");

        static List<String[]> parse(String text) {
            List<String[]> out = new ArrayList<>();
            Matcher m = COORD.matcher(text);
            while (m.find()) {
                out.add(new String[] {m.group(2), m.group(3), m.group(4) == null ? "" : m.group(4), scope(m.group(1))});
            }
            Matcher p = PROJECT.matcher(text);
            while (p.find()) {
                String name = p.group(2);
                out.add(new String[] {"", name.substring(name.lastIndexOf(':') + 1), "", scope(p.group(1))});
            }
            return out;
        }

        private static String scope(String configuration) {
            return switch (configuration) {
                case "testImplementation" -> "test";
                case "compileOnly", "annotationProcessor" -> "provided";
                case "runtimeOnly" -> "runtime";
                default -> "compile";
            };
        }
    }

    // ------------------------------------------------------------------ tiny JSON (keeps this file dependency-free)

    /** Minimal JSON reader/writer for maps, lists, strings, numbers, booleans and null. */
    public static final class MiniJson {
        private final String s;
        private int i;

        private MiniJson(String s) {
            this.s = s;
        }

        public static Object parse(String text) {
            MiniJson p = new MiniJson(text);
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
