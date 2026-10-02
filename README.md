# SmartDBA - AI Database Agent

SmartDBA is an AI database agent built on the [tinystruct](https://github.com/tinystruct/tinystruct) framework (1.7.35). Ask questions in plain English ("show me the ten newest orders"); the agent works out the SQL, runs it through a set of MCP database tools, and shows the results as formatted tables in your terminal.

## Features

- **Interactive CLI**: Streams the model's reply live and renders Markdown (headings, lists, code blocks, tables) with ANSI colors.
- **Database tools over MCP**: List tables, describe schemas, query, insert, update, delete, and run raw SQL (JOINs, subqueries, CTEs).
- **Any JDBC database**: MySQL/MariaDB, SQLite, H2, SQL Server and other JDBC drivers. Schema discovery uses JDBC metadata, and identifier quoting adapts to the dialect.
- **Gemini or OpenAI-compatible LLMs**: Defaults to Gemini (`gemini-3.5-flash-lite`).
- **HTTP + SSE**: The same `chat` action is available over HTTP, with token-by-token updates pushed over Server-Sent Events.
- **Conversation memory**: History is kept between turns in `.agent_history.json`; an optional skill file adds extra guidance for the model.

## Safety model

The model's output is treated as untrusted input.

- **Writes need approval.** `db/insert`, `db/update` and `db/delete` (the `typesafe.routing.confirm-actions` list), file writes and shell commands all ask `[y/N]` in the terminal.
- **Raw SQL is classified first.** `db/execute` runs without a prompt only when the statement is provably read-only (no stacked statements, comments, or write keywords, so `WITH ... DELETE` is caught). Anything else needs approval. If a TypeSafe key is configured, the JEV model gives an extra opinion on read-only SQL.
- **No approver, no write.** Outside the interactive terminal (for example the HTTP `chat` action) there is nobody to approve, so writes, shell commands and modifying tool calls are refused.
- **Confined file access.** The agent's `read` and `write` actions only touch files inside the workspace (`agent.workspace`, default: the working directory). `application.properties` and `.agent_history.json` are blocked, as are paths that escape via `..` or symlinks.
- **Validated WHERE clauses.** `db/update` and `db/delete` require a WHERE clause, which must be a plain predicate: no subqueries, `UNION`, DML/DDL, comments, stacked statements or always-true conditions. The clause is still spliced into the SQL text, so treat approval as the real safeguard.

Use a database account with only the privileges you are comfortable giving the agent.

## Prerequisites

- Java 17 or higher
- Maven 3.6+ (or use the bundled `mvnw`)
- A JDBC database and its driver on the classpath
- A Google Gemini or OpenAI-compatible API key

## Setup

1. **Clone**:
   ```bash
   git clone https://github.com/tinystruct/tinystruct-smart-dba.git
   cd tinystruct-smart-dba
   ```

2. **Configure** `src/main/resources/application.properties`:
   ```properties
   # LLM
   agent.api_key=YOUR_API_KEY
   agent.model=gemini-3.5-flash-lite
   # Optional: agent.api_url=...   (OpenAI-compatible endpoint; defaults to Gemini)
   # Optional: agent.workspace=.   (root for the agent's file access)
   # Optional: agent.skill_file=path/to/SKILL.md

   # Database
   driver=com.mysql.cj.jdbc.Driver
   database.url=jdbc:mysql://localhost:3306/your_db
   database.user=root
   database.password=secret

   # Internal MCP server. Change the token from the sample value.
   mcp.server.url=http://localhost:8080/
   mcp.auth.token=CHANGE_ME
   ```
   Add your JDBC driver as a dependency in `pom.xml` (H2 is the sample default).

3. **Build**:
   ```bash
   ./mvnw package
   ```

## Usage

### Interactive session
```bash
bin/dispatcher chat
```
Type a request such as "Show me the top 5 users created this month". Other commands: `clear` resets the history, `exit` or `quit` leaves.

### Single message
```bash
bin/dispatcher chat --message "List all tables"
```
Single messages cannot ask for approval, so anything that would modify data is refused. Use the interactive session for writes.

## Tools exposed over MCP

| Tool | Purpose |
|---|---|
| `db/list-tables` | List tables and views |
| `db/describe` | Columns, types, keys of a table |
| `db/query` | Select rows with optional WHERE and LIMIT (max 1000) |
| `db/insert` | Insert a row (parameterized) |
| `db/update` | Update rows (WHERE required) |
| `db/delete` | Delete rows (WHERE required) |
| `db/execute` | Raw SQL for JOINs, CTEs, DDL |

## Project layout

- `SmartDBA.java`: chat loop, LLM streaming, Markdown renderer, approval logic.
- `SmartDBAServer.java`: MCP server that registers the database tools.
- `tools/DatabaseTool.java`: the MCP database tools, SQL classifier and WHERE validation.

## Testing

```bash
./mvnw test
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
