package org.tinystruct.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the interactive CLI actually puts on the screen.
 *
 * <p>Every case here is one where the agent used to print nothing, or print the wrong thing, and
 * the user had no way to tell which.
 */
class StreamRendererTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    /** Strips ANSI escapes so assertions are about text, not colour. */
    private String screen() {
        return captured.toString(StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", "");
    }

    private SmartDBA.StreamRenderer renderer() {
        return new SmartDBA().new StreamRenderer();
    }

    // ---- the tail of a stream ---------------------------------------------------------------

    @Test
    void theLastLineIsPrintedEvenWithoutATrailingNewline() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("All 4 tables are healthy.");

        assertEquals("", screen(), "nothing is printed until the line is known to be complete");

        renderer.finish();

        assertTrue(screen().contains("All 4 tables are healthy."), screen());
    }

    @Test
    void aWholeShortAnswerSurvivesAStreamThatEndsAbruptly() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("Done");   // one chunk, no newline, then the stream dies
        renderer.finish();       // called from the finally in internalChat

        assertTrue(screen().contains("Done"), screen());
    }

    @Test
    void finishIsSafeToCallTwice() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("hello");
        renderer.finish();
        renderer.finish();

        assertEquals(1, screen().split("hello", -1).length - 1, "must not print the tail twice");
    }

    @Test
    void aCodeBlockLeftOpenByATruncatedStreamIsStillClosed() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("```java\nint x = 1;\n");
        renderer.finish();

        String out = screen();
        assertTrue(out.contains("int x = 1;"), out);
        assertTrue(out.contains("└──"), "the box must be closed: " + out);
    }

    // ---- knowing when nothing was shown -----------------------------------------------------

    @Test
    void aTurnThatOnlyCarriedAToolCallReportsRenderingNothing() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("```json\n[{\"action\":\"mcp\",\"tool\":\"db/list-tables\"}]\n```\n");
        renderer.finish();

        assertEquals("", screen(), "a tool-call payload is not chat output");
        assertTrue(renderer.renderedNothing(), "the caller has to be able to say why the screen is blank");
        assertTrue(renderer.suppressedToolCall(), "and has to know it was a tool call, not an empty reply");
    }

    @Test
    void anEmptyReplyIsNotMistakenForASuppressedToolCall() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.finish();

        assertTrue(renderer.renderedNothing());
        assertFalse(renderer.suppressedToolCall(), "nothing was suppressed — there was simply no text");
    }

    @Test
    void aTurnWithProseReportsThatSomethingWasShown() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("Here are your tables.\n");
        renderer.finish();

        assertFalse(renderer.renderedNothing());
    }

    @Test
    void blankLinesAloneDoNotCountAsAResponse() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("\n\n   \n");
        renderer.finish();

        assertTrue(renderer.renderedNothing(), "whitespace told the user nothing");
    }

    @Test
    void anEmptyStreamReportsRenderingNothing() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.finish();

        assertTrue(renderer.renderedNothing());
    }

    // ---- prose is not a table ---------------------------------------------------------------

    @Test
    void proseContainingPipesIsPrintedAsProse() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("Use the form name | role | status when you ask.\n");
        renderer.finish();

        String out = screen();
        assertTrue(out.contains("Use the form name | role | status when you ask."), out);
        assertFalse(out.contains("┌"), "must not be redrawn as a table: " + out);
    }

    @Test
    void sqlUsingConcatenationIsNotATable() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("Try SELECT first || ' ' || last FROM users;\n");
        renderer.finish();

        assertTrue(screen().contains("first || ' ' || last"), screen());
    }

    @Test
    void aRealMarkdownTableIsStillDrawn() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("| name | rows |\n| --- | --- |\n| users | 42 |\n");
        renderer.finish();

        String out = screen();
        assertTrue(out.contains("users"), out);
        assertTrue(out.contains("42"), out);
        assertTrue(out.contains("┌") || out.contains("│"), "a genuine table is still box-drawn: " + out);
    }

    @Test
    void aTableAtTheVeryEndIsFlushed() {
        SmartDBA.StreamRenderer renderer = renderer();
        renderer.feed("| name |\n| --- |\n| users |");  // no trailing newline
        renderer.finish();

        assertTrue(screen().contains("users"), screen());
    }
}
