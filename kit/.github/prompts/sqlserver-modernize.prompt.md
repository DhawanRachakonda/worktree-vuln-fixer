---
agent: appmod-harness
description: "Modernize SQL Server database code (compatibility level, Azure SQL, PostgreSQL)"
---

Modernize the SQL Server database code in this repository.

- Goal (compat-level | azure-sql | postgresql | assess): ${input:goal:assess}
- Target (compatibility level such as 160, or PostgreSQL version such as 16): ${input:target:160}
