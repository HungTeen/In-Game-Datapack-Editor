package hungteen.ide.common.network;

import hungteen.ide.client.gui.editor.CodecEditorScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Acknowledges validation and disk writing, not a live registry reload. */
public record SaveResultPacket(String registryName, String resourceId, boolean success, Component message) {

    public SaveResultPacket(FriendlyByteBuf buffer) {
        this(buffer.readUtf(SaveDataPacket.MAX_ID_LENGTH), buffer.readUtf(SaveDataPacket.MAX_ID_LENGTH),
            buffer.readBoolean(), buffer.readComponent());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(registryName, SaveDataPacket.MAX_ID_LENGTH);
        buffer.writeUtf(resourceId, SaveDataPacket.MAX_ID_LENGTH);
        buffer.writeBoolean(success);
        buffer.writeComponent(message);
    }

    public static void handle(SaveResultPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> CodecEditorScreen.onSaveResult(packet.registryName, packet.resourceId, packet.success, packet.message)));
        context.get().setPacketHandled(true);
    }
}
