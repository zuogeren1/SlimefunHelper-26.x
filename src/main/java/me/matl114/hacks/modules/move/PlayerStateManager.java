package me.matl114.hacks.modules.move;

import com.google.common.collect.Streams;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.accessors.interfaces.MetadataHolder;
import me.matl114.events.CombatListener;
import me.matl114.events.impl.MetadataUpdate;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.CombatPlayer;
import me.matl114.events.impl.SlotClickAction;
import me.matl114.hacks.ACTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Tasks;
import me.matl114.utils.*;
import me.matl114.utils.commands.params.api.CommandExecution;
import me.matl114.utils.containers.MetaData;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.utils.inventory.ItemStackSample;
import me.matl114.versioned.api.VDataFlag;
import me.matl114.versioned.api.VPacket;
import me.matl114.versioned.api.VRecord;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.ConsumableListener;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.RemoveStatusEffectsConsumeEffect;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3d;
import me.matl114.hacks.utils.EntityUtils;

public class PlayerStateManager extends BaseModule {
    public static PlayerStateManager INSTANCE;
    double startFallingY;
    public double fallDistance;
    public double lastX;
    public double lastZ;
    public double lastY;
    public float lastPitch;
    public float lastYaw;
    public boolean lastOnGround;
    public boolean lastPlayerOnGround;
    public boolean lastSprint;
    public Vec3 lastKnownMovementSpeed = Vec3.ZERO;
    public Vec3 lastKnownChangePosMovementSpeed = Vec3.ZERO;
    public Vec3 lastKnownRealMovementSpeed = Vec3.ZERO;
    public Vec3 lastKnownClientVelocity = Vec3.ZERO;
    public Vec3 lastAverageMovementSpeed = Vec3.ZERO;
    public Vec3 lastSetBackPosition = Vec3.ZERO;
    public int lastAttackStrengthResetTick = 0;
    public int lastStartGlidingTick = 0;
    public boolean lastMovementContainsPosition = false;
    boolean lastTickHasMovement = false;
    public boolean lastClimbing;
    public boolean lastInLava;
    public boolean lastInWater;
    public boolean lastWaterPush;
    public boolean realInWater;
    public boolean realInLava;
    public boolean lastLavaPush;
    public boolean lastInWeb;
    private boolean inWeb;
    public boolean lastInWall;
    public boolean lastUnderBlock;
    public boolean lastHasGroundSupport;
    public int lastSelectedSlot = 0;
    public PlayerInputUtils.Input lastInput = PlayerInputUtils.EMPTY.clone();
    public boolean serverSideCanFly;
    public Deque<Vec3> last40Positions = new ArrayDeque<>();
    public BlockPos lastVelocityAffectingPos = BlockPos.ZERO;
    public Map<ItemStackSample, Integer> inventorySummary;
    public Map<ItemStackSample, Integer> inventoryTotalSummary;
    public int glidingTicks;
    public int lastActiveTicks;
    private static final int MAX_SIZE = 20;

    {
        for (int i = 0; i < MAX_SIZE; ++i) {
            last40Positions.add(Vec3.ZERO);
        }
    }

    private void updateAFK() {
        lastActiveTicks = Tasks.getTick();
    }

