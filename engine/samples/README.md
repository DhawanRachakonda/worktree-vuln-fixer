# Sample reports

Small invented reports to try the engine without a scanner. None of them comes from a real project or a real scan: the code they point at is
the made-up `shop` (Java) and `orders` (Maven) projects of the demos, and the advisories named in `bdh.json` are real public ones.

| File | Shape | Publish it on |
|---|---|---|
| `reports/bdh.json` | A Black Duck style dependency report (two CVEs in `orders`) | `bdh.issuefixesAgent` |
| `reports/coverity.json` | A Coverity style source report in a plain JSON shape (two findings in `shop`) | `coverity.issuefixesAgent` |
| `reports/coverity-export.sample.json` | The structure of a Coverity "issues" export (`formatVersion` 10), reduced to two invented issues | `coverity.issuefixesAgent` |

They point at paths such as `C:/work/shop`: copy the report, change `projectPath` and the file paths to your own repository, and
`publish <file> --subject <subject>` from the engine console. The engine reads any file; it is the agent that interprets the report.
