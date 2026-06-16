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
 * {@code MCPServer.registerTool()}. Parameters are declared as explicit method
 * arguments annotated with {@code @Argument}; {@code getContext()} is never
 * used.</p>
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
            description = "List all tables in the connected database."
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
            description = "Describe the columns and types of a specific table.",
            arguments = {
                    @Argument(key = "table", description = "The table name to describe", type = "string")
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
            description = "SELECT rows from a table without WHERE or LIMIT.",
            arguments = {
                    @Argument(key = "table", description = "The table name to query", type = "string")
            }
    )
    public String query(String table) throws MCPException {
        return query(table, null, 100);
    }

    @Action(
            value = "db/query",
            description = "SELECT rows from a table with optional WHERE and LIMIT.",
            arguments = {
                    @Argument(key = "table", description = "The table name to query", type = "string"),
                    @Argument(key = "where", description = "Optional SQL WHERE clause (e.g. id=1)", type = "string"),
                    @Argument(key = "limit", description = "Max number of rows to return (1-1000, default 100)", type = "integer")
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
            description = "INSERT a row into a table.",
            arguments = {
                    @Argument(key = "table", description = "The table name", type = "string"),
                    @Argument(key = "data", description = "JSON object with column→value pairs", type = "object")
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
            description = "UPDATE rows in a table matching a WHERE clause.",
            arguments = {
                    @Argument(key = "table", description = "The table name", type = "string"),
                    @Argument(key = "data", description = "JSON object with column→value pairs to set", type = "object"),
                    @Argument(key = "where", description = "SQL WHERE clause (required for safety)", type = "string")
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
            description = "DELETE rows from a table matching a WHERE clause.",
            arguments = {
                    @Argument(key = "table", description = "The table name", type = "string"),
                    @Argument(key = "where", description = "SQL WHERE clause (required for safety)", type = "string")
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
            description = "Execute arbitrary SQL. Returns rows for SELECT/SHOW/DESCRIBE/EXPLAIN, affected rows otherwise.",
            arguments = {
                    @Argument(key = "sql", description = "The raw SQL statement to execute", type = "string")
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

            String upper = sql.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SELECT") || upper.startsWith("SHOW")
                    || upper.startsWith("DESCRIBE") || upper.startsWith("EXPLAIN")) {
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

    /**
     * Parses a JSON string into a {@link Builder}.
     *
     * @throws MCPException if the string is not valid JSON.
     */
    private static Builder parseJson(String json) throws MCPException {
        Builder b = new Builder();
        try {
            b.parse(json);
        } catch (ApplicationException e) {
            throw new MCPException("Invalid JSON: " + e.getMessage(), e);
        }
        return b;
    }

    /**
     * Validates a caller-supplied WHERE clause against the most common
     * SQL-injection patterns: statement terminators, comment sequences.
     * This is a defence-in-depth measure on top of parameterised queries.
     *
     * @throws MCPException if a forbidden pattern is detected.
     */
    private static void validateWhereClause(String where) throws MCPException {
        if (where == null) return;
        if (where.contains(";")) {
            throw new MCPException("WHERE clause must not contain a statement terminator (;).");
        }
        String upper = where.toUpperCase(Locale.ROOT);
        if (upper.contains("--") || upper.contains("/*") || upper.contains("*/")) {
            throw new MCPException("WHERE clause must not contain SQL comment sequences.");
        }
    }
}