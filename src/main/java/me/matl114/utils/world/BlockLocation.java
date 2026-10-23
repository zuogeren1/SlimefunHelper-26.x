package me.matl114.utils.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import me.matl114.utils.MathUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public record BlockLocation(ResourceKey<Level> world, int x, int y, int z) {
    public static BlockLocation of(Entity entity) {
        return new BlockLocation(
                entity.level().dimension(), entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
    }

    public static BlockLocation of(Level world, BlockPos pos) {
        return new BlockLocation(world.dimension(), pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockLocation of(ResourceKey<Level> world, BlockPos pos) {
        return new BlockLocation(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos getPos() {
        return new BlockPos(x, y, z);
    }

    public boolean isInRange(BlockLocation location, double distance) {
        if (Objects.equals(location.world, world)) {
            return MathUtils.s2((double) (x - location.x))
                            + MathUtils.s2((double) (z - location.z))
                            + MathUtils.s2((double) (y - location.y))
                    <= MathUtils.s2(distance);
        } else {
            return false;
        }
    }

    public boolean isInRangeHorizontal(BlockLocation location, double distance) {
        if (Objects.equals(location.world, world)) {
            return MathUtils.s2((double) (x - location.x)) + MathUtils.s2((double) (z - location.z))
                    <= MathUtils.s2(distance);
        } else {
            return false;
        }
    }

    public boolean isLocationLoaded(Level world) {
        if (Objects.equals(world.dimension(), world())) {
            return world.getChunkSource().hasChunk(x >> 4, z >> 4);
        } else {
            return false;
        }
    }

    public static MapCodec<BlockLocation> DELEGATE_MAP_CODEC = RecordCodecBuilder.mapCodec(o -> o.group(
                    ResourceKey.codec(Registries.DIMENSION).fieldOf("world").forGetter(BlockLocation::world),
                    BlockPos.CODEC.fieldOf("pos").forGetter(BlockLocation::getPos))
            .apply(o, BlockLocation::of));

    public static MapCodec<BlockLocation> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    ResourceKey.codec(Registries.DIMENSION).fieldOf("world").forGetter(BlockLocation::world),
                    Codec.INT.fieldOf("x").forGetter(BlockLocation::x),
                    Codec.INT.fieldOf("y").forGetter(BlockLocation::y),
                    Codec.INT.fieldOf("z").forGetter(BlockLocation::z))
            .apply(instance, BlockLocation::new));

    public static Codec<BlockLocation> CODEC = Codec.withAlternative(MAP_CODEC.codec(), DELEGATE_MAP_CODEC.codec());
}
