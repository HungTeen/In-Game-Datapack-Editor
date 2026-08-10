package hungteen.ide.common.codec.parser;

import com.mojang.serialization.Codec;
import hungteen.htlib.common.registry.HTRegistryManager;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 数据包加载的所有 Codec 的收集器。
 *
 * <p>来源分两类：</p>
 * <ul>
 *   <li>HTLib 自定义数据包注册表（{@code HTCodecRegistry}，经
 *       {@code HTRegistryManager#create} 注册、由 {@code DataPackRegistryEvent} 加载）；</li>
 *   <li>原版数据包注册表：世界生成类（{@link RegistryDataLoader#WORLDGEN_REGISTRIES}）
 *       与维度类（{@link RegistryDataLoader#DIMENSION_REGISTRIES}）。</li>
 * </ul>
 *
 * <p>供命令等场景按注册名查 codec，进而用 {@link CodecSchemaParser} 输出其数据格式。</p>
 *
 * @author PangTeen
 * @program: HTLib
 * @create 2026/8/10 12:02
 **/
public final class DatapackCodecs {

    private DatapackCodecs() {
    }

    /**
     * 收集全部数据包 codec，返回（注册名 → codec）有序列表。
     *
     * <p>重复注册名按"原版优先"合并：先放原版，再放 HTLib；同名的后者会覆盖前者。</p>
     */
    public static List<Entry> getDatapackCodecs() {

        List<Entry> entries = new ArrayList<>();

        RegistryDataLoader.WORLDGEN_REGISTRIES.forEach(data ->
            entries.add(new Entry(data.key().location(), data.elementCodec()))
        );

        RegistryDataLoader.DIMENSION_REGISTRIES.forEach(data ->
            entries.add(new Entry(data.key().location(), data.elementCodec()))
        );

        HTRegistryManager.getRegistries().forEach(registry ->
            entries.add(new Entry(registry.getRegistryName(), registry.getCodec()))
        );

        return entries;
    }

    /**
     * 按注册名查 codec；不存在返回 empty。
     */
    public static Optional<? extends Codec<?>> getCodec(ResourceLocation name) {
        return getDatapackCodecs().stream()
            .filter(entry -> entry.name().equals(name))
            .map(Entry::codec)
            .findFirst();
    }

    /** 一个（注册名 → codec）条目。 */
    public record Entry(ResourceLocation name, Codec<?> codec) {
    }
}
