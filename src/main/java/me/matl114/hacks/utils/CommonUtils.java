package me.matl114.hacks.utils;

import java.io.File;
import java.util.List;
import me.matl114.SlimefunHelper;
import me.matl114.utils.world.ChunkIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import me.matl114.utils.ApiMethod;

@ApiMethod
public class CommonUtils {
    public static int parseIntOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable e) {
            return defaultValue;
        }
    }

    public static Identifier getNamespaceKey(String id) {
        return new Identifier(SlimefunHelper.MOD_ID, id);
    }

    private static final Minecraft mc = Minecraft.getInstance();

    public static String getServerName() {
        if (mc.isLocalServer()) {
            if (mc.level == null) return "";

            File folder = (mc.getSingleplayerServer())
                    .storageSource
                    .getDimensionPath(mc.level.dimension())
                    .toFile();
            if (folder.toPath().relativize(mc.gameDirectory.toPath()).getNameCount() != 2) {
                folder = folder.getParentFile();
            }
            return folder.getName();
        }
        if (mc.getCurrentServer() != null) {
            return (mc.getCurrentServer().isRealm() ? "realms" : mc.getCurrentServer().ip);
        }
        return "";
    }

    public static String getWorldName() {
        // Singleplayer
        if (mc.isLocalServer()) {
            if (mc.level == null) return "";

            File folder = (mc.getSingleplayerServer())
                    .storageSource
                    .getDimensionPath(mc.level.dimension())
                    .toFile();
            if (folder.toPath().relativize(mc.gameDirectory.toPath()).getNameCount() != 2) {
                folder = folder.getParentFile();
            }
            return folder.getName() + "|" + mc.level.dimension().identifier();
        }

        // Multiplayer
        if (mc.getCurrentServer() != null) {
            return (mc.getCurrentServer().isRealm() ? "realms" : mc.getCurrentServer().ip)
                    + (mc.level == null ? "" : "|" + mc.level.dimension().identifier());
        }

        return mc.level == null ? "" : mc.level.dimension().identifier().toString();
    }

    public static ResourceKey<LevelStem> getCurrentDimensionOption() {
        if (mc.level == null) return LevelStem.OVERWORLD;
        switch (mc.level.dimension().identifier().getPath()) {
            case "the_nether" -> {
                return LevelStem.NETHER;
            }
            case "the_end" -> {
                return LevelStem.END;
            }
            case "overworld" -> {
                return LevelStem.OVERWORLD;
            }
            default -> {
                // need fix
                DimensionType type = mc.level.dimensionType();
                if (type.cardinalLightType() == net.minecraft.world.level.CardinalLighting.Type.NETHER
                        || type.hasCeiling()) {
                    return LevelStem.NETHER;
                }
                if (type.hasSkyLight()) {
                    return LevelStem.OVERWORLD;
                }
                if (type.skybox() == DimensionType.Skybox.END) return LevelStem.END;
                return LevelStem.OVERWORLD;
            }
        }
    }

    public static Iterable<ChunkAccess> chunks(boolean onlyWithLoadedNeighbours) {
        return () -> new ChunkIterator(onlyWithLoadedNeighbours);
    }

    public static List<String> filterString(List<String> str, String str2) {
        return str.stream().filter(s -> s.contains(str2)).toList();
    }

    public static ChunkPos toChunk(BlockPos blockPos) {
        return new ChunkPos(blockPos.getX() >> 4, blockPos.getZ() >> 4);
    }
}
