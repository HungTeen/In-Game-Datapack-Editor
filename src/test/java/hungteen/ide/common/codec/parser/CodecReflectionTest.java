package hungteen.ide.common.codec.parser;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodecReflectionTest {

    @Test
    void readsInheritedPrivateFieldsAndPrefersNearestDeclaration() {
        Child value = new Child();
        assertEquals("inherited", CodecReflection.getField(value, "inherited"));
        assertEquals("child", CodecReflection.getField(value, "shadowed"));
        assertNull(CodecReflection.getField(value, "missing"));
        assertNull(CodecReflection.getField(null, "missing"));
        assertNull(CodecReflection.getField(value, null));
    }

    @Test
    void enumeratesReadableInstanceFieldsWithoutGlobalStaticState() throws Exception {
        List<Field> fields = CodecReflection.fields(Child.class);
        assertEquals(List.of("shadowed", "inherited", "shadowed"),
                fields.stream().map(Field::getName).toList());
        Child value = new Child();
        assertEquals(List.of("child", "inherited", "parent"),
                List.of(fields.get(0).get(value), fields.get(1).get(value), fields.get(2).get(value)));
    }

    @Test
    void retainsSyntheticCapturedFieldsUsedByCodecs() throws Exception {
        Object captured = new Object();
        java.util.function.Supplier<Object> supplier = () -> captured;
        List<Field> fields = CodecReflection.fields(supplier.getClass());
        assertEquals(1, fields.size());
        assertSame(captured, fields.get(0).get(supplier));
    }

    @Test
    void toleratesUnavailableFieldsAndClosedJdkModules() {
        assertTrue(CodecReflection.fields(null).isEmpty());
        assertTrue(CodecReflection.fields(Object.class).isEmpty());
        assertDoesNotThrow(() -> CodecReflection.fields(String.class));
        assertDoesNotThrow(() -> CodecReflection.getField("text", "value"));
    }

    private static class Parent {
        private final String inherited = "inherited";
        private final String shadowed = "parent";
    }

    private static final class Child extends Parent {
        private static final Object GLOBAL_STATE = new Object();
        private final String shadowed = "child";
    }
}
