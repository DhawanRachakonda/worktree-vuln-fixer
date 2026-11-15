# Code knowledge graph

GitHub Copilot app modernization has a `building-java-knowledge-graph` skill (Python + tree-sitter) whose
`knowledge-graph.json` its agents consume. The harness provides an equivalent **in pure Java** (`CodeGraph.java`,
JDK only) so it runs wherever the JVM runs, and extends it with what migrations need.

## Schema

| Node type | Id pattern | Notes |
|---|---|---|
| `system` | `system:<repo>` | root |
| `module` | `module:<artifactId>:<version>` / `module:<name>:gradle` | `buildSystem`, `groupId`, `javaVersion`, `parent` |
| `class` / `interface` / `enum` / `record` / `annotation` | `<type>:<fqcn>` | `layer`, `annotations`, `imports`, `superclass`, `interfaces`, `file`, `moduleName`, `language` |
| `library` | `library:<group>:<artifact>` | declared external dependency, `version` |
| `endpoint` | `endpoint:<METHOD> <path>` | Spring MVC/WebFlux mapping, `handler` = `Class#method` |
| `table` | `table:<name>` | from `@Table(name=…)` or inferred from `@Entity` |
| `config` | `config:<path>` | `application*.yml/properties` |

| Edge type | Meaning |
|---|---|
| `contains` | system→module, module→type/config |
| `aggregates` | parent module → child module |
| `depends_on` | module → module/library (`scope`) |
| `extends`, `implements` | type hierarchy |
| `imports` | type imports a project type |
| `references` | same-package usage (no import needed) |
| `injects` | constructor/field injection (`private final X x;`, `@Autowired`, `@Inject`) |
| `exposes` | controller → endpoint |
| `maps_to` | entity → table |

Layers: `controller`, `service`, `repository`, `model`, `config`, `util`, `other` – from Spring/JPA annotations,
repository super-interfaces, then package naming.

## Outputs

`knowledge-graph.json`, `summary.md` (types per layer, modules + Java version, endpoints, entity→table map, highest
fan-in types, libraries), `module-dependencies.dot`, `project.mmd` (Mermaid).

## Queries

| Query | Use |
|---|---|
| `impact <Class>` | Reverse transitive closure over imports/references/injects/extends/implements with distance – "what breaks / which tests to run" |
| `uses-package <prefix>` | Which classes depend on a library package – reachability of a vulnerable dependency, javax usage before migration |
| `endpoints`, `entities`, `layer <name>`, `deps <module>`, `node <id>` | Navigation |

## Where it is used

- **Kit skill** `code-knowledge-graph` for developers and agents: `java .github/appmod/tools/CodeGraph.java build <projectDir> [outDir]`, then `query`.
- The dependency advisor uses it to judge whether a vulnerable library is reachable from the code.

Measured on spring-petclinic: 107 nodes / 236 edges in ~2 s; 17 endpoints, 5 entity→table mappings, all layers.

## Limitations

Regex-based declaration parsing: no generic type resolution, no method-level call graph, Kotlin limited to
declarations and imports. Good for navigation, impact estimation and prompt context; not a proof that a type is unused
(reflection, XML configuration, string class names).
