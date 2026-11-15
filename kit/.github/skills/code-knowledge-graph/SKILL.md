---
name: code-knowledge-graph
description: Build and query a code knowledge graph of a JVM project (modules, classes, Spring layers, REST endpoints, JPA entities/tables, dependencies) for impact analysis before changing code. Pure Java – no Python needed.
---

# Code knowledge graph

GitHub Copilot app modernization ships a `building-java-knowledge-graph` skill that needs Python and tree-sitter.
This skill produces a **compatible** `knowledge-graph.json` (same node/edge types: system, module, class, interface,
enum, annotation; contains, depends_on, extends, implements, aggregates) using only the JDK, and adds what
migrations need most: `endpoint`, `table`, `library` nodes and `imports`, `references`, `injects`, `exposes`,
`maps_to` edges.

## Where the tool is

`CodeGraph.java` – a single-file Java program:

- repository install: `.github/appmod/tools/CodeGraph.java`
- user-level install: `~/.copilot/appmod/tools/CodeGraph.java`
- AppMod harness jobs: already built for you in `.appmod/knowledge-graph/` (and the `code_graph_query` tool exists)

## Build

```
java .github/appmod/tools/CodeGraph.java build . .appmod/knowledge-graph
```

Outputs (in the output folder only – it never deletes other files):

| File | Use |
|---|---|
| `knowledge-graph.json` | full graph, app-modernization compatible |
| `summary.md` | layers, modules, endpoints, entity→table map, highest fan-in types, libraries – **read this first** |
| `module-dependencies.dot` | module graph (Graphviz) |
| `project.mmd` | Mermaid diagram of controllers/services/repositories/models |

## Query (use before every non-trivial change)

```
java .github/appmod/tools/CodeGraph.java query .appmod/knowledge-graph/knowledge-graph.json impact OrderService
java .github/appmod/tools/CodeGraph.java query .appmod/knowledge-graph/knowledge-graph.json uses-package javax.persistence
java .github/appmod/tools/CodeGraph.java query .appmod/knowledge-graph/knowledge-graph.json endpoints
java .github/appmod/tools/CodeGraph.java query .appmod/knowledge-graph/knowledge-graph.json layer repository
```

- `impact <Class>` – every type that imports, references, injects, extends or implements it, transitively, with distance.
  Use it to size a change and to decide which tests to run.
- `uses-package <prefix>` – which classes depend on a library package (e.g. a vulnerable dependency) – reachability.
- If the code changed a lot, rebuild before querying.

Limitations: declaration-level parsing (no full type resolution); good for navigation and impact, not for proving
absence of usages via reflection or configuration.
