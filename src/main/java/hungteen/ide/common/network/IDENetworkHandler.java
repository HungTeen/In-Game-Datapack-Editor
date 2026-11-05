package hungteen.ide.common.network;

import hungteen.ide.common.IDEHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * @program: HTLib
 * @author: HungTeen
 * @create: 2026-08-10 22:26
 **/
public class IDENetworkHandler {

    private static final String PROTOCOL_VERSION = "2";
    private static int id = 0;

    private static SimpleChannel CHANNEL;

    public static void init() {

        CHANNEL =
            NetworkRegistry.ChannelBuilder.named(IDEHelper.prefix("networking")).networkProtocolVersion(() -> PROTOCOL_VERSION)
                .clientAcceptedVersions(PROTOCOL_VERSION::equals).serverAcceptedVersions(PROTOCOL_VERSION::equals).simpleChannel();

        CHANNEL.registerMessage(getId(), OpenCodecPacket.class, OpenCodecPacket::encode, OpenCodecPacket::new,
            OpenCodecPacket.Handler::onMessage, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(getId(), RequestSchemaPacket.class, RequestSchemaPacket::encode,
            RequestSchemaPacket::new, RequestSchemaPacket.Handler::onMessage, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(getId(), SchemaResponsePacket.class, SchemaResponsePacket::encode,
            SchemaResponsePacket::new, SchemaResponsePacket.Handler::onMessage, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(getId(), SaveDataPacket.class, SaveDataPacket::encode, SaveDataPacket::new,
            SaveDataPacket.Handler::onMessage, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(getId(), SaveResultPacket.class, SaveResultPacket::encode, SaveResultPacket::new,
            SaveResultPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static <MSG> void sendToServer(MSG msg) {
        CHANNEL.sendToServer(msg);
    }

    public static <MSG> void sendToClient(MSG msg) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), msg);
    }

    public static <MSG> void sendToClient(ServerPlayer serverPlayer, MSG msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), msg);
    }

    public static <MSG> void sendToNearByClient(Level world, Vec3 vec, double dis, MSG msg) {
        CHANNEL.send(PacketDistributor.NEAR.with(() -> {
            return new PacketDistributor.TargetPoint(vec.x, vec.y, vec.z, dis, world.dimension());
        }), msg);
    }

    private static int getId() {
        return id++;
    }
}
