package hungteen.ide.client.gui.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditorFormNodeTest {
    @Test
    void missingOptionalFieldsStayAbsentInsteadOfOverridingCodecDefaults() {
        EditorFormNode node = EditorFormNode.root(schema("""
            {"type":"RECORD","fields":[
              {"name":"enabled","schema":{"type":"OPTIONAL","element":{"type":"BOOLEAN"}}},
              {"name":"count","schema":{"type":"OPTIONAL","element":{"type":"INT"}}},
              {"name":"items","schema":{"type":"OPTIONAL","element":{"type":"LIST","element":{"type":"STRING"}}}}
            ]}
            """));
        assertEquals(json("{}"), node.collect());
        assertFalse(node.hasErrors());
        node.load(json("{\"enabled\":false,\"count\":0,\"items\":[]}"));
        assertEquals(json("{\"enabled\":false,\"count\":0,\"items\":[]}"), node.collect());
        node.load(json("{}"));
        assertEquals(json("{}"), node.collect());
    }

    @Test
    void optionalToggleRetainsTheDraft() {
        OptionalNode node = (OptionalNode) EditorFormNode.create(
            schema("{\"type\":\"OPTIONAL\",\"element\":{\"type\":\"LONG\"}}"), "count", json("9007199254740993"));
        node.setPresent(false);
        assertTrue(node.isOptionalAndAbsent());
        assertTrue(node.collect().isJsonNull());
        node.setPresent(true);
        assertFalse(node.isOptionalAndAbsent());
        assertEquals("9007199254740993", node.collect().toString());
    }

    @Test
    void nestedDispatchKeepsTheRecordFieldName() {
        EditorFormNode node = EditorFormNode.root(schema("""
            {"type":"RECORD","fields":[{"name":"effect","schema":{
              "type":"UNION","variantKey":"kind","variants":[
                {"name":"demo:damage","type":"RECORD","fields":[{"name":"amount","schema":{"type":"INT"}}]}
              ]}}]}
            """));
        JsonElement expected = json("{\"effect\":{\"kind\":\"demo:damage\",\"amount\":7}}");
        node.load(expected);
        assertEquals(expected, node.collect());
    }

    @Test
    void selectingAnotherDispatchVariantKeepsExistingDrafts() {
        EditorFormNode node = EditorFormNode.root(schema("""
            {"type":"UNION","variantKey":"type","variants":[
              {"name":"demo:first","type":"RECORD","fields":[{"name":"value","schema":{"type":"STRING"}}]},
              {"name":"demo:second","type":"RECORD","fields":[{"name":"other","schema":{"type":"STRING"}}]}
            ]}
            """));
        node.load(json("{\"type\":\"demo:first\",\"value\":\"keep me\"}"));
        node.selectVariant(1);
        node.selectVariant(0);
        assertEquals("keep me", node.collect().getAsJsonObject().get("value").getAsString());
    }

    @Test
    void recordHeightIncludesItsHeading() {
        JsonObject schema = schema("{\"type\":\"RECORD\",\"fields\":[{\"name\":\"value\",\"schema\":{\"type\":\"STRING\"}}]}");
        assertEquals(EditorFormNode.ROW_HEIGHT, EditorFormNode.create(schema, "", null).height());
        assertEquals(EditorFormNode.ROW_HEIGHT * 2, EditorFormNode.create(schema, "nested", null).height());
    }

    private static JsonObject schema(String text) {
        return json(text).getAsJsonObject();
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }
}