    public PlayerStateManager() {
        super("PlayerStateManager");
        INSTANCE = this;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundMovePlayerPacket.class),
                this::onMove,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundSetEntityMotionPacket.class),
                this::onPostPlayerVelocityUpdate,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundExplodePacket.class),
                this::onPostPlayerExplosion,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerInputPacket.class),
                this::onPlayerInput,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundPlayerPositionPacket.class),
                this::onPostPlayerPositionLook,
                Integer.MAX_VALUE);
        registerListener(Listener.getPlayerWebSlowPoint(), this::handleInWeb);
        registerListener(Listener.getPlayerFluidVelocityPoint(), this::handleInFluid);
        registerListener(Listener.getPreGameTick(), this::onPreGameTick);
        registerListener(
                Listener.getClientPlayerSendMovementPoint(), this::onPrePlayerSendMovePacket, Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundDamageEventPacket.class),
                this::onEntityAttackEvent,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerCommandPacket.class),
                this::onPlayerCommand,
                Integer.MAX_VALUE);
        registerListener(Listener.getPlayerInitConfiguration(), this::onPlayerInitialize);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundClientTickEndPacket.class),
                this::onTickEnd,
                Integer.MAX_VALUE);
        registerListener(Listener.getPreGameTick(), this::updateOtherPlayers);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundEntityEventPacket.class), this::onTotemPop);
        registerListener(Listener.getServerLeavePoint(), this::onLeave);
        registerListener(
                Listener.getEntityRemoveListener().getChannel(EntityTypes.PLAYER), this::onOtherPlayerRemoveDeath);
        registerListener(Listener.getPostClickSlot(), this::onClickSlot);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundContainerSetContentPacket.class),
                this::onInventoryUpdate);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundContainerSetSlotPacket.class),
                this::onInventorySlotUpdate);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundContainerClosePacket.class), this::onInventoryClose);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundRespawnPacket.class), this::onRespawn);
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.PLAYER), this::onEntityTrackedDataUpdate);
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundEntityEventPacket.class), this::onEntityConsume);
        registerListener(
                Listener.getEntityRemoveListener().getChannel(EntityTypes.SPLASH_POTION), this::onSplashedPotionHit);
        registerListener(
                Listener.getEntityRemoveListener().getChannel(EntityTypes.LINGERING_POTION), this::onLingerPotionHit);
        registerListener(
                Listener.getEntityPreTickListener().getChannel(EntityTypes.AREA_EFFECT_CLOUD),
                this::onAreaEffectCloudTick);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundUpdateMobEffectPacket.class),
                this::onEntityEffect);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundSetEquipmentPacket.class),
                this::onEntityEquipmentUpdate);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundAddEntityPacket.class),
                this::onPlayerEnterVisualRange);
        registerListener(Listener.getOtherPlayerExitPoint(), this::onPlayerLeave);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundSwingPacket.class), this::onSwingHand);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class), this::onAttack);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundDamageEventPacket.class), this::onPlayerDamage);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundSetCarriedItemPacket.class),
                this::onSelectedSlot,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class), this::onInteractBlock);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class), this::onInteract);
    }

    public void onMove(Event<ServerboundMovePlayerPacket> event) {
        if (event.isCancelled()) return;
        ServerboundMovePlayerPacket packet = event.context;
        if (PlayerMoveC2SPacketAccess.of(packet).getCause() != PlayerMoveC2SPacketAccess.Cause.TRIGGER_SIMULATION) {
            lastMovementContainsPosition = packet.hasPosition();

            // will not be intercepted by antiCheat
            Vec3 oldMove = new Vec3(lastX, lastY, lastZ);

            if (!packet.hasPosition()) {
                if (packet.isOnGround()) {
                    handleOnGroundFlag();
                }
            } else {
                Vec3 vec3d = new Vec3(packet.getX(lastX), packet.getY(lastY), packet.getZ(lastZ));
                if (!containsInvalidValues(vec3d.x, vec3d.y, vec3d.z)) {
                    handleMove(vec3d, packet.isOnGround());
                }
            }
            lastOnGround = packet.isOnGround();
            if (PlayerMoveC2SPacketAccess.of(packet).getCause() != PlayerMoveC2SPacketAccess.Cause.SET_BACK) {
                lastPlayerOnGround = lastOnGround;
            }
            if (packet.hasRotation()) {
                lastPitch = packet.getXRot(lastPitch);
                lastYaw = packet.getYRot(lastYaw);
                updateAFK();
            }
            lastKnownMovementSpeed = new Vec3(lastX - oldMove.x, lastY - oldMove.y, lastZ - oldMove.z);
            if (lastKnownMovementSpeed.lengthSqr() > 1E-7) {
                lastKnownChangePosMovementSpeed = lastKnownMovementSpeed;
                updateAFK();
            }
            if (PlayerMoveC2SPacketAccess.of(packet).getCause() != PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP) {
                if (PlayerMoveC2SPacketAccess.of(packet).getCause() == PlayerMoveC2SPacketAccess.Cause.SET_BACK) {
                    lastKnownRealMovementSpeed = Vec3.ZERO;
                } else {
                    lastKnownRealMovementSpeed = lastKnownMovementSpeed;
                    lastKnownClientVelocity = lastKnownRealMovementSpeed;
                }
            }
            lastTickHasMovement = true;
        } else {
            if (packet.hasRotation()) {
                lastPitch = packet.getXRot(lastPitch);
                lastYaw = packet.getYRot(lastYaw);
                updateAFK();
            }
        }
        // update input here , low version
        if (!ViaFabricPlusHooks.isSupportEndTick()) {
            lastInput = PlayerInputUtils.of(mc.player);
            if (lastInput.hasMovementControl()) {
                updateAFK();
            }
        }
    }

    public void onPostPlayerPositionLook(Event<ClientboundPlayerPositionPacket> eventPositionLook) {
        if (checkNull()) return;
        if (mc.player.isPassenger()) {
            return;
        }
        // only when vanilla teleport
        // grim teleport will send a EntityVelocityUpdateS2C to sync the clientVelocity
        if (eventPositionLook.context.id() >= 0) {
            if (!ViaFabricPlusHooks.isSupportEndTick()) {
                var relativesSet = eventPositionLook.context.relatives();
                double lastClientVX = relativesSet.contains(Relative.X) ? lastKnownClientVelocity.x : 0;
                double lastClientVY = relativesSet.contains(Relative.Y) ? lastKnownClientVelocity.y : 0;
                double lastClientVZ = relativesSet.contains(Relative.Z) ? lastKnownClientVelocity.z : 0;
                lastKnownClientVelocity = new Vec3(lastClientVX, lastClientVY, lastClientVZ);
            } else {
                var relativesSet = eventPositionLook.context.relatives();
                PositionMoveRotation position = eventPositionLook.context.change();
                Vec3 deltaMovement = position.deltaMovement();
                double lastClientVX = relativesSet.contains(Relative.DELTA_X)
                        ? lastKnownClientVelocity.x + deltaMovement.x
                        : deltaMovement.x;
                double lastClientVY = relativesSet.contains(Relative.DELTA_Y)
                        ? lastKnownClientVelocity.y + deltaMovement.y
                        : deltaMovement.y;
                double lastClientVZ = relativesSet.contains(Relative.DELTA_Z)
                        ? lastKnownClientVelocity.z + deltaMovement.z
                        : deltaMovement.z;
                lastKnownClientVelocity = new Vec3(lastClientVX, lastClientVY, lastClientVZ);
            }
        } else {
            isGrimResyncPacket = true;
        }
    }

    boolean isGrimResyncPacket = false;
    boolean lastHit = false;

    public void onPlayerDamage(Event<ClientboundDamageEventPacket> damage) {
        if (checkNull()) return;
        if (mc.player != null
                && damage.context.entityId() == mc.player.getId()
                && damage.context.sourceType().is(DamageTypeTags.NO_KNOCKBACK)) {
            lastHit = true;
        }
    }

    public void onPostPlayerVelocityUpdate(Event<ClientboundSetEntityMotionPacket> eventVC) {
        if (checkNull()) return;
        if (eventVC.context.id() != mc.player.getId()) return;
        // filter fireDamage or something
        if (lastHit) {
            lastHit = false;
        }
        if (isGrimResyncPacket) {
            isGrimResyncPacket = false;
        } else {
            return;
        }
        Vec3 velocity = VPacket.getVelocity(eventVC.context);
        ACTasks.addPostTransactionAction(s -> lastKnownClientVelocity = velocity);
    }

    public void onPostPlayerExplosion(Event<ClientboundExplodePacket> eventBoom) {
        if (checkNull()) return;
        var exp = eventBoom.context;
        if (exp.playerKnockback().isPresent()) {
            Vec3 knockBack = exp.playerKnockback().get();
            ACTasks.addPostTransactionAction(s -> lastKnownClientVelocity = lastKnownClientVelocity.add(knockBack));
        }
    }

    public void onPlayerInput(Event<ServerboundPlayerInputPacket> eventInput) {
        if (eventInput.isCancelled()) return;
        if (ViaFabricPlusHooks.isSupportEndTick()) {
            lastInput = PlayerInputUtils.of(eventInput.context);
            if (lastInput.hasMovementControl()) {
                updateAFK();
            }
        }
    }

    public void onPrePlayerSendMovePacket(Event<LocalPlayer> eventPre) {
        // force resync
        if (isRotationDifferent()) {
            ClientPlayerAccess.of(mc.player).setLastRot(lastPitch, lastYaw);
        }
    }

    public void onPlayerInitialize(Event<LocalPlayer> event) {
        onPlayerReset();
    }

    private static boolean containsInvalidValues(double x, double y, double z) {
        return Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z);
    }

    public boolean checkRegionFluid(TagKey<Fluid> tag) {
        if (mc.player.touchingUnloadedChunk()) {
            return false;
        } else {
            AABB box = mc.player.getBoundingBox().deflate(0.001);
            int i = Mth.floor(box.minX);
            int j = Mth.ceil(box.maxX);
            int k = Mth.floor(box.minY);
            int l = Mth.ceil(box.maxY);
            int m = Mth.floor(box.minZ);
            int n = Mth.ceil(box.maxZ);
            double d = 0.0;

            boolean bl2 = false;
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            find_liquid:
            for (int p = i; p < j; ++p) {
                for (int q = k; q < l; ++q) {
                    for (int r = m; r < n; ++r) {
                        mutable.set(p, q, r);
                        FluidState fluidState = mc.level.getFluidState(mutable);
                        if (fluidState.is(tag)) {
                            double e = (double) ((float) q + fluidState.getHeight(mc.level, mutable));
                            if (e >= box.minY) {
                                bl2 = true;
                                break find_liquid;
                            }
                        }
                    }
                }
            }
            return bl2;
        }
    }

    public void handleY(double y, boolean onGround) {
        // handle water
        if (!mc.player.isInWater()) {
            if ((lastInWater = checkRegionFluid(FluidTags.WATER))) {
                fallDistance = 0.0;
            }
        } else {
            fallDistance = 0.0;
        }
        if (lastY > y) {
            if (!mc.player.isInWater()) {
                fallDistance += lastY - y;
            }
        }
        if (onGround) {
            handleOnGroundFlag();
        }
        // handle reset
        if (lastY < y) {
            startFallingY = y;
            fallDistance = 0;
        }
        handleFallDistanceEnvironmentCheck();
    }

    public void onLand() {}

    public void handleFallDistanceEnvironmentCheck() {
        if (fallDistance < 0) {
            fallDistance = 0;
        }
        if (fallDistance > 0) {
            // check water
        }
    }

    public void handleOnGroundFlag() {
        // fall on
        if (!lastOnGround) {
            onLand();
            lastOnGround = true;
            lastPlayerOnGround = true;
        }
        fallDistance = 0.0;
    }

    public void handleMove(Vec3 pos, boolean onGround) {
        handleY(pos.y(), onGround);
        lastY = pos.y();
        lastX = pos.x();
        lastZ = pos.z();
        lastOnGround = onGround;
    }

    public void handleInWeb(Event<Vec3> vec3dEvent) {
        fallDistance = 0.0;
        lastInWeb = true;
        inWeb = true;
    }

    public void handleInFluid(Event<Vec3> vec3dEvent) {
        TagKey<Fluid> fluidTag = vec3dEvent.getArgs(0);
        if (Objects.equals(FluidTags.WATER, fluidTag)) {
            realInWater = true;
            lastWaterPush = true;
            // do not reset falldistance , because move may be reverted
        } else if (Objects.equals(FluidTags.LAVA, fluidTag)) {
            realInLava = true;
            lastLavaPush = true;
        }
    }

    public void onPreGameTick(Event<LocalPlayer> event) {
        handleTick();
    }

    private BlockPos calculateVelocityAffectingPos() {
        BlockPos pos = mc.player.getBlockPosBelowThatAffectsMyMovement();
        BlockState state = mc.level.getBlockState(pos);
        if (!state.isAir() && !state.liquid()) {
            return pos;
        }
        AABB box = mc.player.getBoundingBox();
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX - 1e-7); // 避免边界溢出，实际遍历时用 <= 处理
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ - 1e-7);
        int y = pos.getY();
        boolean hasBlock = false;
        search:
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos candidate = new BlockPos(x, y, z);
                BlockState candidateState = mc.level.getBlockState(candidate);
                if (!candidateState.isAir() && !candidateState.liquid()) {
                    hasBlock = true;
                    break search;
                }
            }
        }
        if (!hasBlock) {
            return pos;
        }
        AABB velocityTest = box.move(0, 0.500001F, 0);
        List<BlockPos> blockPoses = CollisionUtil.getIntersectingBlockPositions(mc.level, velocityTest, false);
        for (var re : blockPoses) {
            if (pos.getY() == re.getY()) {
                return re;
            }
        }
        return pos;
    }

    private Stream<ItemStack> streamInvContent(ItemStack stack) {
        var cp = stack.get(DataComponents.CONTAINER);
        // 上游是 yarn ContainerComponent.stream()（全部槽位）→ allItemsCopyStream()，不是 nonEmptyItemCopyStream()
        return cp == null ? Stream.empty() : cp.allItemsCopyStream();
    }

    private Stream<ItemStack> streamItems(ItemStack stack) {
        return Streams.concat(Stream.of(stack), streamInvContent(stack).flatMap(this::streamItems));
    }

    private int cooldownInvSummary = 0;

    public void handleTick() {
        // base flag ticks;
        lastInLava = mc.player.isInLava();
        lastInWater = checkRegionFluid(FluidTags.WATER);
        lastClimbing = mc.player.onClimbable();
        lastInWeb = inWeb;
        inWeb = false;
        lastWaterPush = realInWater;
        realInWater = false;
        // fix error fluid state caused by reverting
        if (!lastInWater && mc.player.isInWater()) {
            mc.player.wasTouchingWater = false;
        }
        lastLavaPush = realInLava;
        realInLava = false;
        lastInWall = MovTasks.isCollidingWithEnvironment(mc.player);
        AABB box = mc.player.getBoundingBox();
        lastUnderBlock = MovTasks.isCollidingWithEnvironment(
                mc.player, box.setMinY(box.maxY).setMaxY(box.maxY + 0.42));
        // todo: maybe buggy
        lastHasGroundSupport = CollisionUtil.isEntitySupported(mc.player, 1E-3);
        lastVelocityAffectingPos = calculateVelocityAffectingPos();
        if (++cooldownInvSummary > 10 || inventorySummary == null || inventoryTotalSummary == null) {
            cooldownInvSummary = 0;
            LinkedHashMap<ItemStackSample, Integer> map0 = new LinkedHashMap<>();
            mc.player.getInventory().getNonEquipmentItems().stream()
                    .filter(v -> !v.isEmpty())
                    .forEach(s -> map0.merge(ItemStackSample.of(s), s.getCount(), Integer::sum));
            ItemStack offHand = mc.player.getOffhandItem();
            if (!offHand.isEmpty()) {
                map0.merge(ItemStackSample.of(offHand), offHand.getCount(), Integer::sum);
            }
            inventorySummary = map0;
            LinkedHashMap<ItemStackSample, Integer> map1 = new LinkedHashMap<>(map0.size());
            for (var re : map0.entrySet()) {
                int count = re.getValue();
                streamItems(re.getKey().sample())
                        .filter(v -> !v.isEmpty())
                        .forEach(s -> map1.merge(ItemStackSample.of(s), s.getCount() * count, Integer::sum));
            }

            inventoryTotalSummary = map1;
        }
        // push vec3d
        Vec3 nowPos = new Vec3(lastX, lastY, lastZ);
        last40Positions.addLast(nowPos);
        Vec3 last1MinPos = null;
        while (last40Positions.size() > MAX_SIZE) {
            last1MinPos = last40Positions.removeFirst();
        }
        if (last1MinPos != null) {
            lastAverageMovementSpeed = nowPos.subtract(last1MinPos).scale(1D / MAX_SIZE);
        }

        // falldistance tick
        if (lastInLava) {
            fallDistance *= 0.5;
        }
        if (lastInWater) {
            fallDistance = 0.0;
        }
        if (mc.player.isPassenger()) {
            fallDistance = 0.0;
        }
        if (mc.player.hasEffect(MobEffects.SLOW_FALLING) || mc.player.hasEffect(MobEffects.LEVITATION)) {
            fallDistance = 0.0;
        }
        if (lastClimbing) {
            fallDistance = 0.0;
        }
        if (mc.player.isFallFlying()) {
            glidingTicks += 1;
        } else {
            glidingTicks = 0;
        }
    }

    public void onEntityAttackEvent(Event<ClientboundDamageEventPacket> eventS2C) {
        if (mc.player != null && eventS2C.context.sourceCauseId() == mc.player.getId()) {
            // me attack them
            var source = eventS2C.context.sourceType().unwrapKey().orElse(null);
            if (DamageUtils.isType(source, "mace_smash")) {
                // we trigger a mace smash
                handleMaceSmash();
            }
        }
        if (mc.player != null && eventS2C.context.entityId() == mc.player.getId()) {
            var source = eventS2C.context.sourceType().unwrapKey().orElse(null);
            if (DamageUtils.isType(source, "ender_pearl")) {
                handlePearlTeleport();
            }
        }
    }

    public void onPlayerCommand(Event<ServerboundPlayerCommandPacket> event) {
        if (event.isCancelled()) return;
        switch (event.context.getAction()) {
            case START_FALL_FLYING -> lastStartGlidingTick = Tasks.getTick();
            case START_SPRINTING -> {
                lastSprint = true;
            }
            case STOP_SPRINTING -> {
                lastSprint = false;
            }
        }
    }

    public void onSwingHand(Event<ServerboundSwingPacket> event) {
        lastAttackStrengthResetTick = Tasks.getTick();
        updateAFK();
    }

    public void onAttack(Event<ServerboundAttackPacket> event) {
        // 26.2: 攻击语义由 ServerboundAttackPacket 承载
        if (mc.level.getEntity(event.context.entityId()) instanceof LivingEntity living) {
            lastAttackStrengthResetTick = Tasks.getTick();
            updateAFK();
        }
    }

    public void onInteractBlock(Event<ServerboundUseItemOnPacket> event) {
        updateAFK();
    }

    public void onInteract(Event<ServerboundUseItemPacket> event) {
        updateAFK();
    }

    public void handleMaceSmash() {
        if (fallDistance > 1.5) {
            fallDistance = 0;
        }
    }

    public void onClickSlot(Event<SlotClickAction> eventClick) {
        cooldownInvSummary = 100;
    }

    public void onSelectedSlot(Event<ServerboundSetCarriedItemPacket> event) {
        if (event.isCancelled()) {
            return;
        }
        lastSelectedSlot = event.context.getSlot();
        updateAFK();
    }

    public void onInventoryUpdate(Event<ClientboundContainerSetContentPacket> event) {
        cooldownInvSummary = 100;
    }

    public void onInventorySlotUpdate(Event<ClientboundContainerSetSlotPacket> event) {
        cooldownInvSummary = 100;
    }

    public void onInventoryClose(Event<ServerboundContainerClosePacket> event) {
        cooldownInvSummary = 100;
    }

    public void handlePearlTeleport() {
        fallDistance = 0;
    }

    public void onPlayerReset() {
        startFallingY = Double.MIN_VALUE;
        fallDistance = 0;
        lastKnownMovementSpeed = new Vec3(0, 0, 0);
        lastAverageMovementSpeed = new Vec3(0, 0, 0);
        lastSetBackPosition = new Vec3(0, 0, 0);
        last40Positions.clear();
        for (int i = 0; i < MAX_SIZE; ++i) {
            last40Positions.add(Vec3.ZERO);
        }
        lastX = 0.0D;
        lastY = 0.0D;
        lastZ = 0.0D;
        lastOnGround = false;
        lastPlayerOnGround = false;
        lastPitch = 0.0F;
        lastYaw = 0.0F;
        lastSprint = false;
        lastInput = PlayerInputUtils.EMPTY.clone();
        lastVelocityAffectingPos = BlockPos.ZERO;
        inventoryTotalSummary = null;
        inventorySummary = null;
        glidingTicks = 0;
        lastStartGlidingTick = 0;
        lastSelectedSlot = 0;
        updateAFK();
    }

    public void onTickEnd(Event<ServerboundClientTickEndPacket> tickEndPacket) {
        if (tickEndPacket.isCancelled()) return;
        if (!lastTickHasMovement) {
            lastKnownMovementSpeed = Vec3.ZERO;
            lastMovementContainsPosition = false;
        } else {
            lastKnownChangePosMovementSpeed = lastKnownMovementSpeed;
        }
        lastTickHasMovement = false;
    }

    // api methods

    public Vec3 getLastPosition() {
        return new Vec3(lastX, lastY, lastZ);
    }

    public boolean isRotationDifferent() {
        return EntityUtils.isRotationDifferent(lastPitch, mc.player.getXRot(), lastYaw, mc.player.getYRot());
    }

    public boolean isRotationDifferent(float pitch, float yaw) {
        return EntityUtils.isRotationDifferent(lastPitch, pitch, lastYaw, yaw);
    }

    public Vec3 getLastRotationVector() {
        return EntityUtils.pitchYawToRotation(lastPitch, lastYaw);
    }

    public void restoreLastRotation(Player player) {
        if (isRotationDifferent(player.getXRot(), player.getYRot())) {
            mc.player.setXRot(lastPitch);
            mc.player.setYRot(lastYaw);
        }
    }

    public static void setPlayerYawSafe(Player player, float yaw) {
        float newYaw = EntityUtils.getSafeYaw(INSTANCE.lastYaw, yaw);
        player.setYRot(newYaw);
    }

    public static void setPlayerYawSafe(Player entity, Vec2 vec2f) {
        setPlayerYawSafe(entity, (float) Math.toDegrees(Math.atan2(-vec2f.x, vec2f.y)));
    }

    public static void setPlayerRotationSafe(Player entity, Vec3 vec) {
        vec = vec.normalize();
        EntityUtils.setEntityPitchSafe(entity, (float) Math.toDegrees(Math.asin(-vec.y)));

        float newYaw = (float) Math.toDegrees(Math.atan2(-vec.x, vec.z));
        setPlayerYawSafe(entity, newYaw);
    }

    public void sendSprintStatus(boolean sprint) {
        if (sprint != lastSprint) {
            if (sprint) {
                mc.getConnection()
                        .send(new ServerboundPlayerCommandPacket(
                                mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
            } else {
                mc.getConnection()
                        .send(new ServerboundPlayerCommandPacket(
                                mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            }
            ClientPlayerAccess.of(mc.player).setLastSprintFlag(sprint);
        }
    }

    // other players;
    public static final String KEY_RENDER_CONTROL = "slimefunhelper:player_manager/player_status";
    public static final EquipmentSlot[] ARMOR =
            new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private PlayerStatus getOrCreateStatus(Player pl) {
        MetaData data = ((MetadataHolder) pl).getMetadata();
        return data.getOrPut(this, KEY_RENDER_CONTROL, PlayerStatus::new);
    }

    private PlayerStatus getPlayerStatus0(Player entity) {
        if (entity instanceof MetadataHolder holder && !holder.isMetaEmpty()) {
            return holder.getMetadata().get(this, KEY_RENDER_CONTROL);
        }
        return null;
    }

    public PlayerStatus getPlayerStatus(Player entity) {
        var re = getPlayerStatus0(entity);
        if (re != null && re.lastUpdate < Tasks.getTick() - 10) {
            re = null;
        }
        return re;
    }

    private final Map<UUID, Integer> popMap = new ConcurrentHashMap<>();

    public int getPlayerPopCount(Player entity) {
        var re = popMap.get(entity.getUUID());
        return re == null ? 0 : re;
    }

    public void updateOtherPlayers(Event<LocalPlayer> eventUpdate) {
        for (var player : mc.level.players()) {
            if (player instanceof MetadataHolder metadataHolder) {
                PlayerStatus status = getOrCreateStatus(player);
                status.tickUpdate(player);
            }
        }
    }

    public void onTotemPop(Event<ClientboundEntityEventPacket> event) {
        if (checkNull()) return;
        ClientboundEntityEventPacket packet = event.context;
        if (packet.getEntity(mc.level) instanceof Player player) {
            if (packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH) {
                UUID uid = player.getUUID();
                int val = popMap.merge(uid, 1, Integer::sum);
                CombatListener.getPlayerPopTotem().broadcast(new CombatPlayer(player, val));
            }
            if (packet.getEventId() == EntityEvent.DEATH) {
                onDeath(player);
            }
        }
    }

    private void onDeath(Player entity) {
        Integer popCount = popMap.remove(entity.getUUID());
        CombatListener.getPlayerDeathInfo().broadcast(new CombatPlayer(entity, popCount == null ? 0 : popCount));
    }

    public void onRespawn(Event<ClientboundRespawnPacket> eventRespawn) {
        if (checkNull()) return;
        if (eventRespawn.context.dataToKeep() < 3 && mc.player.getHealth() <= 0) {
            onDeath(mc.player);
        }
    }

    public void onOtherPlayerRemoveDeath(Event<Entity> eventRemoval) {
        if (eventRemoval.context instanceof Player pl && pl != mc.player) {
            CombatListener.getPlayerLeaveVisualRange().broadcast(new CombatPlayer(pl, getPlayerPopCount(pl)));
            if (pl.getHealth() <= 0) {
                onDeath(pl);
            }
        }
    }

    public void onPlayerEnterVisualRange(Event<ClientboundAddEntityPacket> event) {
        if (event.context.getType() == EntityTypes.PLAYER) {
            UUID uid = event.context.getUUID();
            Tasks.scheduleDelayedPre(
                    () -> {
                        Entity player = mc.level.getEntities().get(uid);
                        if (player instanceof Player pl) {
                            CombatListener.getPlayerEnterVisualRange()
                                    .broadcast(new CombatPlayer(pl, getPlayerPopCount(pl)));
                        }
                    },
                    0);
        }
    }

    public void onLeave(Event<Void> event) {
        popMap.clear();
        onPlayerReset();
    }

    public void onPlayerLeave(Event<PlayerInfo> eventRemove) {
        popMap.remove(VRecord.getId(eventRemove.context.getProfile()));
    }

    private static final Int2ObjectOpenHashMap<Holder<MobEffect>> colorToRegistry = new Int2ObjectOpenHashMap<>();

    static {
        for (var re : BuiltInRegistries.MOB_EFFECT) {
            var entry = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(re);
            var color = re.getColor();
            colorToRegistry.put(color, entry);
        }
    }

    public void onEntityTrackedDataUpdate(Event<MetadataUpdate> event) {
        var eventDataUpdate = event.context();
        if (eventDataUpdate.entity() instanceof Player pl) {
            if (eventDataUpdate.metadata().id() == VDataFlag.ID_POTION_SWIRLS
                    && eventDataUpdate.metadata().value() instanceof List<?> lst) {
                // update visible effect list
                List<ParticleOptions> particles = (List<ParticleOptions>) lst;
                PlayerStatus status = getOrCreateStatus(pl);
                Set<Holder<MobEffect>> keys = new HashSet<>(status.visibleStatusEffects.keySet());
                for (var ptc : particles) {
                    if (ptc instanceof ColorParticleOption tinted) {
                        int colorValue = ColorUtils.withAlphaInt(tinted.color, 0);
                        var effect = colorToRegistry.get(colorValue);
                        if (effect != null) {
                            keys.remove(effect);
                            var effectTracker = status.visibleStatusEffects.computeIfAbsent(effect, EffectTracker::new);
                            if (!effectTracker.hasInitialized()) {
                                effectTracker.startTick = Tasks.getTick();
                            }
                            effectTracker.visible = true;
                        }
                    }
                }
                for (var re : keys) {
                    status.visibleStatusEffects.remove(re);
                }
            } else if (eventDataUpdate.metadata().id() == VDataFlag.ID_LIVING_FLAGS
                    && eventDataUpdate.metadata().value() instanceof Number lst) {
                byte byteValue = lst.byteValue();
                PlayerStatus status = getOrCreateStatus(pl);
                boolean useItem = (byteValue & VDataFlag.USING_ITEM_FLAG_INDEX) > 0;
                if (!useItem && pl.isUsingItem()) {
                    // cancel use metadata
                    ItemStack lastUsing = status.lastUsing;
                    InteractionHand lastHand = status.lastUsingHand;
                    if (lastUsing != null && lastHand != null && !lastUsing.isEmpty()) {
                        Tasks.scheduleDelayed(
                                () -> {
                                    ItemStack handItem = pl.getItemInHand(lastHand);
                                    if ((lastUsing.getCount() > 1
                                                    && ItemStack.isSameItemSameComponents(lastUsing, handItem))
                                            || (lastUsing.getCount() <= 1
                                                    && handItem.getItem() != lastUsing.getItem())) {
                                        // mark as consuming
                                        ItemStack consumedUsing = lastUsing;
                                        Consumable componentEat = consumedUsing.get(DataComponents.CONSUMABLE);
                                        if (componentEat != null) {
                                            consumedUsing
                                                    .getAllOfType(ConsumableListener.class)
                                                    .forEach(s -> {
                                                        if (s instanceof PotionContents foodComponent) {
                                                            float scale = (Float) consumedUsing.getOrDefault(
                                                                    DataComponents.POTION_DURATION_SCALE, 1.0F);
                                                            foodComponent.forEachEffect(
                                                                    (instance) -> {
                                                                        if (!(instance.getEffect()
                                                                                        .value())
                                                                                .isInstantaneous()) {
                                                                            status.visibleStatusEffects
                                                                                    .computeIfAbsent(
                                                                                            instance.getEffect(),
                                                                                            EffectTracker::new)
                                                                                    .refresh(instance);
                                                                        }
                                                                    },
                                                                    scale);
                                                        }
                                                        ;
                                                    });
                                            if (!componentEat.onConsumeEffects().isEmpty()) {
                                                for (var effect : componentEat.onConsumeEffects()) {
                                                    if (effect instanceof ApplyStatusEffectsConsumeEffect apply) {
                                                        apply.effects()
                                                                .forEach(s -> status.visibleStatusEffects
                                                                        .computeIfAbsent(
                                                                                s.getEffect(), EffectTracker::new)
                                                                        .refresh(s));
                                                    } else if (effect
                                                            instanceof ClearAllStatusEffectsConsumeEffect clear) {
                                                        // it will be cleared by tracked data update
                                                        // status.visibleStatusEffects.clear();
                                                    } else if (effect
                                                            instanceof RemoveStatusEffectsConsumeEffect remove) {
                                                        // it will be cleared by tracked data update
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                1);
                    }
                }
            }
        }
    }

    public void onEntityEffect(Event<ClientboundUpdateMobEffectPacket> event) {
        if (checkNull()) return;
        if (mc.level.getEntity(event.context.getEntityId()) instanceof Player pl) {
            PlayerStatus status = getOrCreateStatus(pl);
            for (var re : pl.getActiveEffects()) {
                status.visibleStatusEffects
                        .computeIfAbsent(re.getEffect(), EffectTracker::new)
                        .refresh(re);
            }
        }
    }

    private static final List<MobEffectInstance> TOTEM_EFFECTS = List.of(
            new MobEffectInstance(MobEffects.REGENERATION, 900, 1),
            new MobEffectInstance(MobEffects.ABSORPTION, 100, 1),
            new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));

    public void onEntityConsume(Event<ClientboundEntityEventPacket> eventEntityStatusS2CPacket) {
        if (checkNull()) return;
        ClientboundEntityEventPacket packet = eventEntityStatusS2CPacket.context;
        if (packet.getEntity(mc.level) instanceof Player player) {
            // shimt
            if (packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH) {
                // experience
                PlayerStatus status = getOrCreateStatus(player);
                for (var effect : TOTEM_EFFECTS) {
                    status.visibleStatusEffects
                            .computeIfAbsent(effect.getEffect(), EffectTracker::new)
                            .refresh(effect);
                }
            }
        }
    }

    public static float getToleranceMargin(Entity entity) {
        return Math.max(0.0F, Math.min(0.3F, (float) (entity.tickCount - 2) / 20.0F));
    }

    public void onSplashedPotionHit(Event<AbstractThrownPotion> eventPotionEntity) {
        if (checkNull()) return;
        Entity.RemovalReason reason = eventPotionEntity.getArgs(0);
        if (reason.shouldDestroy()) {
            AbstractThrownPotion potionEntity = eventPotionEntity.context;
            ItemStack stack = potionEntity.getItem();
            if (stack.isEmpty()) return;
            PotionContents potionContentsComponent = stack.get(DataComponents.POTION_CONTENTS);
            if (potionContentsComponent == null || Objects.equals(potionContentsComponent, PotionContents.EMPTY)) {
                return;
            }
            float durationScale = stack.getOrDefault(DataComponents.POTION_DURATION_SCALE, 1.0f);
            AABB boundingBox = potionEntity.getBoundingBox();
            boundingBox = boundingBox.inflate(4, 2, 4);
            List<Player> players = mc.level.getEntitiesOfClass(Player.class, boundingBox);
            if (!players.isEmpty()) {
                float g = getToleranceMargin(potionEntity);
                for (Player player : players) {
                    if (player.isDeadOrDying()) continue;
                    double distanceSq = MathUtils.squaredMagnitude(
                            boundingBox, player.getBoundingBox().inflate(g));
                    if (distanceSq >= 16.0) continue;
                    double actualDistance = Math.sqrt(distanceSq);
                    double attenuation = 1.0 - actualDistance / 4.0;
                    for (MobEffectInstance effectInstance : potionContentsComponent.getAllEffects()) {
                        Holder<MobEffect> effectType = effectInstance.getEffect();
                        MobEffect effect = effectType.value();

                        if (!effect.isInstantaneous()) {
                            // 持续效果：持续时间随衰减因子和 durationScale 缩放
                            int originalDuration = effectInstance.getDuration(); // 假设有此方法，原代码通过 mapDuration 获取
                            int newDuration = (int) (attenuation * originalDuration * durationScale + 0.5);
                            // 避免施加过短的效果（小于 1 秒）
                            if (newDuration < 20) continue;

                            MobEffectInstance newInstance = new MobEffectInstance(
                                    effectType,
                                    newDuration,
                                    effectInstance.getAmplifier(),
                                    effectInstance.isAmbient(),
                                    effectInstance.isVisible());
                            getOrCreateStatus(player)
                                    .visibleStatusEffects
                                    .computeIfAbsent(newInstance.getEffect(), EffectTracker::new)
                                    .refresh(newInstance);
                        }
                    }
                }
            }
        }
    }

    private static final String AREA_EFFECT_CLOUD_POTION_CONTENT =
            "slimefunhelper:player_manager/tracking_linger_potion_type";

    public void onLingerPotionHit(Event<AbstractThrownPotion> eventLinger) {
        if (checkNull()) return;
        Entity.RemovalReason reason = eventLinger.getArgs(0);
        if (reason.shouldDestroy()) {
            AbstractThrownPotion potionEntity = eventLinger.context;
            ItemStack stack = potionEntity.getItem();
            if (stack.isEmpty()) return;
            PotionContents potionContentsComponent = stack.get(DataComponents.POTION_CONTENTS);
            if (potionContentsComponent == null || Objects.equals(potionContentsComponent, PotionContents.EMPTY)) {
                return;
            }
            float durationScale = stack.getOrDefault(DataComponents.POTION_DURATION_SCALE, 1.0f);
            Vec3 pos = potionEntity.position();
            int startTick = Tasks.getTick();
            AABB detectBox = new AABB(pos.add(-0.2, -0.2, -0.2), pos.add(0.2, 0.2, 0.2));
            Tasks.scheduleRepeated(
                    () -> {
                        if (checkNull()) return true;
                        if (Tasks.getTick() > startTick + 20) return true;
                        List<AreaEffectCloud> near = mc.level.getEntitiesOfClass(AreaEffectCloud.class, detectBox);
                        if (near.isEmpty()) return false;
                        for (var en : near) {
                            if (en instanceof MetadataHolder holder) {
                                holder.getMetadata()
                                        .put(
                                                this,
                                                AREA_EFFECT_CLOUD_POTION_CONTENT,
                                                Pair.of(potionContentsComponent, durationScale));
                            }
                        }
                        return true;
                    },
                    1,
                    1);
        }
    }

    public void onAreaEffectCloudTick(Event<AreaEffectCloud> eventCloud) {
        if (checkNull()) return;
        if (Tasks.getTick() % 5 != 0) return;
        var cloud = eventCloud.context;
        float radius = cloud.getRadius();
        performCloudUpdate(cloud, radius);
    }

    /**
     * 核心更新逻辑（每 5 刻执行一次）
     */
    private void performCloudUpdate(AreaEffectCloud cloud, float currentRadius) {
        // 1. 清理过期记录（reapplicationDelay 默认 20 刻）
        // 无药水效果则跳过
        if (cloud instanceof MetadataHolder holder && !holder.isMetaEmpty()) {
            MetaData data = holder.getMetadata();
            Pair<PotionContents, Float> pairData = data.get(this, AREA_EFFECT_CLOUD_POTION_CONTENT);
            if (pairData != null) {
                List<Player> targets = mc.level.getEntitiesOfClass(Player.class, cloud.getBoundingBox());
                if (targets.isEmpty()) return;
                List<MobEffectInstance> effectList = new ArrayList<>();
                pairData.getFirst().forEachEffect(effectList::add, pairData.getSecond());
                for (Player target : targets) {
                    // 冷却检查
                    if (target.isDeadOrDying()) continue;
                    // 水平距离检查
                    double dx = target.getX() - cloud.getX();
                    double dz = target.getZ() - cloud.getZ();
                    if (dx * dx + dz * dz > currentRadius * currentRadius) continue;

                    // 施加每个效果
                    for (MobEffectInstance effect : effectList) {
                        MobEffect statusEffect = effect.getEffect().value();
                        if (!statusEffect.isInstantaneous()) {
                            getOrCreateStatus(target)
                                    .visibleStatusEffects
                                    .computeIfAbsent(effect.getEffect(), EffectTracker::new)
                                    .refresh(effect);
                        }
                    }
                }
            }
        }
    }

    private void onEntityEquipmentUpdate(Event<ClientboundSetEquipmentPacket> eventUpdate) {
        if (checkNull()) return;
        if (mc.level.getEntity(eventUpdate.context.getEntity()) instanceof Player pl) {
            for (var re : eventUpdate.context.getSlots()) {
                if (!re.getSecond().isEmpty()) {
                    // shit we should remove damage difference
                    ItemStack cleanItem = ItemStackUtils.getCleanedItem(re.getSecond(), 1, true, false, true);
                    getOrCreateStatus(pl).trackedInventoryItems.add(new ItemStackSample(cleanItem));
                }
            }
        }
    }

    public static class PlayerStatus {

        public int lastUpdate;
        public AttributeMap attributeSnapShot = null;
        // public int popCount;
        public int protection;
        public int blastProtection;
        public ItemStack lastUsing;
        public InteractionHand lastUsingHand;
        public boolean lastInBlock;
        public boolean lastUnderBlock;
        public final Map<Holder<MobEffect>, EffectTracker> visibleStatusEffects = new ConcurrentHashMap<>();
        public final Set<ItemStackSample> trackedInventoryItems = new HashSet<>();
        // todo: add more shit
        public void tickUpdate(Player player) {
            AttributeMap container = new AttributeMap(
                    DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) player.getType()));
            container.assignAllValues(player.getAttributes());
            this.attributeSnapShot = container;
            int protection = 0;
            int blastProtection = 0;
            for (var re : ARMOR) {
                ItemStack stack = player.getItemBySlot(re);
                if (stack.isEmpty()) continue;
                ;
                ItemEnchantments itemEnchant = stack.get(DataComponents.ENCHANTMENTS);
                if (itemEnchant.isEmpty()) continue;
                ;
                int level = ItemStackUtils.getEnchantmentLevel(itemEnchant, Enchantments.PROTECTION);
                protection += level;
                level = ItemStackUtils.getEnchantmentLevel(itemEnchant, Enchantments.BLAST_PROTECTION);
                blastProtection += level;
            }
            this.protection = protection;
            this.blastProtection = blastProtection;
            if (player.isUsingItem()) {
                lastUsing = player.getUseItem().copy();
                lastUsingHand = player.getUsedItemHand();
            } else {
                lastUsing = null;
                lastUsingHand = null;
            }
            lastInBlock = MovTasks.isCollidingWithEnvironment(player);
            AABB box = mc.player.getBoundingBox();
            lastUnderBlock = MovTasks.isCollidingWithEnvironment(
                    player, box.setMinY(box.maxY).setMaxY(box.maxY + 0.42));
            this.lastUpdate = Tasks.getTick();
        }
    }

    public static class EffectTracker {
        int startTick = 0;
        int duration = 0;
        public boolean visible;
        final MobEffect effectInstance;

        public EffectTracker(Holder<MobEffect> effectRegistryEntry) {
            effectInstance = effectRegistryEntry.value();
        }

        public EffectTracker(MobEffectInstance effectInstance) {
            this.effectInstance = effectInstance.getEffect().value();
            this.startTick = Tasks.getTick();
            this.duration = effectInstance.getDuration();
        }

        public void refresh(MobEffectInstance effectInstance) {
            int startTick = Tasks.getTick();
            int duration = effectInstance.getDuration();
            if (startTick + duration > this.startTick + this.duration) {
                this.startTick = startTick;
                this.duration = duration;
            }
        }

        public boolean hasInitialized() {
            return startTick != 0;
        }

        public int getRemainDurations() {
            int tick = startTick + duration;
            return Math.max(tick - Tasks.getTick(), 0);
        }
    }

    public static CommandExecution createServer() {
        return ServerPlayerContext.instance;
    }

    public static class ServerPlayerContext implements CommandExecution {

        static final ServerPlayerContext instance = new ServerPlayerContext();

        @Nullable
        @Override
        public Player getExecutor() {
            return mc.player;
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void sendMessage(@NotNull String message) {
            if (mc.player != null) {
                Debug.sendPlayer(ChatUtils.stringToText(message));
            }
        }

        @Override
        public void sendMessage(Component message) {
            if (mc.player != null) {
                Debug.sendPlayer(message);
            }
        }

        @Override
        public Vector2f getExecuteRot() {
            return new Vector2f(INSTANCE.lastPitch, INSTANCE.lastYaw);
        }

        @NotNull
        @Override
        public Vector3d getExecutePos() {
            return new Vector3d(INSTANCE.lastX, INSTANCE.lastY, INSTANCE.lastZ);
        }

        @NotNull
        @Override
        public Level getExecuteWorld() {
            return mc.level;
        }
    }
}
