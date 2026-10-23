package me.matl114.utils;

import com.mojang.datafixers.util.Either;
import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import me.matl114.versioned.api.VRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.*;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.TrackedWaypoint;

public class WorldUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    public static final int UPDATE_BLOCK_NO_PHYSICS = 2 | 16 | 512;

    public static boolean areWorldEquals(ClientLevel world1, ClientLevel world2) {
        return world1 == world2
                || (world1 != null
                        && world2 != null
                        && Objects.equals(
                                world1.dimension().identifier(),
                                world2.dimension().identifier()));
    }

    public static Stream<String> getPlayerListNames() {
        return mc.getConnection().getOnlinePlayers().stream()
                .map(PlayerInfo::getProfile)
                .map(VRecord::getName);
    }

    public static Stream<String> getWaypointNames() {

        return getWaypointInternal()
                .map(TrackedWaypoint::id)
                .flatMap(s -> s.map(
                        uid -> {
                            PlayerInfo entry = mc.getConnection().getPlayerInfo(uid);
                            if (entry != null) {
                                return Stream.of(uid.toString(), VRecord.getName(entry.getProfile()));
                            } else {
                                return Stream.of(uid.toString());
                            }
                        },
                        name -> {
                            PlayerInfo entry = mc.getConnection().getPlayerInfo(name);
                            if (entry != null) {
                                return Stream.of(name, VRecord.getName(entry.getProfile()));
                            } else {
                                return Stream.of(name);
                            }
                        }));
    }

    private static Stream<TrackedWaypoint> getWaypointInternal() {
        List<TrackedWaypoint> waypoints = new ArrayList<>();
        mc.getConnection().getWaypointManager().forEachWaypoint(mc.player, waypoints::add);
        return waypoints.stream();
    }

    public static Stream<Waypoint> getWaypoints() {
        return getWaypointInternal().map(WorldUtils::translate);
    }

    private static Waypoint translate(TrackedWaypoint s) {
        ByteBuf buf = NetworkUtils.createBytebuf();
        s.write(buf);
        FriendlyByteBuf byteBuf = new FriendlyByteBuf(buf);
        Either<UUID, String> either = byteBuf.readEither(UUIDUtil.STREAM_CODEC, FriendlyByteBuf::readUtf);
        net.minecraft.world.waypoints.Waypoint.Icon config = (net.minecraft.world.waypoints.Waypoint.Icon)
                net.minecraft.world.waypoints.Waypoint.Icon.STREAM_CODEC.decode(byteBuf);
        var configNbt = (CompoundTag) net.minecraft.world.waypoints.Waypoint.Icon.CODEC
                .encodeStart(NbtOps.INSTANCE, config)
                .getOrThrow();
        int varInt = byteBuf.readVarInt();

        WaypointData data =
                switch (varInt) {
                    case 1 ->
                        new WaypointData.Pos(
                                new Vec3(byteBuf.readVarInt(), byteBuf.readVarInt(), byteBuf.readVarInt()));
                    case 2 -> new WaypointData.Chunk(new ChunkPos(byteBuf.readVarInt(), byteBuf.readVarInt()));
                    case 3 -> new WaypointData.Direction(byteBuf.readFloat());
                    default -> WaypointData.EMPTY;
                };
        buf.release();
        return new Waypoint(either, configNbt, data);
    }

    public static Map<BlockPos, BlockState> scannChunk(ChunkAccess chunk, BiPredicate<BlockPos, BlockState> predicate) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int minY = chunk.getMinY();
        int minZ = chunkPos.getMinBlockZ();
        int maxX = chunkPos.getMaxBlockX();
        int section = chunk.getHighestFilledSectionIndex();
        int maxY = section == -1
                ? chunk.getMinY()
                : SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(section + 1));
        int maxZ = chunkPos.getMaxBlockZ();
        Map<BlockPos, BlockState> stateMap = new LinkedHashMap<>();

        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!predicate.test(pos, state)) continue;
                    stateMap.put(pos, state);
                }

        return stateMap;
    }

    public static Waypoint getWaypoint(String lookup) {
        String optionalUid;
        PlayerInfo entry = mc.getConnection().getPlayerInfo(lookup);
        if (entry != null) {
            optionalUid = VRecord.getId(entry.getProfile()).toString();
        } else {
            optionalUid = null;
        }
        return getWaypoints()
                .filter(s -> lookup.equalsIgnoreCase(s.getDisplayName())
                        || (optionalUid != null && optionalUid.equalsIgnoreCase(s.getDisplayName())))
                .findFirst()
                .orElse(null);
    }

    public static float getPlayerBlockBreakingSpeedWithCanMineMultiply(
            Player player, BlockState state, ItemStack stack) {
        float f = stack.getDestroySpeed(state);
        if (f > 1.0F) {
            AttributeMap attributeContainer =
                    AttributeUtils.getAttributeWith(player, Map.of(EquipmentSlot.MAINHAND, stack));
            f += attributeContainer.getValue(Attributes.MINING_EFFICIENCY);
        }

        if (MobEffectUtil.hasDigSpeed(player)) {
            f *= 1.0F + (float) (MobEffectUtil.getDigSpeedAmplification(player) + 1) * 0.2F;
        }

        if (player.hasEffect(MobEffects.MINING_FATIGUE)) {
            float var10000;
            switch (player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
                case 0 -> var10000 = 0.3F;
                case 1 -> var10000 = 0.09F;
                case 2 -> var10000 = 0.0027F;
                default -> var10000 = 8.1E-4F;
            }

            float g = var10000;
            f *= g;
        }

        f *= (float) player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
        if (player.isEyeInFluid(FluidTags.WATER)) {
            f *= (float) player.getAttribute(Attributes.SUBMERGED_MINING_SPEED).getValue();
        }

        if (!player.onGround()) {
            f /= 5.0F;
        }
        int i = canToolHarvest(state, stack) ? 30 : 100;
        return f / i;
    }

    public static float calcBlockBreakingDelta(BlockState state, BlockGetter world, BlockPos pos) {
        return calcBlockBreakingDelta(state, world, pos, mc.player, mc.player.getMainHandItem());
    }

    public static float calcBlockBreakingDelta(
            BlockState state, BlockGetter world, BlockPos pos, Player player, ItemStack tool) {

        var playerBreakSpeed = getPlayerBlockBreakingSpeedWithCanMineMultiply(player, state, tool);
        return calcBlockBreakingDelta(state, world, pos, playerBreakSpeed);
    }

    public static float calcBlockBreakingDelta(
            BlockState state, BlockGetter world, BlockPos pos, float playerBreakSpeed) {
        float f = state.getDestroySpeed(world, pos);
        if (f == -1.0F) {
            return 0.0F;
        } else {
            return playerBreakSpeed / f;
        }
    }

    private static boolean canToolHarvest(BlockState state, ItemStack stack) {
        return !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
    }

    public static boolean isServerChunkLoaded(BlockPos pos) {
        return isServerChunkLoaded(
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public static boolean isServerPosLoaded(int blockPosX, int blockPosZ) {

        return isServerChunkLoaded(
                SectionPos.blockToSectionCoord(blockPosX), SectionPos.blockToSectionCoord(blockPosZ));
    }

    public static boolean isServerChunkLoaded(int chunkX, int chunkZ) {
        return isChunkLoaded(chunkX, chunkZ);
    }

    public static boolean isChunkLoaded(BlockPos pos) {
        return isChunkLoaded(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public static boolean isChunkLoaded(int chunkX, int chunkZ) {
        return mc.level.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    public static boolean isInfiniteWater(Level world, BlockPos pos) {
        int stillSourceCount = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            FluidState neighborFluid = world.getFluidState(pos.relative(direction));
            if (neighborFluid.is(Fluids.WATER) && neighborFluid.isSource()) {
                stillSourceCount++;
            }
        }
        if (stillSourceCount < 2) {
            return false;
        }
        BlockPos downPos = pos.below();
        BlockState downState = world.getBlockState(downPos);
        FluidState downFluid = downState.getFluidState();
        return downState.isSolid() || (downFluid.is(Fluids.WATER) && downFluid.isSource());
    }

    public static boolean canEntitySpawnAt(Level world, BlockPos pos, EntityType<?> type) {
        BlockState state = world.getBlockState(pos);
        BlockState upState = world.getBlockState(pos.above());
        BlockState downState = world.getBlockState(pos.below());
        Vec3 spawnerCenter = Vec3.atBottomCenterOf(pos);
        return downState.isValidSpawn(world, pos.below(), type)
                && world.noCollision(type.getSpawnAABB(spawnerCenter.x, spawnerCenter.y, spawnerCenter.z))
                && NaturalSpawner.isValidEmptySpawnBlock(world, pos, state, state.getFluidState(), type)
                && NaturalSpawner.isValidEmptySpawnBlock(world, pos.above(), upState, upState.getFluidState(), type);
    }

    @Getter
    @AllArgsConstructor
    public static class Waypoint {
        Either<UUID, String> source;
        CompoundTag config;
        WaypointData data;

        public String getDisplayName() {
            return getSource().map(UUID::toString, Function.identity());
        }
    }

    public static sealed interface WaypointData
            permits WaypointData.Pos, WaypointData.Chunk, WaypointData.Direction, WaypointData.Empty {
        public String getTypeName();

        public record Pos(Vec3 pos) implements WaypointData {

            @Override
            public String getTypeName() {
                return "Pos";
            }
        }

        public record Chunk(ChunkPos pos) implements WaypointData {
            @Override
            public String getTypeName() {
                return "Chunk";
            }
        }

        public record Direction(float azimuth) implements WaypointData {

            @Override
            public String getTypeName() {
                return "Direction";
            }
        }

        public record Empty() implements WaypointData {
            @Override
            public String getTypeName() {
                return "Empty";
            }
        }

        public static WaypointData EMPTY = new Empty();
    }
}
