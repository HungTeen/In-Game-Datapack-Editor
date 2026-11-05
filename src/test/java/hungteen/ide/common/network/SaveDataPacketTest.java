package hungteen.ide.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SaveDataPacketTest {

    @Test
    void unicodeJsonAndMaximumIdentifiersFitServerboundPayloadBudget() {
        String id = "a".repeat(SaveDataPacket.MAX_ID_LENGTH);
        String json = "\"" + "中".repeat((SaveDataPacket.MAX_JSON_BYTES - 2) / 3) + "\"";
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= SaveDataPacket.MAX_JSON_BYTES);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(3); // SimpleChannel's packet discriminator is part of the payload.
            new SaveDataPacket(id, id, json).encode(buffer);
            assertTrue(buffer.readableBytes() <= 32767, "1.20.1 rejects larger serverbound custom payloads");
            assertEquals(3, buffer.readVarInt());
            assertEquals(id, buffer.readUtf(SaveDataPacket.MAX_ID_LENGTH));
            assertEquals(id, buffer.readUtf(SaveDataPacket.MAX_ID_LENGTH));
            assertEquals(json, buffer.readUtf(SaveDataPacket.MAX_JSON_LENGTH));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
