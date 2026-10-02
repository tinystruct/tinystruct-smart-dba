package org.tinystruct.app;

import org.junit.jupiter.api.Test;
import org.tinystruct.app.SmartDBA.Completion;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The distinction between what the model said and why it said nothing.
 *
 * <p>These used to be the same type — a plain String — so a failure was filed in the conversation
 * as an assistant message and replayed to the model as context on the next request.
 */
class CompletionTest {

    @Test
    void whatTheModelSaidIsNotAFailure() {
        Completion spoken = Completion.spoken("Here are your tables.");

        assertFalse(spoken.failed());
        assertEquals("Here are your tables.", spoken.text());
        assertNull(spoken.failure());
    }

    @Test
    void aFailureCarriesNoTextForTheConversation() {
        Completion failure = Completion.failed("API returned status 429: rate limited");

        assertTrue(failure.failed());
        assertEquals("", failure.text(),
                "an error must contribute nothing to the history sent back to the model");
        assertEquals("API returned status 429: rate limited", failure.failure());
    }

    @Test
    void anEmptyAnswerIsStillAnAnswerNotAFailure() {
        Completion empty = Completion.spoken("");

        assertFalse(empty.failed(), "the model answering with nothing is not a transport error");
        assertEquals("", empty.text());
    }

    @Test
    void aNullBodyIsNormalisedToEmptyText() {
        assertEquals("", Completion.spoken(null).text());
    }
}
