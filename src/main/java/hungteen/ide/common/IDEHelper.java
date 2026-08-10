package hungteen.ide.common;

import hungteen.htlib.util.helper.IModIDHelper;
import hungteen.ide.InGameDatapackEditor;
import net.minecraft.resources.ResourceLocation;

/**
 * @author PangTeen
 * @program In-Game-Datapack-Editor
 * @create 2026/8/10 18:37
 **/
public class IDEHelper {

    private static final IModIDHelper HELPER = () -> InGameDatapackEditor.MOD_ID;

    public static IModIDHelper get() {
        return HELPER;
    }

    public static ResourceLocation prefix(String path) {
        return get().prefix(path);
    }
}
