package me.matl114.utils.structures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Vanilla server-data resources and a private temporary cache for offline structure templates. */
final class DefaultStructureTemplateResources {
    private static final Resources RESOURCES = create();

    private DefaultStructureTemplateResources() {}

    static StructureTemplateManager templates() {
        return RESOURCES.templates();
    }

    static StructurePieceSerializationContext context(RegistryAccess registries) {
        return new StructurePieceSerializationContext(RESOURCES.resourceManager(), registries, RESOURCES.templates());
    }

    private static Resources create() {
        CloseableResourceManager resourceManager = DefaultServerDataResources.create();
        LevelStorageSource.LevelStorageAccess session = null;
        try {
            Path cacheDirectory = Files.createTempDirectory("slimefunhelper-structure-templates-");
            cacheDirectory.toFile().deleteOnExit();
            session = LevelStorageSource.createDefault(cacheDirectory).createAccess("template-cache");
            StructureTemplateManager templates = new StructureTemplateManager(
                    resourceManager,
                    session,
                    DataFixers.getDataFixer(),
                    DefaultWorldgenRegistryManager.registry().lookupOrThrow(Registries.BLOCK));
            LevelStorageSource.LevelStorageAccess openSession = session;
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                try {
                                    openSession.close();
                                } catch (IOException ignored) {
                                }
                                resourceManager.close();
                            },
                            "slimefunhelper-template-cache-close"));
            return new Resources(resourceManager, templates);
        } catch (Throwable throwable) {
            if (session != null) {
                try {
                    session.close();
                } catch (IOException ignored) {
                }
            }
            resourceManager.close();
            throw new IllegalStateException("Unable to initialize vanilla structure templates", throwable);
        }
    }

    private record Resources(ResourceManager resourceManager, StructureTemplateManager templates) {}
}
