package hungteen.ide.common.network;

import hungteen.ide.client.gui.editor.CodecEditorScreen;
import hungteen.ide.client.gui.editor.CodecViewerScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：打开 Codec 界面（查看器或编辑器），附全部可编辑注册表名与预选注册表。
 * 查看器与编辑器共用这一个包，避免重复序列化逻辑。
 *
 * @author PangTeen
 * @create 2026/8/29
 **/
public class OpenCodecPacket {

    /** 打开哪个界面。 */
    public enum Mode {
        VIEWER, EDITOR
    }

    private final List<ResourceLocation> registryNames;
    private final String preselect;
    private final Mode mode;

    public OpenCodecPacket(List<ResourceLocation> registryNames, String preselect, Mode mode) {
        this.registryNames = registryNames;
        this.preselect = preselect;
        this.mode = mode;
    }

    public OpenCodecPacket(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        this.registryNames = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            registryNames.add(buffer.readResourceLocation());
        }
        this.preselect = buffer.readUtf();
        this.mode = buffer.readBoolean() ? Mode.EDITOR : Mode.VIEWER;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(registryNames.size());
        for (ResourceLocation name : registryNames) {
            buffer.writeResourceLocation(name);
        }
        buffer.writeUtf(preselect);
        buffer.writeBoolean(mode == Mode.EDITOR);
    }

    public static class Handler {
        public static void onMessage(OpenCodecPacket message, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (message.mode == Mode.EDITOR) {
                    CodecEditorScreen.open(message.registryNames);
                } else {
                    CodecViewerScreen.open(message.registryNames, message.preselect);
                }
            }));
            ctx.get().setPacketHandled(true);
        }
    }
}
