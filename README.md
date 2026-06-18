# SmartDBA - Autonomous Database Agent

SmartDBA is an autonomous database agent built on the [tinystruct](https://github.com/tinystruct/tinystruct) framework. It combines a powerful Large Language Model (LLM) with a Model Context Protocol (MCP) server to provide a friendly CLI interface for database management and exploration.

## Features

- **Interactive CLI**: A polished command-line interface with ANSI colors, banners, and real-time "thinking" indicators.
- **Autonomous Database Operations**: The agent can list tables, describe schemas, query data, and perform DML operations (Insert, Update, Delete) based on natural language instructions.
- **Complex SQL Support**: Supports JOINs, subqueries, and Common Table Expressions (CTEs/`WITH`) via a robust execution tool.
- **Table-Style Display**: Results from database queries are automatically formatted into clean, readable ASCII tables with intelligent column truncation.
- **Syntax Highlighting**: Built-in highlighting for Java code blocks and JSON actions in agent responses.
- **MCP Integration**: Uses the Model Context Protocol to bridge the LLM with local database tools.

## Prerequisites

- **Java 17** or higher.
- **Maven** 3.6 or higher.
- A JDBC-compliant database (MySQL, SQLite, H2, PostgreSQL, MS SQL Server, etc.).
- An OpenAI or Google Gemini API Key.

## Setup

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd smartdba
   ```

2. **Configure Application**:
   Edit `src/main/resources/application.properties` to set your API key and database connection:
   ```properties
   # Agent Configuration
   agent.api_key=YOUR_API_KEY_HERE
   # Optional: agent.api_url=... (Defaults to Gemini)

   # Database Configuration
   driver=com.mysql.cj.jdbc.Driver
   database.url=jdbc:mysql://localhost:3306/your_db
   database.user=root
   database.password=secret
   ```

3. **Build the project**:
   ```bash
   mvn compile
   ```

## Usage

### Start Interactive Session
The most common way to use SmartDBA is through its interactive CLI mode:
```bash
bin/dispatcher agent/chat
```

### Commands in Interactive Mode
- Type your message to the agent (e.g., "Show me the top 5 users created this month").
- `clear`: Clears the chat history.
- `exit` or `quit`: Ends the session.

### Direct Chat
You can also send a single message from the command line:
```bash
bin/dispatcher agent/chat --message "List all tables"
```

## Available Tools (MCP)

The agent has access to the following specialized tools:
- `db/list-tables`: Lists all tables in the connected database.
- `db/describe`: Describes the columns and types of a specific table.
- `db/query`: Selects rows with optional WHERE and LIMIT clauses.
- `db/insert`/`db/update`/`db/delete`: Standard DML operations.
- `db/execute`: Execute arbitrary SQL (JOINs, CTEs, etc.).

## Development

SmartDBA is modular and can be extended:
- `SmartDBA.java`: Main agent logic and CLI interface.
- `DatabaseTool.java`: Implementation of MCP database tools.
- `SmartDBAServer.java`: MCP server registration.

## License

Distributed under the Apache License, Version 2.0. See `bin/README.md` for details.
