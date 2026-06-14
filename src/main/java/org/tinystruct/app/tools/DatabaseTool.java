package org.tinystruct.app.tools;

import org.tinystruct.ApplicationException;
import org.tinystruct.data.component.Builder;
import org.tinystruct.data.component.Builders;
import org.tinystruct.data.DatabaseOperator;
import org.tinystruct.mcp.MCPException;
import org.tinystruct.mcp.MCPTool;
import org.tinystruct.system.annotation.Action;
import org.tinystruct.system.annotation.Argument;

import java.sql.*;
import java.util.logging.Logger;
import java.util.logging.Level;

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
 * <p>Configure your database in {@code application.properties}:
 * <pre>
 *   driver=com.mysql.cj.jdbc.Driver
 *   database.url=jdbc:mysql://localhost:3306/mydb
 *   database.user=root
 *   database.password=secret
 * </pre>
 * </p>
 */
public class DatabaseTool extends MCPTool {

    private static final Logger logger = Logger.getLogger(DatabaseTool.class.getName());

    /**
     * Constructs a DatabaseTool for local execution.
     */
    public DatabaseTool() {
        super("database", "A set of tools for interacting with a relational database via SQL.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // db/list-tables
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists all user tables in the connected database using INFORMATION_SCHEMA.
     *
     * @return JSON array of table names and types.
     */
    @Action(
            value = "db/list-tables",
            description = "List all tables in the connected database."
    )
    public String listTables() throws MCPException {
        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            Connection conn = null;
            try {
                java.lang.reflect.Field field = org.tinystruct.data.DatabaseOperator.class.getDeclaredField("connection");
                field.setAccessible(true);
                conn = (Connection) field.get(operator);
            } catch (Exception e) {
                logger.warning("Could not access connection via reflection: " + e.getMessage());
            }

            Builders tables = new Builders();
            if (conn != null) {
                String catalog = null;
                try { catalog = conn.getCatalog(); } catch (Throwable t) {}
                String schema = null;
                try { schema = conn.getSchema(); } catch (Throwable t) {}

                try (ResultSet rs = conn.getMetaData().getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        Builder table = new Builder();
                        table.put("name", rs.getString("TABLE_NAME"));
                        table.put("type", rs.getString("TABLE_TYPE"));
                        String remarks = rs.getString("REMARKS");
                        if (remarks != null && !remarks.isEmpty()) {
                            table.put("remarks", remarks);
                        }
                        tables.add(table);
                    }
                }
            } else {
                ResultSet rs = operator.query(
                        "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT AS REMARKS " +
                        "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE()"
                );
                while (rs.next()) {
                    Builder table = new Builder();
                    table.put("name", rs.getString("TABLE_NAME"));
                    table.put("type", rs.getString("TABLE_TYPE"));
                    String remarks = rs.getString("REMARKS");
                    if (remarks != null && !remarks.isEmpty()) {
                        table.put("remarks", remarks);
                    }
                    tables.add(table);
                }
                rs.close();
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
     * Describes columns (name, type, nullable, primary key, default, auto-increment)
     * for the given table using a parameterized INFORMATION_SCHEMA query.
     *
     * @param table The table name to describe.
     * @return JSON object with column metadata.
     */
    @Action(
            value = "db/describe",
            description = "Describe the columns and types of a specific table.",
            arguments = {
                    @Argument(key = "table", description = "The table name to describe", type = "string")
            }
    )
    public String describe(String table) throws MCPException {
        String safeTable = sanitizeIdentifier(table);
        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            Connection conn = null;
            try {
                java.lang.reflect.Field field = org.tinystruct.data.DatabaseOperator.class.getDeclaredField("connection");
                field.setAccessible(true);
                conn = (Connection) field.get(operator);
            } catch (Exception e) {
                logger.warning("Could not access connection via reflection: " + e.getMessage());
            }

            Builders columns = new Builders();
            if (conn != null) {
                String catalog = null;
                try { catalog = conn.getCatalog(); } catch (Throwable t) {}
                String schema = null;
                try { schema = conn.getSchema(); } catch (Throwable t) {}

                DatabaseMetaData meta = conn.getMetaData();
                java.util.Set<String> pkSet = new java.util.HashSet<>();
                try (ResultSet pkRs = meta.getPrimaryKeys(catalog, schema, safeTable)) {
                    while (pkRs.next()) {
                        pkSet.add(pkRs.getString("COLUMN_NAME"));
                    }
                }

                try (ResultSet rs = meta.getColumns(catalog, schema, safeTable, "%")) {
                    while (rs.next()) {
                        Builder col = new Builder();
                        String colName = rs.getString("COLUMN_NAME");
                        col.put("name", colName);
                        col.put("type", rs.getString("TYPE_NAME"));
                        col.put("size", rs.getInt("COLUMN_SIZE"));
                        col.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                        col.put("primary_key", pkSet.contains(colName));
                        String def = rs.getString("COLUMN_DEF");
                        col.put("default", def != null ? def : "");
                        col.put("auto_increment", "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")));
                        columns.add(col);
                    }
                }
            } else {
                PreparedStatement ps = operator.preparedStatement(
                        "SELECT COLUMN_NAME, DATA_TYPE, " +
                        "  CHARACTER_MAXIMUM_LENGTH AS COLUMN_SIZE, " +
                        "  IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_KEY " +
                        "FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                        "ORDER BY ORDINAL_POSITION",
                        new Object[]{safeTable}
                );
                ResultSet rs = operator.executeQuery(ps);
                while (rs.next()) {
                    Builder col = new Builder();
                    col.put("name", rs.getString("COLUMN_NAME"));
                    col.put("type", rs.getString("DATA_TYPE"));
                    long size = rs.getLong("COLUMN_SIZE");
                    col.put("size", rs.wasNull() ? 0 : size);
                    col.put("nullable", "YES".equals(rs.getString("IS_NULLABLE")));
                    col.put("primary_key", "PRI".equals(rs.getString("COLUMN_KEY")));
                    String def = rs.getString("COLUMN_DEFAULT");
                    col.put("default", def != null ? def : "");
                    col.put("auto_increment", "auto_increment".equalsIgnoreCase(rs.getString("EXTRA")));
                    columns.add(col);
                }
                rs.close();
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
     * Queries rows from a table with optional WHERE and LIMIT.
     *
     * @param table The table name to query.
     * @param where Optional SQL WHERE clause (without the WHERE keyword). May be null or empty.
     * @param limit Maximum number of rows to return (default 100).
     * @return JSON object containing the matched rows.
     */
    @Action(
            value = "db/query",
            description = "SELECT rows from a table.",
            arguments = {
                    @Argument(key = "table", description = "The table name to query", type = "string")
            }
    )
    public String query(String table) throws MCPException {
        return this.query(table, null, 100);
    }

    @Action(
            value = "db/query",
            description = "SELECT rows from a table with optional WHERE and LIMIT.",
            arguments = {
                    @Argument(key = "table", description = "The table name to query", type = "string"),
                    @Argument(key = "where", description = "Optional SQL WHERE clause (e.g. id=1)", type = "string"),
                    @Argument(key = "limit", description = "Max number of rows to return (default 100)", type = "integer")
            }
    )
    public String query(String table, String where, int limit) throws MCPException {
        String safeTable = sanitizeIdentifier(table);
        if (limit <= 0) limit = 100;

        StringBuilder sql = new StringBuilder("SELECT * FROM `").append(safeTable).append("`");
        if (where != null && !where.trim().isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" LIMIT ").append(limit);

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            ResultSet rs = operator.query(sql.toString());
            Builders rows = resultSetToBuilders(rs);
            rs.close();

            result.put("success", true);
            result.put("table", safeTable);
            result.put("sql", sql.toString());
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
     * Inserts a row into the given table using a parameterized PreparedStatement.
     *
     * @param table The table name.
     * @param data  JSON object with column→value pairs, e.g. {@code {"username":"alice","email":"a@b.com"}}.
     * @return JSON object indicating success and rows affected.
     */
    @Action(
            value = "db/insert",
            description = "INSERT a row into a table.",
            arguments = {
                    @Argument(key = "table", description = "The table name", type = "string"),
                    @Argument(key = "data", description = "JSON object with column→value pairs", type = "string")
            }
    )
    public String insert(String table, String data) throws MCPException {
        String safeTable = sanitizeIdentifier(table);

        if (data == null || data.trim().isEmpty()) {
            throw new MCPException("Missing 'data' parameter with JSON column values.");
        }

        Builder dataObj = new Builder();
        try {
            dataObj.parse(data);
        } catch (ApplicationException e) {
            throw new MCPException("Invalid JSON in 'data' parameter: " + e.getMessage(), e);
        }

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            java.util.List<String> cols = new java.util.ArrayList<>(dataObj.keySet());
            StringBuilder sql = new StringBuilder("INSERT INTO `").append(safeTable).append("` (");
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                sql.append("`").append(sanitizeIdentifier(cols.get(i))).append("`");
                placeholders.append("?");
                if (i < cols.size() - 1) { sql.append(", "); placeholders.append(", "); }
            }
            sql.append(") VALUES (").append(placeholders).append(")");

            Object[] params = cols.stream().map(dataObj::get).toArray();
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
     * Updates rows in the given table. A WHERE clause is mandatory for safety.
     *
     * @param table The table name.
     * @param data  JSON object with column→value pairs to SET.
     * @param where SQL WHERE clause (required — prevents full-table updates).
     * @return JSON object indicating success and rows affected.
     */
    @Action(
            value = "db/update",
            description = "UPDATE rows in a table matching a WHERE clause.",
            arguments = {
                    @Argument(key = "table", description = "The table name", type = "string"),
                    @Argument(key = "data", description = "JSON object with column→value pairs to set", type = "string"),
                    @Argument(key = "where", description = "SQL WHERE clause (required for safety)", type = "string")
            }
    )
    public String update(String table, String data, String where) throws MCPException {
        String safeTable = sanitizeIdentifier(table);

        if (data == null || data.trim().isEmpty()) {
            throw new MCPException("Missing 'data' parameter.");
        }
        if (where == null || where.trim().isEmpty()) {
            throw new MCPException("Missing 'where' parameter. A WHERE clause is required for safety.");
        }

        Builder dataObj = new Builder();
        try {
            dataObj.parse(data);
        } catch (ApplicationException e) {
            throw new MCPException("Invalid JSON in 'data' parameter: " + e.getMessage(), e);
        }

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            java.util.List<String> cols = new java.util.ArrayList<>(dataObj.keySet());
            StringBuilder sql = new StringBuilder("UPDATE `").append(safeTable).append("` SET ");
            for (int i = 0; i < cols.size(); i++) {
                sql.append("`").append(sanitizeIdentifier(cols.get(i))).append("` = ?");
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
     * Deletes rows from the given table matching a WHERE condition.
     * The table identifier is sanitized; the WHERE clause is passed as-is.
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
        String safeTable = sanitizeIdentifier(table);

        if (where == null || where.trim().isEmpty()) {
            throw new MCPException("Missing 'where' parameter. A WHERE clause is required for safety.");
        }

        Builder result = new Builder();
        try (DatabaseOperator operator = new DatabaseOperator()) {
            operator.disableSafeCheck();
            String sql = "DELETE FROM `" + safeTable + "` WHERE " + where;
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
     * Executes arbitrary SQL and returns the result set (for SELECT/SHOW/DESCRIBE/EXPLAIN)
     * or affected-row count (for DML/DDL).
     * {@code disableSafeCheck()} is called so that valid DDL is not rejected by the
     * built-in SQL injection detector.
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
            // Allow DDL and administrative statements (CREATE, ALTER, DROP…).
            operator.disableSafeCheck();

            String upper = sql.toUpperCase();
            if (upper.startsWith("SELECT") || upper.startsWith("SHOW")
                    || upper.startsWith("DESCRIBE") || upper.startsWith("EXPLAIN")) {
                ResultSet rs = operator.query(sql);
                Builders rows = resultSetToBuilders(rs);
                rs.close();
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
    // MCPTool overrides
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Local execution is handled per-method via @Action-annotated methods and
     * {@code MCPServer.registerTool()} — this override is not used.
     */
    @Override
    protected Object executeLocally(Builder builder) throws MCPException {
        throw new MCPException("Use individual @Action-annotated methods via MCPServer.registerTool().");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Converts a ResultSet to a Builders (JSON array). */
    private Builders resultSetToBuilders(ResultSet rs) throws SQLException {
        Builders rows = new Builders();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        while (rs.next()) {
            Builder row = new Builder();
            for (int i = 1; i <= colCount; i++) {
                String col = meta.getColumnLabel(i);
                Object val = rs.getObject(i);
                row.put(col, val != null ? val.toString() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Strips characters that aren't alphanumeric or underscores from an
     * identifier to prevent SQL injection in table/column names.
     */
    private String sanitizeIdentifier(String name) {
        if (name == null) return "";
        return name.replaceAll("[^a-zA-Z0-9_]", "");
    }
}
