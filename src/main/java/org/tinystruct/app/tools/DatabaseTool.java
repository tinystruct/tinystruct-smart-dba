package org.tinystruct.app.tools;

import org.tinystruct.ApplicationException;
import org.tinystruct.data.component.Builder;
import org.tinystruct.data.component.Builders;
import org.tinystruct.data.DatabaseOperator;
import org.tinystruct.mcp.MCPException;
import org.tinystruct.mcp.MCPTool;
import org.tinystruct.system.annotation.Action;
import org.tinystruct.system.annotation.Argument;
import org.tinystruct.data.repository.Type;

import java.sql.*;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCP Database Tool — exposes database operations as MCP-compatible tools
 * (list tables, describe schema, query, insert, update, delete, execute SQL).
 *
 * <p>Extends {@link MCPTool} so that every {@code @Action}-annotated method is
 * automatically registered as an individual MCP tool by
 * {@code MCPServer.registerTool()}, and is simultaneously registered in the
 * tinystruct {@code ActionRegistry} so that the TypeSafe {@code DispatchPipeline}
 * (JEV model) can route and confirm calls before execution.</p>
 *
 * <p>Supports MySQL/MariaDB, SQLite, H2, Microsoft SQL Server, and any other
 * JDBC-compliant database. All schema introspection uses JDBC
 * {@link DatabaseMetaData} rather than vendor-specific SQL, and identifier
 * quoting adapts to the dialect reported by the driver.</p>
 *
 * <p>Configure your database in {@code application.properties}:
 * <pre>
 *   # MySQL / MariaDB
 *   driver=com.mysql.cj.jdbc.Driver
 *   database.url=jdbc:mysql://localhost:3306/mydb
 *   database.user=root
 *   database.password=secret
 *
 *   # SQLite
 *   driver=org.sqlite.JDBC
 *   database.url=jdbc:sqlite:/path/to/db.sqlite
 *
 *   # H2 (embedded)
 *   driver=org.h2.Driver
 *   database.url=jdbc:h2:~/mydb
 *   database.user=sa
 *   database.password=
 *
 *   # Microsoft SQL Server
 *   driver=com.microsoft.sqlserver.jdbc.SQLServerDriver
 *   database.url=jdbc:sqlserver://localhost:1433;databaseName=mydb
 *   database.user=sa
 *   database.password=secret
 * </pre>
 * </p>
 */
public class DatabaseTool extends MCPTool {

    private static final Logger logger = Logger.getLogger(DatabaseTool.class.getName());

    /** Hard cap on rows returned by db/query. */
    private static final int MAX_ROWS = 1000;

    // ─────────────────────────────────────────────────────────────────────────
    // Dialect
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detects the dialect from a live {@link DatabaseMetaData} instance.
     * The detection is intentionally coarse — we only need to know which
     * quoting character and which schema-scoping approach to use.
     */
    private static Type detectDialect(DatabaseMetaData meta) {
        try {
            String name = meta.getDatabaseProductName().toUpperCase(Locale.ROOT);
            if (name.contains("MYSQL") || name.contains("MARIADB")) return Type.MySQL;
            if (name.contains("SQLITE"))                              return Type.SQLite;
            if (name.contains("H2"))                                  return Type.H2;
            if (name.contains("MICROSOFT") || name.contains("SQL SERVER")) return Type.SQLServer;
        } catch (SQLException ignored) { /* fall through to null */ }
        return null;
    }

    /**
     * Returns the identifier quote string reported by the driver.
     * Falls back to {@code `} for MySQL compatibility when the driver
     * returns a space (meaning "quoting is not supported").
     */
    private static String quoteChar(DatabaseMetaData meta, Type dialect) {
        try {
            String q = meta.getIdentifierQuoteString();
            if (q != null && !q.trim().isEmpty()) return q;
        } catch (SQLException ignored) {}
        // MySQL drivers sometimes return "`" directly; MSSQL returns `"`.
        return dialect == Type.SQLServer ? "\"" : "`";
    }

