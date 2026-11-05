package hungteen.ide.client.gui.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditorToolbarLayoutTest {
    @Test
    void allControlsRemainUsableAndSeparatedAcrossSupportedGuiWidths() {
        for (int width = 320; width <= 1920; width++) {
            EditorToolbarLayout layout = EditorToolbarLayout.forWidth(width);
            assertTrue(layout.typeX() >= 0);
            assertTrue(layout.typeWidth() >= 90, "The registry search must remain editable at width " + width);
            assertTrue(layout.resourceWidth() >= 80, "The resource ID must remain editable at width " + width);
            assertTrue(layout.modeWidth() >= 70);
            assertTrue(layout.saveWidth() >= 40);
            assertTrue(layout.typeX() + layout.typeWidth() < layout.modeX());
            assertTrue(layout.modeX() + layout.modeWidth() < layout.resourceX());
            assertTrue(layout.resourceX() + layout.resourceWidth() < layout.saveX());
            assertTrue(layout.saveX() + layout.saveWidth() < width, "Save must be reachable at width " + width);
        }
    }

    @Test
    void ordinaryWindowProvidesMoreRoomForLongResourceIds() {
        EditorToolbarLayout narrow = EditorToolbarLayout.forWidth(320);
        EditorToolbarLayout ordinary = EditorToolbarLayout.forWidth(427);
        assertTrue(ordinary.typeWidth() > narrow.typeWidth());
        assertTrue(ordinary.resourceWidth() > narrow.resourceWidth());
    }
}
