package hungteen.ide.common.network;

import hungteen.ide.common.codec.editor.CodecEditorManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求某个注册表的 schema。
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 14:06
 **/
public class RequestSchemaPacket {

    private final String registryName;

    public RequestSchemaPacket(String registryName) {
        this.registryName = registryName;
    }

    public RequestSchemaPacket(FriendlyByteBuf buffer) {
        this.registryName = buffer.readUtf();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(registryName);
    }

    public static class Handler {
        public static void onMessage(RequestSchemaPacket message, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                CodecEditorManager.getSchemaJson(message.registryName).ifPresent(schema -> {
                    IDENetworkHandler.sendToClient(
                            ctx.get().getSender(),
                            new SchemaResponsePacket(message.registryName, schema.toString())
                    );
                });
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
