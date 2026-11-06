package me.matl114.utils.structures;

import java.util.List;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

/** Creates a resource manager containing only the built-in vanilla server-data pack. */
final class DefaultServerDataResources {
    private DefaultServerDataResources() {}

    static CloseableResourceManager create() {
        //#if MC >= 260300
        //$$ PackResources vanillaPack = ServerPacksSource.createVanillaPackSource().fullResources();
        //#else
        PackResources vanillaPack = ServerPacksSource.createVanillaPackSource();
        //#endif
        return new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanillaPack));
    }
}
