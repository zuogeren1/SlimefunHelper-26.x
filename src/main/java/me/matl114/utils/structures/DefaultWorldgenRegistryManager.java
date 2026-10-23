package me.matl114.utils.structures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Util;

/** Loads vanilla's default worldgen registries without using the connected world's registry packet. */
public final class DefaultWorldgenRegistryManager {
    private static final RegistryAccess.Frozen REGISTRIES = create();

    private DefaultWorldgenRegistryManager() {}

    public static RegistryAccess.Frozen registry() {
        return REGISTRIES;
    }

    private static RegistryAccess.Frozen create() {
        try (CloseableResourceManager resources = DefaultServerDataResources.create()) {
            RegistryAccess.Frozen staticRegistries =
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            List<Registry.PendingTags<?>> pendingTags =
                    TagLoader.loadTagsForExistingRegistries(resources, staticRegistries);
            List<HolderLookup.RegistryLookup<?>> registryLookups =
                    TagLoader.buildUpdatedLookups(staticRegistries, pendingTags);
            RegistryAccess.Frozen worldgen = RegistryDataLoader.load(
                            resources,
                            registryLookups,
                            RegistryDataLoader.WORLDGEN_REGISTRIES,
                            Util.backgroundExecutor())
                    .join();
            pendingTags.forEach(Registry.PendingTags::apply);
            Map<ResourceKey<? extends Registry<?>>, Registry<?>> merged = new LinkedHashMap<>();
            staticRegistries.registries().forEach(entry -> merged.put(entry.key(), entry.value()));
            worldgen.registries().forEach(entry -> merged.put(entry.key(), entry.value()));
            return new RegistryAccess.ImmutableRegistryAccess(merged).freeze();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load default vanilla worldgen registries", exception);
        }
    }
}
