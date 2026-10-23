<p align="center">
  <img src="banner.svg" alt="SmartDBA: 自然语言智能数据库助手" width="100%">
</p>

<p align="center">
  <a href="https://github.com/tinystruct/tinystruct-smart-dba/releases"><img src="https://img.shields.io/github/v/release/tinystruct/tinystruct-smart-dba?color=34d399" alt="最新版本"></a>
  <a href="../LICENSE"><img src="https://img.shields.io/github/license/tinystruct/tinystruct-smart-dba?color=38bdf8" alt="许可证: Apache 2.0"></a>
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+">
  <a href="https://github.com/tinystruct/tinystruct"><img src="https://img.shields.io/badge/tinystruct-1.7.35-blue" alt="tinystruct 1.7.35"></a>
  <img src="https://img.shields.io/badge/MCP-database%20tools-8b5cf6" alt="MCP 数据库工具">
  <a href="https://github.com/tinystruct/tinystruct-smart-dba/stargazers"><img src="https://img.shields.io/github/stars/tinystruct/tinystruct-smart-dba?style=flat" alt="GitHub stars"></a>
</p>

<p align="center">
  <a href="../README.md">English</a> | <a href="README_zh.md">简体中文</a> | <a href="README_ja.md">日本語</a>
</p>

# SmartDBA - AI 数据库智能代理

