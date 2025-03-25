package hungteen.editor;

import com.google.gson.JsonElement;
import com.mojang.datafixers.kinds.App;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * @author PangTeen
 * @program In-Game-Datapack-Editor
 * @create 2025/3/26 0:07
 **/
public class CodecManager {

    private static void test(){
//        Function<RecordCodecBuilder.Instance<MobEffectEntityConsumer>, ? extends App<RecordCodecBuilder.Mu<MobEffectEntityConsumer>, MobEffectEntityConsumer>> builder = instance -> instance.group(
//                EffectHelper.get().getHolderCodec().fieldOf("effect").forGetter(MobEffectEntityConsumer::effect),
//                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("duration").forGetter(MobEffectEntityConsumer::duration),
//                Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("level", 0).forGetter(MobEffectEntityConsumer::level),
//                Codec.BOOL.optionalFieldOf("ambient", false).forGetter(MobEffectEntityConsumer::ambient),
//                Codec.BOOL.optionalFieldOf("display", true).forGetter(MobEffectEntityConsumer::display)
//        ).apply(instance, MobEffectEntityConsumer::new);
//        MapCodec<MobEffectEntityConsumer> codec = MobEffectEntityConsumer.CODEC;
//
//        App<RecordCodecBuilder.Mu<MobEffectEntityConsumer>, MobEffectEntityConsumer> builderBox = builder.apply(RecordCodecBuilder.instance());
//        RecordCodecBuilder<MobEffectEntityConsumer, MobEffectEntityConsumer> codecBuilder = RecordCodecBuilder.unbox(builderBox);
//
//        findField(codec, codecBuilder);
    }

    public static <T> void findField(MapCodec<T> codec, RecordCodecBuilder<T, T> codecBuilder){
        List<JsonElement> keys = codec.keys(JsonOps.INSTANCE).toList();
        List<String> list = new ArrayList<>();
        dfs(codecBuilder, list);
        list.forEach(System.out::println);
    }

    public static void dfs(RecordCodecBuilder<?, ?> codecBuilder, List<String> list){
        // 获取 codecBuilder 的 ‘encoder’ 字段
        System.out.println("Enter new RecordCodecBuilder");
        try {
            Field encoderField = RecordCodecBuilder.class.getDeclaredField("encoder");
            encoderField.setAccessible(true);
            Object encoder = encoderField.get(codecBuilder);

            Field decoderField = RecordCodecBuilder.class.getDeclaredField("decoder");
            decoderField.setAccessible(true);
            Object decoder = decoderField.get(codecBuilder);
            if(decoder instanceof MapCodec<?> mapCodec){
                StringBuilder sb = new StringBuilder();
                mapCodec.keys(JsonOps.INSTANCE).forEach(jsonElement -> sb.append(jsonElement.toString()).append(", "));
                if(decoder.toString().startsWith("OptionalFieldCodec")){
                    Field decoderField2 = decoder.getClass().getDeclaredField("val$decoder");
                    decoderField2.setAccessible(true);
                    Object decoder2 = decoderField2.get(mapCodec);

                    Field functionField = decoder2.getClass().getDeclaredField("val$function");
                    functionField.setAccessible(true);
                    Object function = functionField.get(decoder2);
                    if(function instanceof Function<?,?> func){
                        Object defaultValue = ((Function) function).apply(Optional.empty());
                        list.add(sb + ": " + defaultValue.toString());
                    }
                } else {
                    list.add(sb.toString());
                }
            }

            // 遍历匿名内部类的字段。
            if(true) {
                int argId = 1;
                while (true) {
                    try {
                        Field argField = encoder.getClass().getDeclaredField("arg$" + argId);
                        argField.setAccessible(true);
                        Object arg = argField.get(encoder);
                        System.out.println("arg$" + argId + " Class: " + arg.getClass().getSimpleName());
                        if(arg instanceof RecordCodecBuilder) {
                            dfs((RecordCodecBuilder<?, ?>) arg, list);
                        } else if(arg instanceof MapCodec<?> mapCodec){
                            Stream<JsonElement> keys = mapCodec.keys(JsonOps.INSTANCE);
                            keys.forEach(System.out::println);
                        }
                        argId++;
                    } catch (NoSuchFieldException e) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
