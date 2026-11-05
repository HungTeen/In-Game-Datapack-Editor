package hungteen.ide.common.network;

import hungteen.ide.client.gui.editor.CodecScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：返回某注册表的 schema JSON。
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 14:06
 **/
public class SchemaResponsePacket {

    public static final int MAX_SCHEMA_LENGTH = 262144;
    private final String registryName;
    private final String schemaJson;
    private final Component error;

    public SchemaResponsePacket(String registryName, String schemaJson) {
        this(registryName, schemaJson, null);
    }

    private SchemaResponsePacket(String registryName, String schemaJson, Component error) {
        this.registryName = registryName;
        this.schemaJson = schemaJson;
        this.error = error;
    }

    public static SchemaResponsePacket failure(String registryName, String reason) {
        String detail = reason == null ? "Unknown error" : reason;
        if (detail.length() > 1024) detail = detail.substring(0, 1024) + "…";
        return new SchemaResponsePacket(registryName, "", Component.translatable("ide.schema.failed", detail));
    }

    public SchemaResponsePacket(FriendlyByteBuf buffer) {
        this.registryName = buffer.readUtf(SaveDataPacket.MAX_ID_LENGTH);
        this.schemaJson = buffer.readUtf(MAX_SCHEMA_LENGTH);
        this.error = buffer.readBoolean() ? buffer.readComponent() : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(registryName, SaveDataPacket.MAX_ID_LENGTH);
        buffer.writeUtf(schemaJson, MAX_SCHEMA_LENGTH);
        buffer.writeBoolean(error != null);
        if (error != null) buffer.writeComponent(error);
    }

    public static class Handler {
        public static void onMessage(SchemaResponsePacket message, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (message.error == null) {
                    CodecScreen.onSchema(message.registryName, message.schemaJson);
                } else {
                    CodecScreen.onSchemaError(message.registryName, message.error);
                }
            }));
            ctx.get().setPacketHandled(true);
        }
    }
}
