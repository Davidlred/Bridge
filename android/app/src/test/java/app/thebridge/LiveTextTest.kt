package app.thebridge

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.thebridge.ui.components.LiveText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTextTest {
    private fun tv(s: String) = TextFieldValue(s, TextRange(s.length))

    @Test fun `fast typing keeps every letter while the ViewModel echoes late`() {
        val t = LiveText("from memor")
        t.typed(tv("from memory"))
        // The screen recomposes before the ViewModel catches up: the old value must not wipe the "y".
        assertFalse(t.incoming("from memor"))
        assertEquals("from memory", t.field.text)
        assertFalse(t.incoming("from memory"))
        assertEquals("from memory", t.field.text)
    }

    @Test fun `late echoes of earlier keystrokes are ignored`() {
        val t = LiveText("")
        listOf("a", "ab", "abc").forEach { t.typed(tv(it)) }
        assertFalse(t.incoming("a")); assertFalse(t.incoming("ab"))
        assertEquals("abc", t.field.text)
        assertFalse(t.incoming("abc"))
    }

    @Test fun `changes from elsewhere replace the text`() {
        val t = LiveText("")
        t.typed(tv("Run a"))
        assertFalse(t.incoming("Run a"))
        assertTrue(t.incoming(""))                       // message sent: the box clears
        assertEquals("", t.field.text)
        assertTrue(t.incoming("Run a half marathon"))    // "Use an example"
        assertEquals(TextRange(19), t.field.selection)
    }

    @Test fun `typing back to an earlier text still settles`() {
        val t = LiveText("")
        listOf("a", "ab", "a").forEach { t.typed(tv(it)) }
        listOf("a", "ab", "a").forEach { assertFalse(t.incoming(it)) }
        assertEquals("a", t.field.text)
    }

    @Test fun `stops at the cap and ignores cursor moves`() {
        val t = LiveText("abc", maxLength = 3)
        assertNull(t.typed(tv("abcd")))
        assertEquals("abc", t.field.text)
        assertNull(t.typed(TextFieldValue("abc", TextRange(1))))
        assertEquals(TextRange(1), t.field.selection)
        assertEquals("ab", t.typed(tv("ab")))            // deleting is always allowed
    }
}
