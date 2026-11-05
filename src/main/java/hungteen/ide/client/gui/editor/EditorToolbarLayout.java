package hungteen.ide.client.gui.editor;

/** Keeps the save action on-screen at Minecraft's minimum 320-pixel GUI width. */
record EditorToolbarLayout(int typeX, int typeWidth, int modeX, int modeWidth,
                           int resourceX, int resourceWidth, int saveX, int saveWidth) {
    static EditorToolbarLayout forWidth(int screenWidth) {
        int margin = 8;
        int gap = 8;
        int modeWidth = 70;
        int saveWidth = 40;
        int resourceMinWidth = 80;
        // Shorten the searchable type field first; give any extra space to the resource ID.
        int typeWidth = Math.min(150,
            screenWidth - margin * 2 - gap * 3 - modeWidth - saveWidth - resourceMinWidth);
        int modeX = margin + typeWidth + gap;
        int resourceX = modeX + modeWidth + gap;
        int saveX = screenWidth - margin - saveWidth;
        return new EditorToolbarLayout(margin, typeWidth, modeX, modeWidth,
            resourceX, saveX - gap - resourceX, saveX, saveWidth);
    }
}
