package hungteen.ide.common.codec.parser;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CodecSchemaParserTest {

    @Test
    void recognizesPrimitiveCodecsWithoutGameBootstrap() {
        DataSchema integer = CodecSchemaParser.parse(Codec.INT);
        assertEquals(SchemaType.INT, integer.type());
        assertEquals(Integer.class, integer.javaType());
        assertEquals(SchemaType.STRING, CodecSchemaParser.parse(Codec.STRING).type());
        assertEquals(SchemaType.BOOLEAN, CodecSchemaParser.parse(Codec.BOOL).type());
    }

    @Test
    void discoversRecordFieldsThroughDfuBuilderState() {
        Codec<SimpleRecord> codec = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(SimpleRecord::name),
                Codec.INT.fieldOf("count").forGetter(SimpleRecord::count)
        ).apply(instance, SimpleRecord::new));

        DataSchema schema = CodecSchemaParser.parse(codec);
        assertEquals(SchemaType.RECORD, schema.type());
        Map<String, FieldSchema> fields = fieldsByName(schema);
        assertEquals(2, fields.size());
        assertEquals(SchemaType.STRING, fields.get("name").schema().type());
        assertEquals(SchemaType.INT, fields.get("count").schema().type());
        for (FieldSchema field : fields.values()) {
            assertTrue(field.required());
            assertEquals(field.name(), field.path());
            assertInstanceOf(ValueInfo.Absent.class, field.defaultValue());
        }
    }

    @Test
    void preservesOptionalElementTypesAndDistinguishesMissingFromDefaultValues() {
        Codec<OptionalRecord> codec = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("note").forGetter(OptionalRecord::note),
                Codec.INT.optionalFieldOf("limit", 4).forGetter(OptionalRecord::limit)
        ).apply(instance, OptionalRecord::new));

        DataSchema schema = CodecSchemaParser.parse(codec);
        assertEquals(SchemaType.RECORD, schema.type());
        Map<String, FieldSchema> fields = fieldsByName(schema);
        assertEquals(2, fields.size());
        FieldSchema note = fields.get("note");
        FieldSchema limit = fields.get("limit");
        assertFalse(note.required());
        assertFalse(limit.required());
        assertEquals(SchemaType.OPTIONAL, note.schema().type());
        assertEquals(SchemaType.STRING, note.schema().element().type());
        assertEquals(SchemaType.OPTIONAL, limit.schema().type());
        assertEquals(SchemaType.INT, limit.schema().element().type());
        assertInstanceOf(ValueInfo.Absent.class, note.defaultValue());
        assertEquals(4, assertInstanceOf(ValueInfo.Static.class, limit.defaultValue()).value());
    }

    private static Map<String, FieldSchema> fieldsByName(DataSchema schema) {
        return schema.fields().stream().collect(Collectors.toMap(FieldSchema::name, Function.identity()));
    }

    private record SimpleRecord(String name, int count) {
    }

    private record OptionalRecord(Optional<String> note, int limit) {
    }
}
