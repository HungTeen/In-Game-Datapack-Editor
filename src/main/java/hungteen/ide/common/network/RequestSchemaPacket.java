package hungteen.ide.common.network;

import hungteen.ide.common.codec.editor.CodecEditorManager;
import hungteen.ide.common.codec.parser.SchemaRegistryAccess;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
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
        this.registryName = buffer.readUtf(SaveDataPacket.MAX_ID_LENGTH);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(registryName, SaveDataPacket.MAX_ID_LENGTH);
    }

    public static class Handler {
        public static void onMessage(RequestSchemaPacket message, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender == null) return;
                if (!sender.hasPermissions(2)) {
                    IDENetworkHandler.sendToClient(sender,
                        SchemaResponsePacket.failure(message.registryName, "Permission level 2 is required"));
                    return;
                }
                var previousAccess = SchemaRegistryAccess.get();
                try {
                    SchemaRegistryAccess.set(sender.server.registryAccess());
                    var schema = CodecEditorManager.getSchemaJson(message.registryName);
                    if (schema.isEmpty()) {
                        IDENetworkHandler.sendToClient(sender,
                            SchemaResponsePacket.failure(message.registryName, "Unknown datapack registry"));
                        return;
                    }
                    String json = schema.get().toString();
                    if (json.length() > SchemaResponsePacket.MAX_SCHEMA_LENGTH) {
                        IDENetworkHandler.sendToClient(sender,
                            SchemaResponsePacket.failure(message.registryName, "Schema exceeds the network size limit"));
                        return;
                    }
                    IDENetworkHandler.sendToClient(sender, new SchemaResponsePacket(message.registryName, json));
                } catch (RuntimeException ex) {
                    LogUtils.getLogger().warn("Could not generate schema for {}", message.registryName, ex);
                    IDENetworkHandler.sendToClient(sender, SchemaResponsePacket.failure(message.registryName, ex.getMessage()));
                } finally {
                    SchemaRegistryAccess.set(previousAccess);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
