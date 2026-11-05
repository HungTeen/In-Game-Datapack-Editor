package hungteen.ide.common.codec.editor;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import hungteen.ide.common.codec.parser.CodecSchemaParser;
import hungteen.ide.common.codec.parser.DataSchema;
import hungteen.ide.common.codec.parser.SchemaPrinter;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Server-side registry discovery, schema generation and validated datapack saving. */
public final class CodecEditorManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CodecEditorManager() {
    }

    public static List<ResourceLocation> getRegistryNames() {
        return DatapackCodecs.getDatapackCodecs().stream()
            .map(RegistryDataLoader.RegistryData::key)
            .map(ResourceKey::location)
            .toList();
    }

    public static Optional<JsonObject> getSchemaJson(String registryName) {
        ResourceLocation name = ResourceLocation.tryParse(registryName);
        if (name == null) {
            return Optional.empty();
        }
        return DatapackCodecs.getCodec(name).map(codec -> {
            DataSchema schema = CodecSchemaParser.parse(codec);
            return SchemaPrinter.toJson(schema);
        });
    }

    /** Validate before writing; client resource IDs never become arbitrary server paths. */
    public static SaveResult saveJson(MinecraftServer server, String registryName, String resourceId, String json) {
        try {
            ResourceLocation registry = ResourceLocation.tryParse(registryName);
            ResourceLocation resource = ResourceLocation.tryParse(resourceId);
            if (registry == null || resource == null) {
                return SaveResult.failure("Invalid registry or resource identifier");
            }
            var codec = DatapackCodecs.getCodec(registry);
            if (codec.isEmpty()) {
                return SaveResult.failure("Unknown datapack registry: " + registryName);
            }
            CodecJsonValidator.parseAndValidate(codec.get(), RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), json);
            Path datapacksDirectory = server.getWorldPath(LevelResource.DATAPACK_DIR);
            Path file = DatapackFileStorage.save(datapacksDirectory,
                registry.getNamespace(), registry.getPath(), resource.getNamespace(), resource.getPath(), json);
            String displayPath = "datapacks/" + datapacksDirectory.toRealPath().relativize(file).toString().replace('\\', '/');
            return new SaveResult(true, Component.translatable("ide.save.success", displayPath));
        } catch (IllegalArgumentException ex) {
            return SaveResult.failure(ex.getMessage());
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("Could not save datapack resource {} in registry {}", resourceId, registryName, ex);
            return SaveResult.failure(ex.getMessage());
        }
    }

    public record SaveResult(boolean success, Component message) {
        public static SaveResult failure(String detail) {
            String message = detail == null ? "Unknown error" : detail;
            if (message.length() > 1024) {
                message = message.substring(0, 1024) + "…";
            }
            return new SaveResult(false, Component.translatable("ide.save.failed", message));
        }
    }
}