    /** Wraps an already-sanitized identifier in the correct quote characters. */
    private static String quoteIdentifier(String safe, String q) {
        return q + safe + q;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public DatabaseTool() {
        super("database", "A set of tools for interacting with a relational database via SQL.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // db/list-tables
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists all user tables (and views) in the connected database.
     * Uses JDBC {@link DatabaseMetaData#getTables} — fully portable across
     * MySQL, SQLite, H2, MSSQL, and any other JDBC driver.
     *
     * @return JSON object containing a {@code tables} array of
     *         {@code {name, type, remarks}} entries.
     */
    @Action(
            value = "db/list-tables",
            description = "List all tables and views that exist in the connected database. " +
                    "READ-ONLY operation — does not modify any data."
    )
    public String listTables() throws MCPException {
        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            DatabaseMetaData meta = operator.getMetaData();
            Type dialect = detectDialect(meta);

            // catalog / schema scoping — SQLite has no catalog or schema.
            String catalog = operator.getCatalog();
            String schema  = dialect == Type.SQLite ? null : operator.getSchema();

            Builders tables = new Builders();
            try (ResultSet rs = meta.getTables(catalog, schema, "%",
                    new String[]{"TABLE", "VIEW", "SYSTEM TABLE"})) {
                while (rs.next()) {
                    // Skip internal H2 / MSSQL system tables reported via the wildcard.
                    String tableType = rs.getString("TABLE_TYPE");
                    if ("SYSTEM TABLE".equalsIgnoreCase(tableType)) continue;

                    Builder table = new Builder();
                    table.put("name", rs.getString("TABLE_NAME"));
                    table.put("type", tableType);
                    String remarks = rs.getString("REMARKS");
                    if (remarks != null && !remarks.isEmpty()) {
                        table.put("remarks", remarks);
                    }
                    tables.add(table);
                }
            }

            result.put("success", true);
            result.put("tables", tables);
        } catch (ApplicationException | SQLException e) {
            logger.log(Level.SEVERE, "list-tables failed", e);
            throw new MCPException("list-tables failed: " + e.getMessage(), e);
        }
        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // db/describe
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Describes the columns of a table (name, type, size, nullable, PK,
     * default, auto-increment) using JDBC {@link DatabaseMetaData} —
     * no vendor-specific SQL required.
     *
     * @param table The table name to describe.
     * @return JSON object containing a {@code columns} array.
     */
    @Action(
            value = "db/describe",
            description = "Describe the schema of a table: column names, data types, sizes, " +
                    "nullability, primary keys, and auto-increment flags. " +
                    "READ-ONLY operation — does not modify any data.",
            arguments = {
                    @Argument(key = "table", description = "The name of the table to describe.", type = "string")
            }
    )
    public String describe(String table) throws MCPException {
        if (table == null || table.trim().isEmpty()) {
            throw new MCPException("Missing 'table' parameter.");
        }
        String safeTable = sanitizeIdentifier(table);

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            DatabaseMetaData meta = operator.getMetaData();
            Type dialect = detectDialect(meta);

            String catalog = operator.getCatalog();
            String schema  = dialect == Type.SQLite ? null : operator.getSchema();

            // Collect primary-key column names up front.
            java.util.Set<String> pkColumns = new java.util.HashSet<>();
            try (ResultSet pkRs = meta.getPrimaryKeys(catalog, schema, safeTable)) {
                while (pkRs.next()) {
                    pkColumns.add(pkRs.getString("COLUMN_NAME"));
                }
            }

            Builders columns = new Builders();
            try (ResultSet rs = meta.getColumns(catalog, schema, safeTable, "%")) {
                while (rs.next()) {
                    Builder col = new Builder();
                    String colName = rs.getString("COLUMN_NAME");
                    col.put("name", colName);
                    col.put("type", rs.getString("TYPE_NAME"));
                    int size = rs.getInt("COLUMN_SIZE");
                    col.put("size", rs.wasNull() ? 0 : size);
                    col.put("nullable",
                            rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    col.put("primary_key", pkColumns.contains(colName));
                    String def = rs.getString("COLUMN_DEF");
                    col.put("default", def != null ? def : "");
                    // IS_AUTOINCREMENT is JDBC 4.0+; not all drivers populate it.
                    String autoInc = rs.getString("IS_AUTOINCREMENT");
                    col.put("auto_increment", "YES".equalsIgnoreCase(autoInc));
                    columns.add(col);
                }
            }

            if (columns.isEmpty()) {
                throw new MCPException("Table not found or has no columns: " + safeTable);
            }

            result.put("success", true);
            result.put("table", safeTable);
            result.put("columns", columns);
        } catch (ApplicationException | SQLException e) {
            logger.log(Level.SEVERE, "describe failed for table: " + safeTable, e);
            throw new MCPException("describe failed for table '" + safeTable + "': " + e.getMessage(), e);
        }
        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // db/query
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Selects rows from a table with an optional WHERE clause and row limit.
     * Table and column identifiers are quoted using the driver-reported
     * quote character, so the query works on every supported dialect.
     *
     * @param table The table name to query.
     * @return JSON object with {@code rows} array and metadata.
     */
    @Action(
            value = "db/query",
            description = "Query rows from a table. Returns up to 100 rows by default. " +
                    "READ-ONLY operation — does not modify any data.",
            arguments = {
                    @Argument(key = "table", description = "The name of the table to query.", type = "string")
            }
    )
    public String query(String table) throws MCPException {
        return query(table, null, 100);
    }

    @Action(
            value = "db/query",
            description = "Query rows from a table with an optional filter and row limit. " +
                    "READ-ONLY operation — does not modify any data.",
            arguments = {
                    @Argument(key = "table", description = "The name of the table to query.", type = "string"),
                    @Argument(key = "where", description = "Optional SQL WHERE clause without the WHERE keyword (e.g. id = 1 AND status = 'active').", type = "string"),
                    @Argument(key = "limit", description = "Maximum number of rows to return (1–1000, default 100).", type = "integer")
            }
    )
    public String query(String table, String where, Integer limit) throws MCPException {
        if (table == null || table.trim().isEmpty()) {
            throw new MCPException("Missing 'table' parameter.");
        }
        String safeTable = sanitizeIdentifier(table);
        if (limit == null || limit <= 0 || limit > MAX_ROWS) limit = 100;

        if (where != null && !where.trim().isEmpty()) {
            validateWhereClause(where);
        }

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            DatabaseMetaData meta = operator.getMetaData();
            Type dialect = detectDialect(meta);
            String q = quoteChar(meta, dialect);
            String quotedTable = quoteIdentifier(safeTable, q);

            String sql = buildSelectSql(quotedTable, where, limit, dialect);

            ResultSet rs = operator.query(sql);
            Builders rows;
            try {
                rows = resultSetToBuilders(rs);
            } finally {
                rs.close();
            }

            result.put("success", true);
            result.put("table", safeTable);
            result.put("sql", sql);
            result.put("count", rows.size());
            result.put("rows", rows);
        } catch (ApplicationException | SQLException e) {
            logger.log(Level.SEVERE, "query failed on: " + safeTable, e);
            throw new MCPException("query failed on '" + safeTable + "': " + e.getMessage(), e);
        }
        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // db/insert
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inserts a row into a table using a parameterized {@link PreparedStatement}.
     * Column names are sanitized and quoted for the active dialect.
     *
     * @param table The table name.
     * @param data  JSON object with column→value pairs,
     *              e.g. {@code {"username":"alice","email":"a@b.com"}}.
     * @return JSON object indicating success and rows affected.
     */
    @Action(
            value = "db/insert",
            description = "Insert one or more new rows into a table. " +
                    "WRITES DATA — this operation is persistent and irreversible without a backup.",
            arguments = {
                    @Argument(key = "table", description = "The name of the table to insert into.", type = "string"),
                    @Argument(key = "data", description = "JSON object with column-value pairs to insert (e.g. {\"name\":\"Alice\",\"age\":30}).", type = "object")
            }
    )
    public String insert(String table, Builder data) throws MCPException {
        if (table == null || table.trim().isEmpty()) {
            throw new MCPException("Missing 'table' parameter.");
        }
        if (data == null || data.isEmpty()) {
            throw new MCPException("Missing 'data' parameter or it's empty.");
        }
        String safeTable = sanitizeIdentifier(table);

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            DatabaseMetaData meta = operator.getMetaData();
            String q = quoteChar(meta, detectDialect(meta));

            java.util.List<String> cols = new java.util.ArrayList<>(data.keySet());
            StringBuilder sql = new StringBuilder("INSERT INTO ")
                    .append(quoteIdentifier(safeTable, q)).append(" (");
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                sql.append(quoteIdentifier(sanitizeIdentifier(cols.get(i)), q));
                placeholders.append("?");
                if (i < cols.size() - 1) { sql.append(", "); placeholders.append(", "); }
            }
            sql.append(") VALUES (").append(placeholders).append(")");

            Object[] params = cols.stream().map(data::get).toArray();
            PreparedStatement ps = operator.preparedStatement(sql.toString(), params);
            int affected = operator.executeUpdate(ps);

            result.put("success", true);
            result.put("table", safeTable);
            result.put("rows_affected", affected);
            result.put("sql", sql.toString());
        } catch (ApplicationException e) {
            logger.log(Level.SEVERE, "insert failed on: " + safeTable, e);
            throw new MCPException("insert failed on '" + safeTable + "': " + e.getMessage(), e);
        }
        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // db/update
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates rows in a table. A WHERE clause is mandatory for safety.
     * Column names in {@code data} are sanitized and quoted for the active dialect.
     *
     * @param table The table name.
     * @param data  JSON object with column→value pairs to SET.
     * @param where SQL WHERE clause (required).
     * @return JSON object indicating success and rows affected.
     */
    @Action(
            value = "db/update",
            description = "Update existing rows in a table that match a filter. A WHERE clause is required. " +
                    "WRITES DATA — modifies existing records. This operation is irreversible without a backup.",
            arguments = {
                    @Argument(key = "table", description = "The name of the table to update.", type = "string"),
                    @Argument(key = "data", description = "JSON object with column-value pairs to set (e.g. {\"status\":\"active\"}).", type = "object"),
                    @Argument(key = "where", description = "SQL WHERE clause without the WHERE keyword — required to prevent updating all rows (e.g. id = 5).", type = "string")
            }
    )
    public String update(String table, Builder data, String where) throws MCPException {
        if (table == null || table.trim().isEmpty()) {
            throw new MCPException("Missing 'table' parameter.");
        }
        if (data == null || data.keySet().isEmpty()) {
            throw new MCPException("Missing 'data' parameter or it's empty.");
        }
        if (where == null || where.trim().isEmpty()) {
            throw new MCPException("Missing 'where' parameter. A WHERE clause is required for safety.");
        }
        validateWhereClause(where);

        String safeTable = sanitizeIdentifier(table);
        Builder dataObj = data;
        if (dataObj.keySet().isEmpty()) {
            throw new MCPException("'data' object must contain at least one column-value pair.");
        }

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            DatabaseMetaData meta = operator.getMetaData();
            String q = quoteChar(meta, detectDialect(meta));

            java.util.List<String> cols = new java.util.ArrayList<>(dataObj.keySet());
            StringBuilder sql = new StringBuilder("UPDATE ")
                    .append(quoteIdentifier(safeTable, q)).append(" SET ");
            for (int i = 0; i < cols.size(); i++) {
                sql.append(quoteIdentifier(sanitizeIdentifier(cols.get(i)), q)).append(" = ?");
                if (i < cols.size() - 1) sql.append(", ");
            }
            sql.append(" WHERE ").append(where);

            Object[] params = cols.stream().map(dataObj::get).toArray();
            PreparedStatement ps = operator.preparedStatement(sql.toString(), params);
            int affected = operator.executeUpdate(ps);

            result.put("success", true);
            result.put("table", safeTable);
            result.put("rows_affected", affected);
            result.put("sql", sql.toString());
        } catch (ApplicationException e) {
            logger.log(Level.SEVERE, "update failed on: " + safeTable, e);
            throw new MCPException("update failed on '" + safeTable + "': " + e.getMessage(), e);
        }
        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // db/delete
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Deletes rows from a table matching a WHERE condition.
     *
     * @param table The table name.
     * @param where SQL WHERE clause (required — prevents full-table deletes).
     * @return JSON object indicating success and rows affected.
     */
    @Action(
            value = "db/delete",
            description = "Delete rows from a table that match a filter. A WHERE clause is required. " +
                    "DESTRUCTIVE — permanently removes data. This operation is irreversible without a backup.",
            arguments = {
                    @Argument(key = "table", description = "The name of the table to delete from.", type = "string"),
                    @Argument(key = "where", description = "SQL WHERE clause without the WHERE keyword — required to prevent deleting all rows (e.g. id = 5).", type = "string")
            }
    )
    public String delete(String table, String where) throws MCPException {
        if (table == null || table.trim().isEmpty()) {
            throw new MCPException("Missing 'table' parameter.");
        }
        if (where == null || where.trim().isEmpty()) {
            throw new MCPException("Missing 'where' parameter. A WHERE clause is required for safety.");
        }
        validateWhereClause(where);

        String safeTable = sanitizeIdentifier(table);

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            DatabaseMetaData meta = operator.getMetaData();
            String q = quoteChar(meta, detectDialect(meta));

            String sql = "DELETE FROM " + quoteIdentifier(safeTable, q) + " WHERE " + where;
            int affected = operator.update(sql);

            result.put("success", true);
            result.put("table", safeTable);
            result.put("rows_affected", affected);
            result.put("sql", sql);
        } catch (ApplicationException e) {
            logger.log(Level.SEVERE, "delete failed on: " + safeTable, e);
            throw new MCPException("delete failed on '" + safeTable + "': " + e.getMessage(), e);
        }
        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // db/execute
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes arbitrary SQL and returns rows (SELECT/SHOW/DESCRIBE/EXPLAIN)
     * or an affected-row count (DML/DDL).
     *
     * <p><strong>Security note:</strong> This action accepts raw SQL. Restrict
     * access to trusted callers at the MCP layer.</p>
     *
     * @param sql The raw SQL statement to execute.
     * @return JSON object with query rows or update count.
     */
    @Action(
            value = "db/execute",
            description = "Execute a raw SQL statement. " +
                    "SELECT, WITH, SHOW, DESCRIBE, EXPLAIN, VALUES and PRAGMA are READ-ONLY. " +
                    "INSERT, UPDATE, DELETE, DROP, TRUNCATE, ALTER, CREATE, GRANT, and REVOKE are " +
                    "DESTRUCTIVE and irreversible without a backup. Use this only for complex queries " +
                    "(JOINs, CTEs, subqueries) or DDL that cannot be expressed with the other tools.",
            arguments = {
                    @Argument(key = "sql", description = "The raw SQL statement to execute.", type = "string")
            }
    )
    public String execute(String sql) throws MCPException {
        if (sql == null || sql.trim().isEmpty()) {
            throw new MCPException("Missing 'sql' parameter.");
        }
        sql = sql.trim();
        Builder result = new Builder();

        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();

            // Only statements proven read-only take the query path; anything else
            // (including WITH ... DELETE and stacked statements) is run as an update.
            if (isReadOnlySql(sql)) {

                ResultSet rs = operator.query(sql);
                Builders rows;
                try {
                    rows = resultSetToBuilders(rs);
                } finally {
                    rs.close();
                }
                result.put("success", true);
                result.put("type", "query");
                result.put("count", rows.size());
                result.put("rows", rows);
            } else {
                int affected = operator.update(sql);
                result.put("success", true);
                result.put("type", "update");
                result.put("rows_affected", affected);
            }
            result.put("sql", sql);
        } catch (ApplicationException | SQLException e) {
            logger.log(Level.SEVERE, "execute failed", e);
            throw new MCPException("execute failed: " + e.getMessage(), e);
        }
        return result.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCPTool override
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected Object executeLocally(Builder builder) throws MCPException {
        throw new MCPException("Use individual @Action-annotated methods via MCPServer.registerTool().");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Keywords that make a statement (or a fragment of one) something other than a plain read. */
    private static final java.util.regex.Pattern WRITE_KEYWORDS = java.util.regex.Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|REPLACE|UPSERT|DROP|TRUNCATE|ALTER|CREATE|GRANT|REVOKE|" +
            "CALL|EXEC|EXECUTE|COPY|ATTACH|DETACH|VACUUM|SET|INTO|LOCK|RENAME|COMMENT|ANALYZE)\\b");
    private static final java.util.regex.Pattern READ_START = java.util.regex.Pattern.compile(
            "^(SELECT|WITH|SHOW|DESCRIBE|EXPLAIN|VALUES|HELP)\\b");

    /** Removes single-quoted string literals and double-quoted identifiers so keywords inside them are ignored. */
    private static String stripLiterals(String sql) {
        return sql.replaceAll("'(?:[^']|'')*'", "''").replaceAll("\"(?:[^\"]|\"\")*\"", "\"\"");
    }

    /**
     * Conservative classifier: {@code true} only when the statement is demonstrably read-only.
     * Anything ambiguous — stacked statements, comments, a write keyword anywhere (so
     * {@code WITH x AS (...) DELETE ...} is caught), PRAGMA assignments — returns {@code false}.
     * A false negative only costs an extra approval prompt; a false positive would skip one.
     */
    public static boolean isReadOnlySql(String sql) {
        if (sql == null) return false;
        String s = sql.trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        String code = stripLiterals(s);
        if (code.contains(";") || code.contains("--") || code.contains("/*")) return false;
        String upper = code.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PRAGMA")) return !upper.contains("=") && !upper.contains("(");
        if (!READ_START.matcher(upper).find()) return false;
        return !WRITE_KEYWORDS.matcher(upper).find();
    }

    /**
     * Builds a SELECT statement with a dialect-correct row-limiting clause.
     *
     * <ul>
     *   <li>MySQL, SQLite, H2, generic: {@code LIMIT n}</li>
     *   <li>MSSQL: {@code SELECT TOP n ...}</li>
     * </ul>
     */
    private static String buildSelectSql(String quotedTable, String where,
                                         int limit, Type dialect) {
        StringBuilder sql = new StringBuilder();
        if (dialect == Type.SQLServer) {
            sql.append("SELECT TOP ").append(limit).append(" * FROM ").append(quotedTable);
        } else {
            sql.append("SELECT * FROM ").append(quotedTable);
        }
        if (where != null && !where.trim().isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        if (dialect != Type.SQLServer) {
            sql.append(" LIMIT ").append(limit);
        }
        return sql.toString();
    }

    /**
     * Converts a {@link ResultSet} to a {@link Builders} JSON array.
     * SQL {@code NULL} values are stored as {@code null} (not the string {@code "null"}).
     */
    private static Builders resultSetToBuilders(ResultSet rs) throws SQLException {
        Builders rows = new Builders();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        while (rs.next()) {
            Builder row = new Builder();
            for (int i = 1; i <= colCount; i++) {
                Object val = rs.getObject(i);
                row.put(meta.getColumnLabel(i), val != null ? val.toString() : null);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Strips every character that is not alphanumeric or an underscore from a
     * SQL identifier. Throws {@link MCPException} if the result is empty.
     */
    private static String sanitizeIdentifier(String name) throws MCPException {
        if (name == null || name.trim().isEmpty()) {
            throw new MCPException("SQL identifier must not be null or blank.");
        }
        String safe = name.replaceAll("[^a-zA-Z0-9_]", "");
        if (safe.isEmpty()) {
            throw new MCPException("SQL identifier '" + name + "' contains no valid characters.");
        }
        return safe;
    }

    private static final java.util.regex.Pattern WHERE_FORBIDDEN = java.util.regex.Pattern.compile(
            "\\b(SELECT|UNION|INSERT|UPDATE|DELETE|MERGE|DROP|TRUNCATE|ALTER|CREATE|GRANT|REVOKE|CALL|EXEC|EXECUTE|INTO|" +
            "SLEEP|BENCHMARK|PG_SLEEP|WAITFOR|LOAD_FILE|OUTFILE|DUMPFILE|XP_CMDSHELL)\\b");
    private static final java.util.regex.Pattern TAUTOLOGY = java.util.regex.Pattern.compile(
            "(^|\\bOR\\b)\\s*(TRUE|(\\d+)\\s*=\\s*\\3|(''|\"\")\\s*=\\s*(''|\"\")|('[^']*')\\s*=\\s*\\6)\\s*($|\\bOR\\b)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Validates a caller-supplied WHERE clause. The clause is spliced into the SQL text, so it is
     * restricted to a plain predicate: no stacked statements, comments, subqueries, UNION, DML/DDL
     * or time-delay functions, quotes must balance, and a trivially-true condition ({@code 1=1},
     * {@code TRUE}, {@code OR 'a'='a'}) is refused so it cannot defeat the "WHERE is required" rule.
     *
     * @throws MCPException if a forbidden pattern is detected.
     */
    private static void validateWhereClause(String where) throws MCPException {
        if (where == null) return;
        // Empty the (now placeholder) literals so any quote still present is an unterminated one.
        String stripped = stripLiterals(where).replace("''", "").replace("\"\"", "");
        if (stripped.contains("'") || stripped.contains("\"")) {
            throw new MCPException("WHERE clause has unbalanced quotes.");
        }
        if (stripped.contains(";")) {
            throw new MCPException("WHERE clause must not contain a statement terminator (;).");
        }
        if (stripped.contains("--") || stripped.contains("/*") || stripped.contains("*/") || stripped.contains("#")) {
            throw new MCPException("WHERE clause must not contain SQL comment sequences.");
        }
        if (WHERE_FORBIDDEN.matcher(stripped.toUpperCase(Locale.ROOT)).find()) {
            throw new MCPException("WHERE clause must be a plain predicate (no subqueries, UNION, or DML/DDL keywords).");
        }
        // Tautology check runs on the original so 'a'='a' style literals are still visible.
        if (TAUTOLOGY.matcher(where.trim()).find()) {
            throw new MCPException("WHERE clause is always true; use a condition that selects specific rows.");
        }
    }
}
