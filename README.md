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

## Quick start

An embedded H2 database is configured by default, so you only need an API key.

```bash
git clone https://github.com/tinystruct/tinystruct-smart-dba.git
cd tinystruct-smart-dba
./mvnw package -DskipTests          # builds and copies dependencies into lib/
```

Set your Gemini key (the sample config reads it from the `GEMINI_API_KEY` environment variable, so nothing needs editing):

```bash
export GEMINI_API_KEY=your-key       # Linux / macOS
```
```powershell
$env:GEMINI_API_KEY = "your-key"     # Windows PowerShell
```

Run it from the project root:

```bash
bin/dispatcher chat                  # Linux / macOS
```
```bat
bin\dispatcher.cmd chat              # Windows
```

Then try: `create a table of books with title and author, add three rows, and show them`. Writes will ask for your approval.

On Windows, run `chcp 65001` first if the box-drawing characters look garbled.

## Configuration

Everything lives in `src/main/resources/application.properties`. A value written as `$_NAME` is read from the environment variable `NAME`.

```properties
# LLM
agent.api_key=$_GEMINI_API_KEY        # or paste the key directly
agent.model=gemini-3.5-flash-lite
# agent.api_url=...                   # OpenAI-compatible endpoint; defaults to Gemini
# agent.workspace=.                   # root for the agent's file access
# agent.skill_file=path/to/SKILL.md   # extra guidance for the model (empty by default)

# Database (sample default: embedded H2 in your home directory)
driver=org.h2.Driver
database.url=jdbc:h2:~/test
database.user=
database.password=

# Internal MCP server. Change the token from the sample value.
mcp.server.url=http://localhost:8080/
mcp.auth.token=123456
```

**Using your own database:** put its JDBC driver in `pom.xml` (H2 is already there; `sqlite-jdbc`, MySQL and others work the same way), run `./mvnw package -DskipTests` again, and update `driver`, `database.url`, `database.user` and `database.password`. For example, MySQL:

```properties
driver=com.mysql.cj.jdbc.Driver
database.url=jdbc:mysql://localhost:3306/your_db
database.user=root
database.password=secret
```

To use an OpenAI-compatible API, set `agent.api_url` (for example `https://api.openai.com/v1/chat/completions`), `agent.model` and `agent.api_key`.

### Approval rules (TypeSafe settings)

```properties
typesafe.routing.confirm-actions=db/insert,db/update,db/delete
typesafe.api-key=$_TYPESAFE_API_KEY
```

- `typesafe.routing.confirm-actions` is the list of tools that always ask for approval. Add `db/execute` or any other tool to be stricter; removing entries is not recommended.
- `typesafe.api-key` (environment variable `TYPESAFE_API_KEY`) is optional. With it, a TypeSafe JEV model also reviews read-only `db/execute` SQL as a second opinion. Without it, SmartDBA relies on its built-in read-only check, so everything works either way.
- The other `typesafe.*` keys in the sample file (endpoint, model, confidence thresholds, cache, workflow) belong to the TypeSafe integration and can be left at their defaults.

### Logging

`logging.enabled=FALSE` is the default, which hides framework warnings and errors. Set it to `TRUE` when something misbehaves and the terminal shows nothing useful.

### Ports

The embedded HTTP/MCP server starts on tinystruct's default port, 8080, and `mcp.server.url` must point at it. The agent starts it itself, so you do not run a separate server.

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
Prints the answer and exits. Single messages cannot ask for approval, so they are limited to read-only actions. Use the interactive session for anything that modifies data.

### HTTP
With the app running, the same `chat` action is reachable through tinystruct's HTTP server on port 8080, and streamed tokens are pushed over SSE. HTTP requests are read-only for the same reason.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `API key not configured` | Set `GEMINI_API_KEY` (or `agent.api_key`) in the same shell you run the dispatcher from. |
| `API returned status 400 ... API key not valid` | The key is wrong or for a different API; check `agent.model` and `agent.api_url` too. |
| `ClassNotFoundException: org.tinystruct.system.Dispatcher` | Run `./mvnw package -DskipTests` first so `lib/` is populated, and run the dispatcher from the project root. On Windows use `bin\dispatcher.cmd`, not the bash script. |
| Driver or `database.url` errors | Make sure the JDBC driver is in `pom.xml` and `lib/`. |
| Port 8080 already in use | Set `server.port` in `application.properties` and update `mcp.server.url` to match. |

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
