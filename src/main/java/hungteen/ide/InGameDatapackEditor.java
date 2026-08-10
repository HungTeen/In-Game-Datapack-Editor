package hungteen.ide;

import com.mojang.logging.LogUtils;
import hungteen.ide.common.command.IDECommand;
import hungteen.ide.common.network.IDENetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * @author PangTeen
 * @program In-Game-Datapack-Editor
 * @create 2026/8/10 18:24
 **/
@Mod(InGameDatapackEditor.MOD_ID)
public class InGameDatapackEditor {

    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Mod ID.
    public static final String MOD_ID = "ide";

    public InGameDatapackEditor() {
        /* Mod Bus Events */
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(InGameDatapackEditor::setUp);

        /* Forge Bus Events */
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener((RegisterCommandsEvent event) -> IDECommand.register(event.getDispatcher(), event.getBuildContext()));
    }

    public static void setUp(FMLCommonSetupEvent event) {
        IDENetworkHandler.init();
    }
}