SmartDBA 是一个基于 [tinystruct](https://github.com/tinystruct/tinystruct) 框架 (1.7.35) 构建的 AI 数据库智能代理（Database Agent）。你可以直接使用日常的自然语言（中文或英文）向它提问（例如：“查询最新创建的 10 个订单”），SmartDBA 会自动推理并生成对应的 SQL，通过内置的一组标准 MCP（Model Context Protocol）数据库工具安全地执行查询，并在终端中以优雅的格式化表格直观呈现结果。

## 核心特性

- **交互式命令行终端 (Interactive CLI)**：支持大模型实时流式输出，并通过 ANSI 颜色与高亮优雅渲染 Markdown 内容（包括标题、列表、代码块以及多列表格）。
- **基于 MCP 协议的数据库工具集**：提供标准的 MCP 工具，涵盖查看数据库表列表、查询表结构、单表数据检索、新增、更新、删除，以及执行复杂的原生 SQL（支持多表 JOIN 关联查询、子查询、CTE 公共表表达式等）。
- **兼容任意 JDBC 数据库**：开箱即用支持 MySQL/MariaDB、PostgreSQL、SQLite、H2、Microsoft SQL Server 等所有具备 JDBC 驱动的数据库。表结构自省直接基于 JDBC 元数据规范，标识符引用转义自动适配不同数据库方言。
- **支持 Gemini 与 OpenAI 兼容大模型**：默认预置 Google Gemini（`gemini-3.5-flash-lite`），同时支持无缝对接任意兼容 OpenAI API 规范的模型端点。
- **HTTP 服务与 SSE 实时推送**：相同的 `chat` 操作可无缝通过内置 HTTP 服务调用，并利用 Server-Sent Events (SSE) 技术实现 Token 级别的逐字实时流式推送。
- **持久化对话上下文记忆**：每次对话历史均保存在 `.agent_history.json` 中；支持通过可选的 Skill 引导文件注入特定业务领域的上下文与专家知识。

## 安全模型

大模型生成的一切输出均被视为不可信输入（Untrusted Input），SmartDBA 构筑了多层严格的安全防御机制：

- **写操作必须获得人工审批**：`db/insert`、`db/update` 与 `db/delete`（配置在 `typesafe.routing.confirm-actions` 列表中）、文件写入以及系统 Shell 命令执行，均会在终端强制弹出 `[y/N]` 提示等待用户显式确认。
- **原生 SQL 预分类与只读静态校验**：执行 `db/execute` 时，系统会预先进行严格分类。只有在语句被静态证明为百分之百只读时（禁止多语句堆叠拼接、禁止注释逃逸、无任何写操作关键字，能够精准识别并拦截形如 `WITH ... DELETE` 的隐藏写操作），才会在免确认模式下执行；任何其他语句一律要求人工审批。若配置了 TypeSafe 密钥，JEV 模型还会对只读 SQL 语义进行二次智能审查。
- **无人审批则拒绝执行写操作**：在脱离交互式终端的环境中（例如通过 HTTP 接口调用的 `chat` 操作），由于没有人工参与审批确认，所有写入操作、Shell 执行以及修改类工具调用均会被严格拒绝。
- **沙箱化文件系统访问限制**：智能代理的 `read` 与 `write` 工具被严格限制在设定的工作区目录（`agent.workspace`，默认为项目当前工作目录）内。系统强制屏蔽对 `application.properties`、`.agent_history.json` 等敏感配置文件的访问，并自动防御通过 `..` 或符号链接（Symlink）逃逸出工作区的路径遍历攻击。
- **严格校验 WHERE 查询条件**：`db/update` 和 `db/delete` 操作强制要求必须指定 WHERE 条件，且条件必须为纯谓词表达式：严禁包含子查询、`UNION` 联合查询、DML/DDL 关键字、注释符、多语句拼接或恒真条件（例如 `1=1`）。需要注意的是，条件最终仍会拼接到 SQL 文本中，因此人工确认审批始终是最终也是最重要的安全防线。

建议使用仅授予必要权限的数据库独立账号连接 SmartDBA，遵循最小权限原则。

## 环境要求

- Java 17 或更高版本
- Maven 3.6+（或者直接使用项目自带的 `mvnw`）
- 目标 JDBC 数据库及其相应的驱动 Jar 包（置于 Classpath 中）
- Google Gemini API Key 或兼容 OpenAI 协议的 API Key

## 快速开始

项目默认配置了嵌入式 H2 数据库，开箱即用，只需配置大模型 API 密钥即可立即体验。

```bash
git clone https://github.com/tinystruct/tinystruct-smart-dba.git
cd tinystruct-smart-dba
./mvnw package -DskipTests          # 编译并将依赖库拷贝至 lib/ 目录
```

设置 Gemini API 密钥（示例配置默认从 `GEMINI_API_KEY` 环境变量读取，无需手动修改配置文件）：

```bash
export GEMINI_API_KEY=your-key       # Linux / macOS
```
```powershell
$env:GEMINI_API_KEY = "your-key"     # Windows PowerShell
```

在项目根目录下启动：

```bash
bin/dispatcher chat                  # Linux / macOS
```
```bat
bin\dispatcher.cmd chat              # Windows
```

启动后，可以尝试输入自然语言指令，例如：`创建一个书籍表，包含书名和作者字段，添加三条数据并展示出来`。所有涉及写入的操作都会主动向你请求确认。

> **字符编码与控制台支持**：
> SmartDBA 对中文等非 ASCII 文本提供了开箱即用的支持。启动脚本已自动将控制台及 JVM 运行环境切换为 UTF-8 编码（Windows 下自动执行 `chcp 65001`，JVM 附加 `-Dfile.encoding=UTF-8`）。如果你通过 IDE 等其他方式启动应用，请确保在 JVM 参数中添加 `-Dfile.encoding=UTF-8`。在 Windows 环境下，推荐使用 Windows Terminal 以获得最佳字体渲染和色彩显示体验。

## 配置说明

所有配置均位于 `src/main/resources/application.properties`。以 `$_NAME` 形式书写的配置值会自动从环境变量 `NAME` 中动态读取。

```properties
# 大模型配置
agent.api_key=$_GEMINI_API_KEY        # 或者直接填入 API Key
agent.model=gemini-3.5-flash-lite
# agent.api_url=...                   # OpenAI 兼容端点；默认为 Gemini 端点
# agent.workspace=.                   # 限制代理访问文件的根目录
# agent.skill_file=path/to/SKILL.md   # 为大模型提供额外业务指导的 Skill 文件（默认留空）

# 数据库配置（示例默认使用用户主目录下的嵌入式 H2 数据库）
driver=org.h2.Driver
database.url=jdbc:h2:~/test
database.user=
database.password=

# 内部 MCP 服务。生产环境请务必修改此默认 Token。
mcp.server.url=http://localhost:8080/
mcp.auth.token=123456
```

**连接你自己的数据库：** 将对应的 JDBC 驱动依赖添加到 `pom.xml` 中（H2 与 PostgreSQL 已默认内置；`sqlite-jdbc`、MySQL 等驱动添加方式完全相同），重新运行 `./mvnw package -DskipTests`，然后修改 `driver`、`database.url`、`database.user` 与 `database.password`。

例如连接 MySQL：

```properties
driver=com.mysql.cj.jdbc.Driver
database.url=jdbc:mysql://localhost:3306/your_db
database.user=root
database.password=secret
```

连接 PostgreSQL（驱动已预先内置，无需修改 `pom.xml`）：

```properties
driver=org.postgresql.Driver
database.url=jdbc:postgresql://localhost:5432/your_db
database.user=postgres
database.password=secret
```

SmartDBA 会在数据库连接的当前模式（Schema）下工作（PostgreSQL 默认为 `public`；可通过在 JDBC URL 后追加 `?currentSchema=myschema` 进行切换）。表名匹配不区分大小写，且代理已经内置了解 PostgreSQL 关于全小写折叠、保留字以及 `SERIAL` 自增键的处理规则。

如需使用 OpenAI 兼容的 API，只需配置 `agent.api_url`（例如 `https://api.openai.com/v1/chat/completions`）、`agent.model` 和 `agent.api_key`。

### 审批规则（TypeSafe 配置）

```properties
typesafe.routing.confirm-actions=db/insert,db/update,db/delete
typesafe.api-key=$_TYPESAFE_API_KEY
```

- `typesafe.routing.confirm-actions`：始终需要人工交互确认的工具列表。若需要更严苛的安全管控，可以追加 `db/execute` 或其他工具；不建议移除已有项。
- `typesafe.api-key`（对应环境变量 `TYPESAFE_API_KEY`）为可选配置。配置后，TypeSafe JEV 模型将作为第二道防线，对只读的 `db/execute` SQL 进行语义意图复核。未配置时，SmartDBA 会直接使用内置的高性能只读校验规则，两种方式均可正常运行。
- 配置文件中其他的 `typesafe.*` 属性（如接口端点、模型版本、置信度阈值、缓存策略、工作流等）属于 TypeSafe 深度集成配置，保持默认值即可。

### 日志配置

默认配置为 `logging.enabled=FALSE`，用于隐藏底层框架的非关键警告与调试日志，保证终端整洁。当遇到异常或终端无有效反馈需要排查问题时，可将其设为 `TRUE`。

### 端口配置

内置的 HTTP/MCP 服务会监听 tinystruct 默认的 8080 端口，且 `mcp.server.url` 必须指向该端口。SmartDBA 启动时会自动拉起该服务，无需单独手动运行外部服务器。

## 使用指南

### 交互式会话
```bash
bin/dispatcher chat
```
输入自然语言需求，例如：“帮我统计本月注册的前 5 名活跃用户”。
其他内置快捷指令：输入 `clear` 可清空当前上下文历史，输入 `exit` 或 `quit` 可退出会话。

### 单次命令行执行
```bash
bin/dispatcher chat --message "列出所有数据表"
```
终端将直接打印回答并立即退出。由于单次消息模式无法进行人工交互确认，因此仅限于执行只读操作。涉及任何数据修改的操作，请在交互式会话中使用。

### HTTP 接口与 SSE 流式推送
应用启动后，相同的 `chat` 操作可直接通过 tinystruct 内置的 HTTP 服务（端口 8080）进行访问，生成的回复内容将通过 SSE 实时推送到客户端。出于安全考虑，通过 HTTP 触发的请求同样仅限制为只读操作。

## 常见问题排查

| 现象 / 报错信息 | 解决方案 |
|---|---|
| `API key not configured` | 请在运行 dispatcher 的同一终端 Shell 环境中正确设置 `GEMINI_API_KEY`（或在配置文件中配置 `agent.api_key`）。 |
| `API returned status 400 ... API key not valid` | API Key 无效或与当前指定的 API 接口不匹配；请同时检查 `agent.model` 和 `agent.api_url` 的配置是否正确。 |
| `ClassNotFoundException: org.tinystruct.system.Dispatcher` | 请先执行 `./mvnw package -DskipTests` 生成并填充 `lib/` 依赖目录，并确保在项目根目录下运行命令。在 Windows 环境下请运行 `bin\dispatcher.cmd`，而不是 Bash 脚本。 |
| JDBC 驱动或 `database.url` 相关报错 | 请确保目标数据库的 JDBC 驱动已在 `pom.xml` 中引入并已被打包至 `lib/` 目录中。 |
| 端口 8080 被占用 (Port already in use) | 在 `application.properties` 中添加 `server.port=新端口`，并同步修改 `mcp.server.url` 中的端口号。 |

## 基于 MCP 暴露的数据库工具

| 工具标识 | 用途说明 |
|---|---|
| `db/list-tables` | 获取数据库中所有的表和视图列表 |
| `db/describe` | 查看指定表的列名、字段数据类型、主键及索引等模式信息 |
| `db/query` | 单表数据查询，支持可选的 WHERE 过滤条件和 LIMIT 数量限制（最大 1000 条） |
| `db/insert` | 向表中插入单行记录（采用参数化预编译方式） |
| `db/update` | 更新指定表的数据（强制必须提供合法的 WHERE 条件） |
| `db/delete` | 删除指定表的数据（强制必须提供合法的 WHERE 条件） |
| `db/execute` | 执行原生 SQL 语句，用于复杂的多表 JOIN 关联、CTE 表达式、DDL 等操作 |

## 项目核心源码结构

- `SmartDBA.java`：智能代理核心，包含交互式对话循环、大模型流式调用与解析、终端 Markdown 渲染器以及安全审批逻辑。
- `SmartDBAServer.java`：内置 MCP 服务端，负责注册并向外暴露数据库工具接口。
- `tools/DatabaseTool.java`：MCP 数据库工具具体实现，包含 SQL 静态只读分类器以及 WHERE 子句安全校验。

## 运行测试

```bash
./mvnw test
```

## 开源许可证

本项目基于 Apache License 2.0 协议开源。详细信息请参阅 [LICENSE](../LICENSE)。
