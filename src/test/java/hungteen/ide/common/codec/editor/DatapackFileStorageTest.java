package hungteen.ide.common.codec.editor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DatapackFileStorageTest {

    @TempDir
    Path temporary;

    @Test
    void writesVanillaResourceInItsOwnNamespaceAndCreatesMetadata() throws IOException {
        Path root = temporary.resolve("datapacks");
        Path file = DatapackFileStorage.save(root, "minecraft", "worldgen/biome", "demo", "forest/hills", "{\"name\":\"森林\"}");
        assertEquals(root.resolve("demo/data/demo/worldgen/biome/forest/hills.json"), file);
        assertEquals("{\"name\":\"森林\"}", Files.readString(file));
        var metadata = JsonParser.parseString(Files.readString(root.resolve("demo/pack.mcmeta"))).getAsJsonObject();
        assertEquals(15, metadata.getAsJsonObject("pack").get("pack_format").getAsInt());
    }

    @Test
    void prefixesCustomRegistryNamespaceAndPreservesExistingMetadata() throws IOException {
        Path root = temporary.resolve("datapacks");
        Path pack = Files.createDirectories(root.resolve("demo"));
        String metadata = "{\"pack\":{\"pack_format\":15,\"description\":\"My existing pack\"}}";
        Files.writeString(pack.resolve("pack.mcmeta"), metadata);
        Path file = DatapackFileStorage.save(root, "opentd", "effects", "demo", "damage", "{\"amount\":1}");
        assertEquals(pack.resolve("data/demo/opentd/effects/damage.json"), file);
        DatapackFileStorage.save(root, "opentd", "effects", "demo", "damage", "{\"amount\":2}");
        assertEquals("{\"amount\":2}", Files.readString(file));
        assertEquals(metadata, Files.readString(pack.resolve("pack.mcmeta")));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count(), "Successful writes must not leave temporary files");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "a/../../outside", "/absolute", "a//b", "a/./b", "a/", "..", ".", "C:/outside", "a\\b", ""})
    void rejectsUnsafeResourcePathsBeforeWriting(String resourcePath) {
        Path root = temporary.resolve("datapacks");
        assertThrows(IllegalArgumentException.class,
            () -> DatapackFileStorage.save(root, "minecraft", "worldgen/biome", "demo", resourcePath, "{}"));
        assertFalse(Files.exists(root));
    }

    @Test
    void alsoRejectsTraversalInRegistryAndNamespace() {
        assertThrows(IllegalArgumentException.class,
            () -> DatapackFileStorage.relativePath("minecraft", "../effects", "demo", "safe"));
        assertThrows(IllegalArgumentException.class,
            () -> DatapackFileStorage.relativePath("minecraft", "effects", "..", "safe"));
    }

    @Test
    void rejectsLinkedPackWithoutTouchingItsDestination() throws IOException {
        Path root = Files.createDirectories(temporary.resolve("datapacks"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        createSymbolicLinkOrSkip(root.resolve("demo"), outside);
        assertThrows(IOException.class,
            () -> DatapackFileStorage.save(root, "minecraft", "worldgen/biome", "demo", "forest", "{}"));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsLinkedTargetWithoutChangingOriginalFile() throws IOException {
        Path root = temporary.resolve("datapacks");
        Path parent = Files.createDirectories(root.resolve("demo/data/demo/worldgen/biome"));
        Path outside = Files.writeString(temporary.resolve("outside.json"), "original");
        createSymbolicLinkOrSkip(parent.resolve("forest.json"), outside);
        assertThrows(IOException.class,
            () -> DatapackFileStorage.save(root, "minecraft", "worldgen/biome", "demo", "forest", "{}"));
        assertEquals("original", Files.readString(outside));
    }

    @Test
    void rejectsLinkedMetadataWithoutChangingIt() throws IOException {
        Path root = temporary.resolve("datapacks");
        Path pack = Files.createDirectories(root.resolve("demo"));
        Path outside = Files.writeString(temporary.resolve("metadata.json"), "original");
        createSymbolicLinkOrSkip(pack.resolve("pack.mcmeta"), outside);
        assertThrows(IOException.class,
            () -> DatapackFileStorage.save(root, "minecraft", "worldgen/biome", "demo", "forest", "{}"));
        assertEquals("original", Files.readString(outside));
        assertFalse(Files.exists(pack.resolve("data/demo/worldgen/biome/forest.json")));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "Symbolic links are unavailable: " + ex.getMessage());
        }
    }
}
