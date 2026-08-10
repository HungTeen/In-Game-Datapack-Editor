package hungteen.ide.common.network;

import hungteen.ide.client.gui.editor.CodecViewerScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：通知客户端打开 Codec 字段查看器，附全部可编辑注册表名与预选注册表。
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 16:42
 **/
public class OpenViewerPacket {

    private final List<String> registryNames;
    private final String preselect;

    public OpenViewerPacket(List<String> registryNames, String preselect) {
        this.registryNames = registryNames;
        this.preselect = preselect;
    }

    public OpenViewerPacket(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        this.registryNames = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            registryNames.add(buffer.readUtf());
        }
        this.preselect = buffer.readUtf();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(registryNames.size());
        for (String name : registryNames) {
            buffer.writeUtf(name);
        }
        buffer.writeUtf(preselect);
    }

    public static class Handler {
        public static void onMessage(OpenViewerPacket message, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> CodecViewerScreen.open(message.registryNames, message.preselect));
            ctx.get().setPacketHandled(true);
        }
    }
}
