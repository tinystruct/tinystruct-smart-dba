package org.tinystruct.app.tools;

import org.junit.jupiter.api.Test;
import org.tinystruct.mcp.MCPException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseToolSafetyTest {

    @Test
    void readOnlyStatementsAreRecognised() {
        assertTrue(DatabaseTool.isReadOnlySql("SELECT * FROM \"user\""));
        assertTrue(DatabaseTool.isReadOnlySql("select 1;"));
        assertTrue(DatabaseTool.isReadOnlySql("WITH t AS (SELECT 1) SELECT * FROM t"));
        assertTrue(DatabaseTool.isReadOnlySql("SELECT 'DELETE me' AS note FROM t"));
        assertTrue(DatabaseTool.isReadOnlySql("EXPLAIN SELECT 1"));
    }

    @Test
    void writesAndAmbiguousStatementsAreNotReadOnly() {
        assertFalse(DatabaseTool.isReadOnlySql("DELETE FROM t WHERE id = 1"));
        assertFalse(DatabaseTool.isReadOnlySql("WITH d AS (SELECT 1) DELETE FROM t"));
        assertFalse(DatabaseTool.isReadOnlySql("SELECT 1; DROP TABLE t"));
        assertFalse(DatabaseTool.isReadOnlySql("SELECT 1 -- x\n; DROP TABLE t"));
        assertFalse(DatabaseTool.isReadOnlySql("SELECT * INTO copy FROM t"));
        assertFalse(DatabaseTool.isReadOnlySql("PRAGMA writable_schema = 1"));
        assertFalse(DatabaseTool.isReadOnlySql("CREATE TABLE t (id INT)"));
        assertFalse(DatabaseTool.isReadOnlySql(""));
        assertFalse(DatabaseTool.isReadOnlySql(null));
    }

    private static void assertRejected(String where) {
        assertThrows(MCPException.class, () -> invokeValidate(where), where);
    }

    private static void invokeValidate(String where) throws Exception {
        var m = DatabaseTool.class.getDeclaredMethod("validateWhereClause", String.class);
        m.setAccessible(true);
        try {
            m.invoke(null, where);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void plainPredicatesAreAccepted() {
        assertDoesNotThrow(() -> invokeValidate("id = 5"));
        assertDoesNotThrow(() -> invokeValidate("status = 'active' AND age > 30"));
        assertDoesNotThrow(() -> invokeValidate("id IN (1, 2, 3)"));
        assertDoesNotThrow(() -> invokeValidate("name = 'a;b'"));
    }

    @Test
    void dangerousPredicatesAreRejected() {
        assertRejected("1=1");
        assertRejected("id = 5 OR 1=1");
        assertRejected("TRUE");
        assertRejected("id = 5 OR 'a'='a'");
        assertRejected("id IN (SELECT id FROM secrets)");
        assertRejected("id = 1 UNION SELECT 1");
        assertRejected("id = 1; DROP TABLE t");
        assertRejected("id = 1 -- rest");
        assertRejected("name = 'unterminated");
    }
}
