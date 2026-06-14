package org.tinystruct.app;

import org.tinystruct.app.tools.DatabaseTool;
import org.tinystruct.mcp.MCPServer;

/**
 * SmartDBA MCP Server — extends {@link MCPServer} and registers all
 * {@link DatabaseTool} operations as MCP-callable tools.
 *
 * <p>Deploy this server the same way as any tinystruct MCPServer:</p>
 * <pre>
 *   bin/dispatcher start --import org.tinystruct.system.HttpServer \
 *       --import org.tinystruct.app.SmartDBAServer
 * </pre>
 *
 * <p>Configure the database connection in {@code application.properties}:
 * <pre>
 *   driver=com.mysql.cj.jdbc.Driver
 *   database.url=jdbc:mysql://localhost:3306/mydb
 *   database.user=root
 *   database.password=secret
 * </pre>
 * </p>
 *
 * <h2>Registered MCP Tools</h2>
 * <ul>
 *   <li>{@code db/list-tables} — list all tables in the connected database</li>
 *   <li>{@code db/describe}    — describe columns of a table</li>
 *   <li>{@code db/query}       — SELECT rows with optional WHERE and LIMIT</li>
 *   <li>{@code db/insert}      — INSERT a row from a JSON data object</li>
 *   <li>{@code db/update}      — UPDATE rows matching a WHERE clause</li>
 *   <li>{@code db/delete}      — DELETE rows matching a WHERE clause</li>
 *   <li>{@code db/execute}     — execute arbitrary SQL</li>
 * </ul>
 */
public class SmartDBAServer extends MCPServer {

    @Override
    public void init() {
        super.init();

        // Register all DatabaseTool @Action methods as individual MCP tools.
        // MCPServer.registerTool() scans for @Action-annotated methods on the
        // DatabaseTool instance and adds each one as a named ToolMethod.
        this.registerTool(new DatabaseTool());
    }

    @Override
    public String version() {
        return "1.0.0";
    }
}
