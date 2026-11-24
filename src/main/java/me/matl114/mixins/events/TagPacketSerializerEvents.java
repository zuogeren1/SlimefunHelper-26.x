package me.matl114.mixins.events;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import java.util.Map;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 在服务端下发的标签包被解析成 {@link TagLoader.LoadResult} 的那一刻广播事件。
 *
 * <p>标签解析发生在网络线程、且早于任何界面或世界事件，
 * 依赖标签内容的模块（如坏包修复）只能挂在这里才能拿到原始标签表。
 */
@Environment(EnvType.CLIENT)
@Mixin(TagNetworkSerialization.class)
public abstract class TagPacketSerializerEvents {
    @WrapOperation(
            method = "deserializeTagsFromNetwork",
            at =
                    @At(
                            value = "NEW",
                            target =
                                    "(Lnet/minecraft/resources/ResourceKey;Ljava/util/Map;)Lnet/minecraft/tags/TagLoader$LoadResult;"))
    private static <T> TagLoader.LoadResult<T> onRegistryTagLoad(
            ResourceKey<T> key,
            Map<TagKey<T>, List<Holder<T>>> tags,
            Operation<TagLoader.LoadResult<T>> original) {
        Map<TagKey<T>, List<Holder<T>>> tagMap = tags;
        Event<Map<TagKey<T>, List<Holder<T>>>> event = new Event<>(tagMap, false, true, key);
        Listener.getRegistryTagKeyReload().handleValue((Event) event);
        return original.call(key, event.context);
    }
}
