---
applyTo: "**/*.sql,**/*.sqlproj"
description: "Editing rules for T-SQL and SQL project files in AppMod harness jobs"
---

- Keep one object per file in SQL projects (`<schema>/<ObjectType>/<Name>.sql`).
- Use explicit column lists in `INSERT` and `SELECT` (no `SELECT *` in persisted objects).
- Terminate statements with `;`.
- Always schema-qualify object references (`dbo.Orders`, not `Orders`).
- Add `-- appmod:` comments only where a reviewer must look; do not annotate every change.
