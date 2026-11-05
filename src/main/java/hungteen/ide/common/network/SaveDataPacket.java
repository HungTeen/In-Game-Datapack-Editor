package hungteen.ide.common.network;

import hungteen.ide.common.codec.editor.CodecEditorManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：保存注册表资源；文件路径只能由服务端推导。
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 14:07
 **/
public class SaveDataPacket {

    public static final int MAX_ID_LENGTH = 256;
    public static final int MAX_JSON_LENGTH = 32767;
    /** Leaves room for IDs and framing in the 1.20.1 serverbound custom payload's 32767-byte limit. */
    public static final int MAX_JSON_BYTES = 30000;

    private final String registryName;
    private final String resourceId;
    private final String json;

    public SaveDataPacket(String registryName, String resourceId, String json) {
        this.registryName = registryName;
        this.resourceId = resourceId;
        this.json = json;
    }

    public SaveDataPacket(FriendlyByteBuf buffer) {
        this.registryName = buffer.readUtf(MAX_ID_LENGTH);
        this.resourceId = buffer.readUtf(MAX_ID_LENGTH);
        this.json = buffer.readUtf(MAX_JSON_LENGTH);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(registryName, MAX_ID_LENGTH);
        buffer.writeUtf(resourceId, MAX_ID_LENGTH);
        buffer.writeUtf(json, MAX_JSON_LENGTH);
    }

    public static class Handler {
        public static void onMessage(SaveDataPacket message, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                var sender = ctx.get().getSender();
                if (sender == null) return;
                CodecEditorManager.SaveResult result;
                if (!sender.hasPermissions(2)) {
                    result = new CodecEditorManager.SaveResult(false, Component.translatable("ide.save.denied"));
                } else if (message.json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                    result = CodecEditorManager.SaveResult.failure("JSON exceeds the 30000-byte UTF-8 limit");
                } else {
                    result = CodecEditorManager.saveJson(sender.server, message.registryName, message.resourceId, message.json);
                }
                IDENetworkHandler.sendToClient(sender, new SaveResultPacket(message.registryName,
                    message.resourceId, result.success(), result.message()));
                sender.sendSystemMessage(result.message());
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
