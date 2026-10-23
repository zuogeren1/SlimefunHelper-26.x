package me.matl114.hacks.utils;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.*;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;
import org.spongepowered.include.com.google.common.collect.BiMap;
import org.spongepowered.include.com.google.common.collect.HashBiMap;

public class EntityUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    public static void parseEntityWhiteList(String value, Set<EntityType<?>> collection) {
        collection.clear();
        try {
            for (net.minecraft.world.entity.EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                if (Pattern.matches(
                        value, BuiltInRegistries.ENTITY_TYPE.getKey(entityType).getPath())) {
                    collection.add(entityType);
                }
            }
            if (Pattern.matches(value, "animal")) {
                for (net.minecraft.world.entity.EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                    if (entityType.getCategory() == MobCategory.CREATURE) {
                        collection.add(entityType);
                    }
                }
            }
            if (Pattern.matches(value, "monster")) {
                for (net.minecraft.world.entity.EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                    if (entityType.getCategory() == MobCategory.MONSTER) {
                        if (!(entityType == EntityTypes.ZOMBIFIED_PIGLIN)
                                && !(entityType == net.minecraft.world.entity.EntityTypes.ENDERMAN)) {
                            collection.add(entityType);
                        }
                    }
                }
            }
            // feat: add spawn group flag
            for (MobCategory group : MobCategory.values()) {
                if (group != MobCategory.MONSTER && Pattern.matches(value, group.getName())) {
                    for (net.minecraft.world.entity.EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                        if (entityType.getCategory() == group) {
                            collection.add(entityType);
                        }
                    }
                }
            }
            if (Pattern.matches(value, "living_entity")) {
                for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
                    if (entityType.getCategory() != MobCategory.MISC) {
                        collection.add(entityType);
                    }
                }
            }
            var iter = collection.iterator();
            while (iter.hasNext()) {
                EntityType<?> entityType = iter.next();
                if (!Pattern.matches(
                                value,
                                BuiltInRegistries.ENTITY_TYPE.getKey(entityType).getPath())
                        && Pattern.matches(
                                value,
                                "!"
                                        + BuiltInRegistries.ENTITY_TYPE
                                                .getKey(entityType)
                                                .getPath())) {
                    iter.remove();
                }
            }
        } catch (Throwable valuePatternError) {
            collection.clear();
        }
        // Debug.info("Using whitelist",set);

    }

    // 26.2: 建表过程要 new ItemStack，必须在组件绑定之后，改为首次访问时构建
    private static BiMap<Item, EntityType<?>> ITEM2SPAWN_ENTITY = null;

    private static BiMap<Item, EntityType<?>> spawnEggMap() {
        BiMap<Item, EntityType<?>> map = ITEM2SPAWN_ENTITY;
        if (map == null) {
            map = HashBiMap.create();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item instanceof SpawnEggItem egg) {
                    map.put(item, egg.getType(new ItemStack(item)));
                }
            }
            ITEM2SPAWN_ENTITY = map;
        }
        return map;
    }

    public static EntityType<?> spawnEggToEntity(Item spawner) {
        return spawnEggMap().getOrDefault(spawner, null);
    }

    public static Item entityToSpawnEgg(EntityType<?> entityType) {
        return spawnEggMap().inverse().getOrDefault(entityType, null);
    }

    public static EntityType<?> getStoredEntityType(ItemStack stack) {
        if (stack != null
                && stack.getItem() instanceof BlockItem block
                && block.getBlock() instanceof SpawnerBlock spawner
                && ItemStackUtils.hasInPatch(stack, DataComponents.BLOCK_ENTITY_DATA)) {
            TypedEntityData<?> component = ItemStackUtils.getInPatch(stack, DataComponents.BLOCK_ENTITY_DATA);
            return getSpawnerEntityType(component.getUnsafe());
        }
        return null;
    }

    public static EntityType<?> getSpawnerEntityType(CompoundTag spawnerCompound) {
        return spawnerCompound == null
                ? null
                : BuiltInRegistries.ENTITY_TYPE
                        .getOptional(getSpawnedEntityId(spawnerCompound, "SpawnData"))
                        .orElse(null);
    }

    public static Identifier getSpawnedEntityId(CompoundTag nbt, String spawnDataKey) {
        if (nbt.contains(spawnDataKey)) {
            if (nbt.get(spawnDataKey) instanceof CompoundTag cp1) {
                if (cp1.get("entity") instanceof CompoundTag cp2) {
                    if (cp2.get("id") instanceof StringTag nbt3) {
                        String string = nbt3.value();
                        if (string != null && !string.isEmpty()) {
                            return Identifier.tryParse(string);
                        }
                    }
                }
            }

            return null;
        } else {

            return null;
        }
    }

    public static boolean isEntityValid(@Nullable Entity entity) {
        return entity != null
                && entity.isAlive()
                && !entity.isRemoved()
                && mc.level != null
                && mc.level == entity.level()
                && mc.level.getEntities().get(entity.getUUID()) == entity;
    }

    public static Vector2d getEntityLookXZ(Entity entity) {
        float yaw = entity.getYRot();
        float pitch = entity.getXRot();
        float f = Mth.cos(-yaw * 0.017453292F - 3.1415927F);
        float g = Mth.sin(-yaw * 0.017453292F - 3.1415927F);
        float h = -Mth.cos(-pitch * 0.017453292F);
        return new Vector2d(g * h, f * h);
    }

    //    public static void setEntityRotation(Entity entity, Vec3d vec) {
    //        vec = vec.normalize();
    //
    //        entity.setPitch((float) Math.toDegrees(Math.asin(-vec.y)));
    //        entity.setYaw((float) Math.toDegrees(Math.atan2(-vec.x, vec.z)));
    //    }
    //
    //    public static void setEntityYawSafe(Entity entity, Vec2f vec2f) {
    //        setEntityYawSafe(entity, (float) Math.toDegrees(Math.atan2(-vec2f.x, vec2f.y)));
    //    }
    //
    //    public static void setEntityRotationSafe(Entity entity, Vec3d vec) {
    //        vec = vec.normalize();
    //        setEntityPitchSafe(entity, (float) Math.toDegrees(Math.asin(-vec.y)));
    //
    //        float newYaw = (float) Math.toDegrees(Math.atan2(-vec.x, vec.z));
    //        setEntityYawSafe(entity, newYaw);
    //    }

    public static float getSafeYaw(float oldYaw, float newYaw) {
        //        if(newYaw == -180.0 || newYaw == 180.0)return newYaw;
        //        float oldYaw = entity.getYaw();
        float diff = getSafeYawDiff(oldYaw, newYaw);
        return oldYaw + diff;
    }

    public static float getSafeYawDiff(float oldYaw, float newYaw) {
        float diff = newYaw - oldYaw;
        float normalizedDiff = (diff % 360.0F + 720.0F + 180.0F) % 360.0F - 180.0F; // 归一化到 [-180,180]
        if (oldYaw > 1000 && normalizedDiff > 179) {
            normalizedDiff -= 360.0F;
        } else if (oldYaw < -1000 && normalizedDiff < -179) {
            normalizedDiff += 360.0F;
        }
        return normalizedDiff;
    }

    public static float getSafePitch(float newPitch) {
        // fix: 当玩家低头的时候不要改成抬头
        return normalizePitch(newPitch);
    }

    public static float normalizeYaw(float yaw) {
        if (yaw > -1E-5 && yaw < 360 + 1E-5) {
            return yaw;
        }
        return ((yaw % 360.0F + 720.0F + 180.0F) % 360.0F) - 180.0F;
    }

    public static float normalizePitch(float newPitch) {
        if (newPitch < 90.0F + 1E-5 && newPitch > -90.0F - 1E-5) return newPitch;
        return (newPitch % 180.0F + 720.0F + 90.0F) % 180.0F - 90.0F; // 归一化到 [-90, 90]
    }

    public static void setEntityYawSafe(Entity entity, float newYaw) {
        newYaw = getSafeYaw(entity.getYRot(), newYaw);
        entity.setYRot(newYaw);
    }

    public static void setEntityPitchSafe(Entity entity, float newPitch) {
        entity.setXRot(getSafePitch(newPitch));
    }

    public static Vec3 pitchYawToRotation(float pitch, float yaw) {
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;
        float h = Mth.cos(g);
        float i = Mth.sin(g);
        float j = Mth.cos(f);
        float k = Mth.sin(f);
        return new Vec3((double) (i * j), (double) (-k), (double) (h * j));
    }

    public static Vec2 rotationToPitchYaw(Vec3 vec) {
        return new Vec2(rotationToPitch(vec), rotationToYaw(vec));
    }

    public static Vec2 directionToPitchYaw(Direction direction) {
        switch (direction) {
            case DOWN:
                return new Vec2(89.9F, 0);
            case UP:
                return new Vec2(-89.9F, 0);
            case NORTH:
                return new Vec2(0, 180);
            case SOUTH:
                return new Vec2(0, 0);
            case WEST:
                return new Vec2(0, 90);
            case EAST:
                return new Vec2(0, -90);
            default:
                throw new IllegalArgumentException("Unknown direction: " + direction);
        }
    }

    public static float rotationToYaw(Vec3 vec) {
        vec = vec.normalize();
        return (float) Math.toDegrees(Math.atan2(-vec.x, vec.z));
    }

    public static float rotationToYaw(Direction direction) {
        switch (direction) {
            case SOUTH:
                return 0.0F;
            case WEST:
                return 90.0F;
            case NORTH:
                return 180.0F;
            case EAST:
                return -90.0F;
            default:
                // 对于UP/DOWN，返回0或任意值，但通常不会调用
                return 0.0F;
        }
    }

    public static float rotationToPitch(Vec3 vec) {
        vec = vec.normalize();
        return (float) Math.toDegrees(Math.asin(-vec.y));
    }

    public static Direction pitchYawToDirection(Vec2 pitchYaw) {
        float pitch = pitchYaw.x;
        float yaw = pitchYaw.y;

        double radPitch = Math.toRadians(pitch);
        double radYaw = Math.toRadians(yaw);

        double cosPitch = Math.cos(radPitch);
        double sinPitch = Math.sin(radPitch);
        double cosYaw = Math.cos(radYaw);
        double sinYaw = Math.sin(radYaw);

        double x = -cosPitch * sinYaw;
        double y = -sinPitch;
        double z = cosPitch * cosYaw;

        double x2 = x * x;
        double y2 = y * y;
        double z2 = z * z;

        if (x2 > y2 && x2 > z2) {
            return x > 0 ? Direction.EAST : Direction.WEST;
        } else if (y2 > x2 && y2 > z2) {
            return y > 0 ? Direction.UP : Direction.DOWN;
        } else {
            return z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    public static Direction yawToHorizontalDirection(float yaw) {
        // 将角度偏移 45°，使边界落在整数点上
        float shifted = yaw + 45;
        // 归一化到 [0, 360)
        float norm = shifted % 360;
        if (norm < 0) norm += 360;
        int quarter = (int) (norm / 90);
        switch (quarter) {
            case 0:
                return Direction.SOUTH; // 原偏移后 0-90 -> 原 -45~45 -> 南
            case 1:
                return Direction.WEST; // 90-180 -> 45~135 -> 西
            case 2:
                return Direction.NORTH; // 180-270 -> 135~225 -> 北
            default:
                return Direction.EAST; // 270-360 -> 225~315 -> 东
        }
    }

    public static int yawToXSgn(float yaw) {
        float norm = yaw % 360;
        if (norm < 0) norm += 360;
        final float THRESH = 1e-3f; // 度
        // 判断是否接近0或180
        if (norm < THRESH || Math.abs(norm - 180) < THRESH || Math.abs(norm - 360) < THRESH) {
            return 0;
        }
        // 否则在(0,180)为负，在(180,360)为正
        return (norm > 0 && norm < 180) ? -1 : 1;
    }

    public static int yawToZSgn(float yaw) {
        float norm = yaw % 360;
        if (norm < 0) norm += 360;
        final float THRESH = 1e-3f;
        // 判断是否接近90或270
        if (Math.abs(norm - 90) < THRESH || Math.abs(norm - 270) < THRESH) {
            return 0;
        }
        // 在(90,270)为负，其余为正
        return (norm > 90 && norm < 270) ? -1 : 1;
    }

    public static boolean isRotationDifferent(float lastPitch, float pitch, float lastYaw, float yaw) {
        return Math.abs(pitch - lastPitch) > 1e-2 || Math.abs(EntityUtils.getSafeYawDiff(lastYaw, yaw)) > 1e-2;
    }

    public static double getProjectileGravity(Item item) {
        if (item instanceof ProjectileWeaponItem) return 0.05;

        if (item instanceof ThrowablePotionItem) return 0.4;

        if (item instanceof FishingRodItem) return 0.15;

        if (item instanceof TridentItem) return 0.015;

        return 0.03;
    }

    public static ClipContext.Fluid getFluidHandling(Item item) {
        if (item instanceof FishingRodItem) return ClipContext.Fluid.ANY;

        return ClipContext.Fluid.NONE;
    }

    public static Vec3 rotateVec(Vec3 vec, float pitch, float yaw) {
        Vec3 facing = vec.normalize();
        double len = vec.length();
        Vec2 py = rotationToPitchYaw(facing);
        Vec3 rotated = pitchYawToRotation(py.x + pitch, py.y + yaw);
        return rotated.normalize().scale(len);
    }

    public static Vec3 lookCoordToAbsolutePos(Entity source, double x, double y, double z) {
        Vec2 vec2f = source.getRotationVector();
        Vec3 vec3d = source.position();

        float f = Mth.cos((vec2f.y + 90.0F) * 0.017453292F);

        float g = Mth.sin((vec2f.y + 90.0F) * 0.017453292F);

        float h = Mth.cos(-vec2f.x * 0.017453292F);

        float i = Mth.sin(-vec2f.x * 0.017453292F);
        float j = Mth.cos((-vec2f.x + 90.0F) * 0.017453292F);
        float k = Mth.sin((-vec2f.x + 90.0F) * 0.017453292F);
        Vec3 vec3d2 = new Vec3((double) (f * h), (double) i, (double) (g * h));
        Vec3 vec3d3 = new Vec3((double) (f * j), (double) k, (double) (g * j));
        Vec3 vec3d4 = vec3d2.cross(vec3d3).scale(-1.0);
        double d = vec3d2.x * z + vec3d3.x * y + vec3d4.x * x;
        double e = vec3d2.y * z + vec3d3.y * y + vec3d4.y * x;
        double l = vec3d2.z * z + vec3d3.z * y + vec3d4.z * x;
        return new Vec3(vec3d.x + d, vec3d.y + e, vec3d.z + l);
    }

    public static Vec3 lookCoordToPos(float pitch, float yaw, double x, double y, double z) {
        Vec2 vec2f = new Vec2(pitch, yaw);

        float f = Mth.cos((vec2f.y + 90.0F) * 0.017453292F);

        float g = Mth.sin((vec2f.y + 90.0F) * 0.017453292F);

        float h = Mth.cos(-vec2f.x * 0.017453292F);

        float i = Mth.sin(-vec2f.x * 0.017453292F);
        float j = Mth.cos((-vec2f.x + 90.0F) * 0.017453292F);
        float k = Mth.sin((-vec2f.x + 90.0F) * 0.017453292F);
        Vec3 vec3d2 = new Vec3((double) (f * h), (double) i, (double) (g * h));
        Vec3 vec3d3 = new Vec3((double) (f * j), (double) k, (double) (g * j));
        Vec3 vec3d4 = vec3d2.cross(vec3d3).scale(-1.0);
        double d = vec3d2.x * z + vec3d3.x * y + vec3d4.x * x;
        double e = vec3d2.y * z + vec3d3.y * y + vec3d4.y * x;
        double l = vec3d2.z * z + vec3d3.z * y + vec3d4.z * x;
        return new Vec3(d, e, l);
    }

    public static Player getPlayerByName(String name) {
        return Minecraft.getInstance().level.players().stream()
                .filter(m -> m.getScoreboardName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public static Stream<String> getWorldPlayerNames(boolean containSelf) {
        return mc.level.players().stream()
                .filter(i -> containSelf || i != mc.player)
                .map(Player::getScoreboardName);
    }

    public static Vec3 movementInputToVelocity(Vec3 movementInput, float speed, float yaw) {
        double d = movementInput.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        } else {
            Vec3 vec3d = (d > 1.0 ? movementInput.normalize() : movementInput).scale((double) speed);
            float f = Mth.sin(yaw * 0.017453292F);
            float g = Mth.cos(yaw * 0.017453292F);
            return new Vec3(
                    vec3d.x * (double) g - vec3d.z * (double) f, vec3d.y, vec3d.z * (double) g + vec3d.x * (double) f);
        }
    }

    public static Vec2 movementInputToRotation(Vec3 movementInput, float yaw) {
        double horizontal = Math.sqrt(movementInput.x * movementInput.x + movementInput.z * movementInput.z);

        if (horizontal < 1.0E-7) {
            if (Math.abs(movementInput.y) < 1.0E-7) {
                return new Vec2(0.0F, yaw);
            }

            return new Vec2(movementInput.y > 0.0 ? -90.0F : 90.0F, yaw);
        }

        float inputYaw = (float) Math.toDegrees(Math.atan2(-movementInput.x, movementInput.z));

        float pitch = (float) Math.toDegrees(-Math.atan2(movementInput.y, horizontal));

        return new Vec2(pitch, yaw + inputYaw);
    }

    public static void smoothPlayerInputState() {}

    public static Component getEntityDisplayable(Entity target) {
        return target instanceof Player player
                ? Component.literal(player.getScoreboardName())
                : target.getDisplayName();
    }

    public static double sqrtSpeed(Vec3 vec) {
        return Math.sqrt(vec.x * vec.x + vec.z * vec.z);
    }

    public static Vec3 withStrafe(Vec3 self, double speed, double strength, PlayerInputUtils.Input input, float yaw) {
        // 输入无效（无移动输入）时水平速度清零
        if (input != null && !input.hasWASDMovement()) {
            return new Vec3(0.0, self.y, 0.0);
        }

        // 保留部分原有水平速度
        double prevX = self.x * (1.0 - strength);
        double prevZ = self.z * (1.0 - strength);
        double useSpeed = speed * strength;

        // 根据 yaw 计算新方向的单位向量，并叠加原速度
        double angle = Math.toRadians(yaw);
        double x = -Math.sin(angle) * useSpeed + prevX;
        double z = Math.cos(angle) * useSpeed + prevZ;

        return new Vec3(x, self.y, z);
    }

    public static float getMovementDirectionOfInput(float facingYaw, PlayerInputUtils.Input input) {
        boolean forwards = input.forward() && !input.backward();
        boolean backwards = input.backward() && !input.forward();
        boolean left = input.left() && !input.right();
        boolean right = input.right() && !input.left();

        float actualYaw = facingYaw;
        float forward = 1.0f;

        if (backwards) {
            actualYaw += 180f;
            forward = -0.5f;
        } else if (forwards) {
            forward = 0.5f;
        }

        if (left) {
            actualYaw -= 90f * forward;
        }
        if (right) {
            actualYaw += 90f * forward;
        }

        return Mth.wrapDegrees(actualYaw);
    }

    public static final double SQRT_SPEED = Math.sqrt(0.0825);

    public static Vec3 withStrafe(Vec3 self, double speed) {

        return withStrafe(self, speed, 1.0D);
    }

    public static Vec2 applyMovementFactors(Entity entity, Vec2 vec2f) {
        if (vec2f.lengthSquared() == 0) {
            return vec2f;
        }
        if (entity instanceof LocalPlayer p) {
            vec2f = vec2f.scale(0.98F);
            if (p.isUsingItem() && !p.isPassenger()) {
                vec2f = vec2f.scale(p.getUseItem()
                        .getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT)
                        .speedMultiplier());
            }
            if (p.isMovingSlowly()) {
                float f = (float) p.getAttributeValue(Attributes.SNEAKING_SPEED);
                vec2f = vec2f.scale(f);
            }
            float f = vec2f.length();
            vec2f = vec2f.scale(1.0F / f);
            float g = getDirectionalMovementSpeedMultiplier(vec2f);
            float h = Math.min(f * g, 1.0F);
            return vec2f.scale(h);
        } else {
            return vec2f;
        }
    }

    private static float getDirectionalMovementSpeedMultiplier(Vec2 vec) {
        float f = Math.abs(vec.x);
        float g = Math.abs(vec.y);
        float h = g > f ? f / g : g / f;
        return Mth.sqrt(1.0F + Mth.square(h));
    }

    public static double getEffectiveGravity(LocalPlayer player) {
        boolean bl = PlayerStateManager.INSTANCE.lastKnownClientVelocity.y <= 0.0;
        return (bl && player.hasEffect(MobEffects.SLOW_FALLING))
                ? Math.min(player.getGravity(), 0.01)
                : player.getGravity();
    }

    public static Vec3 calculateGlidingVelocity(
            LocalPlayer player, Vec3 oldVelocity, Vec3 rotationVector, boolean hasGravity) {
        Vec3 look = rotationVector;
        float pitch = rotationToPitch(rotationVector);
        float pitchRad = pitch * 0.017453292F;
        double lookHorizLen = Math.sqrt(look.x * look.x + look.z * look.z);
        double initialHorizSpeed = oldVelocity.horizontalDistance();
        double gravity = hasGravity ? getEffectiveGravity(player) : 0;
        double cosPitchSq = Mth.square(Math.cos(pitchRad));

        // 1. 重力影响
        double newY = oldVelocity.y + gravity * (cosPitchSq * 0.75 - 1.0);
        Vec3 vel = new Vec3(oldVelocity.x, newY, oldVelocity.z);

        // 2. 下降时的抬升效应
        if (newY < 0.0 && lookHorizLen > 0.0) {
            double lift = newY * -0.1 * cosPitchSq;
            vel = vel.add(look.x * lift / lookHorizLen, lift, look.z * lift / lookHorizLen);
        }

        // 3. 俯冲加速（向下看时）
        if (pitchRad < 0.0F && lookHorizLen > 0.0) {
            double dive = initialHorizSpeed * (-Mth.sin(pitchRad)) * 0.04;
            vel = vel.add(-look.x * dive / lookHorizLen, dive * 3.2, -look.z * dive / lookHorizLen);
        }

        // 4. 水平速度向视线方向修正
        if (lookHorizLen > 0.0) {
            double targetScale = initialHorizSpeed / lookHorizLen;
            vel = vel.add((look.x * targetScale - vel.x) * 0.1, 0.0, (look.z * targetScale - vel.z) * 0.1);
        }

        // 5. 空气阻力（水平0.99，垂直0.98）
        return vel.multiply(0.99, 0.98, 0.99);
    }

    public static Vec3 simulateTravelInFluidVelocity(
            Vec3 velocity, boolean lastInWater, boolean lastInLava, boolean hasGravity) {
        if (lastInWater) {
            return simulateTravelInWaterVelocity(velocity, hasGravity);
        }
        if (lastInLava) {
            return simulateTravelInLavaVelocity(velocity, hasGravity);
        }
        return velocity;
    }

    private static Vec3 simulateTravelInWaterVelocity(Vec3 velocity, boolean hasGravity) {
        PlayerInputUtils.Input input = PlayerStateManager.INSTANCE.lastInput;

        Vec2 vec2f = EntityUtils.applyMovementFactors(mc.player, new Vec2(input.sidewaysSpeed(), input.forwardSpeed()));
        Vec3 movementInput = new Vec3(vec2f.x, 0, vec2f.y);
        boolean falling = velocity.y <= 0.0;
        double y = mc.player.getY();
        double gravity = EntityUtils.getEffectiveGravity(mc.player);
        float drag = mc.player.isSprinting() ? 0.9F : 0.8F;
        float acceleration = 0.02F;
        float efficiency = (float) mc.player.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (!mc.player.onGround()) {
            efficiency *= 0.5F;
        }
        if (efficiency > 0.0F) {
            drag += (0.54600006F - drag) * efficiency;
            acceleration += (mc.player.getSpeed() - acceleration) * efficiency;
        }
        if (mc.player.hasEffect(MobEffects.DOLPHINS_GRACE)) {
            drag = 0.96F;
        }
        Vec3 nextVelocity =
                velocity.add(EntityUtils.movementInputToVelocity(movementInput, acceleration, mc.player.getYRot()));
        if (mc.player.horizontalCollision && mc.player.onClimbable()) {
            nextVelocity = new Vec3(nextVelocity.x, 0.2, nextVelocity.z);
        }
        nextVelocity = nextVelocity.multiply((double) drag, 0.800000011920929, (double) drag);
        nextVelocity =
                simulateApplyFluidMovingSpeed(gravity, falling, nextVelocity, hasGravity, mc.player.isSprinting());
        return simulateResetVerticalVelocityInFluid(nextVelocity, y);
    }

    private static Vec3 simulateTravelInLavaVelocity(Vec3 velocity, boolean hasGravity) {
        PlayerInputUtils.Input input = PlayerStateManager.INSTANCE.lastInput;
        Vec3 movementInput = new Vec3(input.sidewaysSpeed(), input.upwardSpeed(), input.forwardSpeed());
        boolean falling = velocity.y <= 0.0;
        double y = mc.player.getY();
        double gravity = EntityUtils.getEffectiveGravity(mc.player);
        Vec3 nextVelocity =
                velocity.add(EntityUtils.movementInputToVelocity(movementInput, 0.02F, mc.player.getYRot()));
        if (mc.player.getFluidHeight(net.minecraft.tags.FluidTags.LAVA) <= mc.player.getFluidJumpThreshold()) {
            nextVelocity = nextVelocity.multiply(0.5, 0.800000011920929, 0.5);
            nextVelocity =
                    simulateApplyFluidMovingSpeed(gravity, falling, nextVelocity, hasGravity, mc.player.isSprinting());
        } else {
            nextVelocity = nextVelocity.scale(0.5);
        }
        if (gravity != 0.0) {
            nextVelocity = nextVelocity.add(0.0, -gravity / 4.0, 0.0);
        }
        return simulateResetVerticalVelocityInFluid(nextVelocity, y);
    }

    private static Vec3 simulateApplyFluidMovingSpeed(
            double gravity, boolean falling, Vec3 velocity, boolean hasGravity, boolean isSprinting) {
        if (gravity != 0.0 && hasGravity && !isSprinting) {
            double nextY;
            if (falling && Math.abs(velocity.y - 0.005) >= 0.003 && Math.abs(velocity.y - gravity / 16.0) < 0.003) {
                nextY = -0.003;
            } else {
                nextY = velocity.y - gravity / 16.0;
            }
            return new Vec3(velocity.x, nextY, velocity.z);
        }
        return velocity;
    }

    private static Vec3 simulateResetVerticalVelocityInFluid(Vec3 velocity, double y) {
        if (mc.player.horizontalCollision
                && mc.player.isFree(velocity.x, velocity.y + 0.6000000238418579 - mc.player.getY() + y, velocity.z)) {
            return new Vec3(velocity.x, 0.30000001192092896, velocity.z);
        }
        return velocity;
    }

    /**
     * 指定 speed 和 strength
     */
    public static Vec3 withStrafe(Vec3 self, double speed, double strength) {
        LocalPlayer player = Minecraft.getInstance().player;
        PlayerInputUtils.Input input = PlayerInputUtils.of(player);
        float yaw = getMovementDirectionOfInput(player.getYRot(), input);
        return withStrafe(self, speed, strength, input, yaw);
    }
}
