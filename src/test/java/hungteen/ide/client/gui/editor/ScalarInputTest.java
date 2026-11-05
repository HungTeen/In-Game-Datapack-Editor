package hungteen.ide.client.gui.editor;

import com.google.gson.JsonElement;
import hungteen.ide.common.codec.parser.SchemaType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScalarInputTest {
    @Test
    void longValuesKeepAllDigits() {
        for (String text : new String[]{"9007199254740993", "9223372036854775807", "-9223372036854775808"}) {
            JsonElement parsed = ScalarInput.parse(text, SchemaType.LONG);
            assertEquals(text, parsed.toString());
            assertTrue(ScalarInput.isValidNumber(parsed, SchemaType.LONG));
        }
    }

    @Test
    void invalidIntegersRemainEditableWithoutTruncationOrWrapping() {
        assertInvalid("abc", SchemaType.INT);
        assertInvalid("1.5", SchemaType.INT);
        assertInvalid("256", SchemaType.BYTE);
        assertInvalid("32768", SchemaType.SHORT);
        assertInvalid("2147483648", SchemaType.INT);
        assertInvalid("9223372036854775808", SchemaType.LONG);
        assertInvalid("-", SchemaType.INT);
    }

    @Test
    void nonFiniteFloatingPointValuesAreRejected() {
        assertInvalid("NaN", SchemaType.DOUBLE);
        assertInvalid("Infinity", SchemaType.FLOAT);
        assertInvalid("1e400", SchemaType.DOUBLE);
        assertInvalid("1e40", SchemaType.FLOAT);
        assertEquals(1.25, ScalarInput.parse("1.25", SchemaType.DOUBLE).getAsDouble());
    }

    @Test
    void stringsKeepWhitespaceAndQuotes() {
        assertEquals("  \"hello\"  ", ScalarInput.parse("  \"hello\"  ", SchemaType.STRING).getAsString());
    }

    private static void assertInvalid(String text, SchemaType type) {
        JsonElement parsed = ScalarInput.parse(text, type);
        assertTrue(parsed.getAsJsonPrimitive().isString());
        assertEquals(text, parsed.getAsString());
        assertFalse(ScalarInput.isValidNumber(parsed, type));
    }
}
