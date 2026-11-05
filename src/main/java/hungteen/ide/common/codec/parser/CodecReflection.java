package hungteen.ide.common.codec.parser;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort access to codec implementation fields, including captured lambda state.
 * Kept here so schema parsing works with the published HTLib 1.2.0 release.
 */
public final class CodecReflection {

    private CodecReflection() {
    }

    /** Read the nearest declaration of a field, returning null when it cannot be read. */
    public static Object getField(Object target, String fieldName) {
        if (target == null || fieldName == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                return field.trySetAccessible() ? field.get(target) : null;
            } catch (NoSuchFieldException ignored) {
                // A wrapper may inherit the state we need from its parent.
            } catch (IllegalAccessException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        return null;
    }

    /** Accessible instance fields, from the concrete class up through its parents. */
    public static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] declared;
            try {
                declared = current.getDeclaredFields();
            } catch (RuntimeException | LinkageError ignored) {
                continue;
            }
            for (Field field : declared) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    if (field.trySetAccessible()) {
                        result.add(field);
                    }
                } catch (RuntimeException ignored) {
                    // Closed modules and optional classes should not break schema discovery.
                }
            }
        }
        return result;
    }
}
