package hungteen.ide.common.network;

import hungteen.ide.client.gui.editor.CodecEditorScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：通知客户端打开 Codec 编辑器界面，附全部可编辑注册表名。
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 14:05
 **/
public class OpenEditorPacket {

    private final List<String> registryNames;

    public OpenEditorPacket(List<String> registryNames) {
        this.registryNames = registryNames;
    }

    public OpenEditorPacket(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        this.registryNames = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            registryNames.add(buffer.readUtf());
        }
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(registryNames.size());
        for (String name : registryNames) {
            buffer.writeUtf(name);
        }
    }

    public static class Handler {
        public static void onMessage(OpenEditorPacket message, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> CodecEditorScreen.open(message.registryNames));
            ctx.get().setPacketHandled(true);
        }
    }
}
