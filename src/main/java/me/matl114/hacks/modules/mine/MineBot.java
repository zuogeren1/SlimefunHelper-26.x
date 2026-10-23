package me.matl114.hacks.modules.mine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.Listener;
import me.matl114.hacks.MineTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.MineTargetingMode;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.managers.*;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VPacket;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import me.matl114.hacks.utils.EntityUtils;

public class MineBot extends BaseModule {
    public MineBot() {
        super("MineBot");
        bindFlag(enable);
    }

    // should initialize before the config
    private final Random rand = new Random();
    public final ModulePath mineBot = makePath(Configs.MINE_CONFIG, "mine-bot");
    public final FlagRef enable = flagBuilder(mineBot.addEnable()).build();

    public final KeyBindRef keyBind = moduleEntry(
                    mineBot.addHotkey(), new MultiKeyBind(), mineBot.addEnable(), moduleMeta(() -> this.mineBotMode))
            .build();

    public final NBTRef<EntrySet<Block>> whiteListBlockRegex = builder(
                    mineBot.add("whitelist"), EntrySet.<Block>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(cobblestone|stone|.*ore)$"), BuiltInRegistries.BLOCK))
            .build();

    public final EnumRef<MineBotMode> mineBotMode = builder(mineBot.add("mine-mode"), MineBotMode.class)
            .defaultValue(MineBotMode.SPHERICAL)
            .build();

    public final EnumRef<MineTargetingMode> legalMode = builder(
                    mineBot.add("legal-mode"), MineTargetingMode.class)
            .defaultValue(MineTargetingMode.NO_BYPASS)
            .show(() -> this.mineBotMode.get().isNotIn(MineBotMode.AUTO_TOOL))
            .build();

    public final IntRef minY = intBuilder(mineBot.add("min-dy"))
            .defaultValue(0)
            .show(() -> this.mineBotMode.get().isNotIn(MineBotMode.AUTO_TOOL))
            .build();

    public final IntRef maxY = intBuilder(mineBot.add("max-dy"))
            .defaultValue(6)
            .show(() -> this.mineBotMode.get() != MineBotMode.AUTO_TOOL)
            .build();

    public final IntRef width = intBuilder(mineBot.add("max-width"))
            .defaultValue(1)
            .show(() -> this.mineBotMode.get().isIn(MineBotMode.SQUARE, MineBotMode.TUNNEL))
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef maxInstaMine =
            intBuilder(mineBot.add("max-instant-mine")).defaultValue(30).build();

    public final FlagRef doubleBreak =
            flagBuilder(mineBot.add("use-double-break")).build();

    public final FlagRef considerCooldown = builder(mineBot.add("consider-cooldown"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef autoSwap = flagBuilder(mineBot.add("auto-swap")).build();

    public final FlagRef swing = flagBuilder(mineBot.add("swing-hand")).build();

    public final EnumRef<GhostHandMode> ghostHand = builder(mineBot.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef toolProtect = builder(mineBot.add("durability-protect"), Boolean.class)
            .defaultValue(true)
            .build();

    private boolean isMineable(BlockState state) {
        if (state != null && !state.isAir() && !state.liquid()) {
            Block block = state.getBlock();
            if (block.defaultDestroyTime() >= 0.0F && whiteListBlockRegex.get().test(block)) {
                return true;
            }
        }
        return false;
    }

    public BlockPos lastMinePos = null;

    public void onTick() {
        if (isActive()) {
            onMineBotTick();
        }
    }
    // todo: add cooldown

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        lastMinePos = null;
    }

    public void onMineBotTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        switch (mineBotMode.get()) {
            case SPHERICAL -> onMineSpherical();
            case LAYERED_UP -> onMineLayered(true);
            case LAYERED_DOWN -> onMineLayered(false);
            case SQUARE -> onMineSquare();
            case TUNNEL -> onMineTunnel();
            case RANDOM -> onMineRandom();
            case AUTO_TOOL -> onMineCustomTool();
        }
        ;
    }

    public static final int durMultiply = 4;
    public static final int minDurLimit = 9;

    public boolean isDurabilityOk(ItemStack item) {
        if (item.count() == 0) return true;
        int durabilityLimit;
        if (item.get(DataComponents.UNBREAKABLE) != null) {
            return true;
        } else if (item.get(DataComponents.MAX_DAMAGE) != null) {
            Holder<Enchantment> unbreaking =
                    RegistryUtils.getRegistryEntry(ItemStackUtils.registry(), Enchantments.UNBREAKING);
            int multiply = 1;
            if (unbreaking != null) {
                multiply = EnchantmentHelper.getItemEnchantmentLevel(unbreaking, item) + 1;
            }
            durabilityLimit = (durMultiply * (2)) / multiply;
        } else {
            return true;
        }
        int max = Math.max(minDurLimit, durabilityLimit);
        if (item.getDamageValue() > item.getMaxDamage() - max) {
            return false;
        }
        return true;
    }

    private final TimerExecutor noBlocksAround = new TimerExecutor();
    private static final int NO_BLOCK_MENTION_LIMIT = 400;

    public int onMineCommon(Supplier<BlockPos> posFinder) {
        int tryMine = 0;
        Vec2 originPy = new Vec2(mc.player.getXRot(), mc.player.getYRot());
        do {
            if (!checkDistanceAndCondition(lastMinePos)) {
                lastMinePos = posFinder.get();
            }
            if (lastMinePos == null) {
                break;
            }
            if (considerCooldown.get()) {
                if (MineExtra.INSTANCE.getMiningPacketCooldown(1) > 0) {
                    break;
                }
            }
            PlayerInteractionAccess.of(mc.gameMode).setMiningCooldown(0);

            BlockState mineState = mc.level.getBlockState(lastMinePos);
            IndexEntry<ItemStack> bestStack = autoSwap.get()
                    ? InventoryUtils.findBestPlayerItem(
                            s -> {
                                if (isDurabilityOk(s)) {
                                    return (double) WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(
                                            mc.player, mineState, s);
                                } else return null;
                            },
                            ghostHand.get().getSearchSize(false),
                            true,
                            true)
                    : InventoryUtils.getSelectedItem();
            if (bestStack == null || !isDurabilityOk(bestStack.val())) {
                if (toolProtect.get()) {
                    logI18N("message.module.mine-bot.tool-broken-stop");
                    enable.set(false);
                    break;
                } else {
                    bestStack = InventoryUtils.getSelectedItem();
                }
            }
            Runnable runnable = InvExtra.INSTANCE.swapItemToHand(bestStack.index(), false, ghostHand.get());
            float speed = MineExtra.INSTANCE.predictBlockBreakingSpeedAt(lastMinePos);
            tryMine += 1;
            // use real Direction
            Vec3 shouldFacing = Vec3.atCenterOf(lastMinePos).subtract(mc.player.getEyePosition());
            Direction dir = Direction.getApproximateNearest(shouldFacing).getOpposite();
            switch (legalMode.get()) {
                case SWING_HAND_AND_ROT -> {
                    Vec3 rotate2f = mc.player.getLookAngle();
                    Vec3 rotateXZ = new Vec3(rotate2f.x, 0, rotate2f.z);
                    // out of the sight
                    if (rotateXZ.dot(shouldFacing) < 0) {
                        PlayerStateManager.setPlayerYawSafe(mc.player, mc.player.getYRot() + 180);
                        mc.getConnection()
                                .send(VPacket.newLookAndOnGround(
                                        mc.player.getYRot(),
                                        mc.player.getXRot(),
                                        mc.player.onGround(),
                                        mc.player.horizontalCollision));
                    }
                }
                case SWING_HAND_AND_TARGET -> {
                    Vec3 facing = shouldFacing.normalize();
                    Vec2 pitchYaw = EntityUtils.rotationToPitchYaw(facing);
                    if (Math.abs(EntityUtils.getSafeYawDiff(mc.player.getYRot(), pitchYaw.y)) > 30) {
                        mc.player.setXRot(pitchYaw.x);
                        mc.player.setYRot(pitchYaw.y);
                        mc.getConnection()
                                .send(VPacket.newLookAndOnGround(
                                        mc.player.getYRot(),
                                        mc.player.getXRot(),
                                        mc.player.onGround(),
                                        mc.player.horizontalCollision));
                    }
                }
            }
            mc.gameMode.continueDestroyBlock(lastMinePos, dir);
            // fake a swing packet , so that we can bypass some packet check

            if (swing.get()) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }

            if (!MineExtra.INSTANCE.shouldTreatAsInstantBreak(speed)) {
                //
                var access = PlayerInteractionAccess.of(mc.gameMode);
                if (doubleBreak.get()
                        && Objects.equals(access.getCurrentMiningPos(), lastMinePos)
                        && access.isFailBreakEmpty()) {
                    access.sendFailBreakCurrentPos(null);
                } else {
                    break;
                }
            }

        } while (!mc.gameMode.isDestroying() && tryMine < maxInstaMine.get());
        if (mc.player.getXRot() != originPy.x || mc.player.getYRot() != originPy.y) {
            mc.player.setXRot(originPy.x);
            mc.player.setYRot(originPy.y);
            ClientPlayerAccess.of(mc.player).resyncRot();
        }
        if (tryMine == 0) {
            noBlocksAround.run(NO_BLOCK_MENTION_LIMIT, () -> logI18N("message.module.mine-bot.no-blocks"));
        } else {
            noBlocksAround.mark();
        }
        return tryMine;
    }

    public int onMineSpherical() {
        return onMineCommon(this::findNextMinePosSpherical);
    }

    public int onMineLayered(boolean up) {
        return onMineCommon(() -> this.findNextMinePosLayer(up));
    }

    public int onMineSquare() {
        return onMineCommon(this::findNextMinePosSquare);
    }

    public int onMineTunnel() {
        return onMineCommon(this::findNextMinePosTunnel);
    }

    public int onMineRandom() {
        return onMineCommon(this::findNextMinePosRandom);
    }

    public int onMineCustomTool() {
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult result = ((BlockHitResult) mc.hitResult);
            BlockPos pos = result.getBlockPos();
            if (isMineable(mc.level.getBlockState(pos))) {
                mc.gameMode.startPrediction(mc.level, (sequence) -> {
                    return new ServerboundUseItemPacket(
                            InteractionHand.MAIN_HAND, sequence, mc.player.getYRot(), mc.player.getXRot());
                });
            }
        }
        return 0;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreGameTick(), this::onTick);
    }

    public static enum MineBotMode implements ConfigEnum {
        SPHERICAL,
        LAYERED_UP,
        LAYERED_DOWN,
        SQUARE,
        TUNNEL,
        RANDOM,
        AUTO_TOOL;

        @Override
        public String getConfigEnumType() {
            return "mine_bot_mode";
        }
    }

    private boolean checkDistanceAndCondition(BlockPos newPos) {
        var access = PlayerInteractionAccess.of(mc.gameMode);
        // do not mine on double break
        if (Objects.equals(access.getCurrentFailBreakPos(), newPos)) {
            return false;
        }
        if (MineTasks.distanceOutOfReach(newPos, mc.player.getEyePosition())) {
            return false;
        }
        if (!isMineable(mc.level.getBlockState(newPos))) {
            return false;
        }
        return true;
    }

    private BlockPos findNextMinePosSpherical() {
        BlockPos posStanding = mc.player.getOnPos();
        BlockPos posCenter = posStanding.offset(0, 1, 0);
        int lowest = minY.get();
        int highest = maxY.get();
        for (var vec : InteractExtra.INSTANCE.getBlocksAround()) {
            int x = vec.getX();
            int y = vec.getY();
            int z = vec.getZ();
            if (y >= lowest && y <= highest) {
                BlockPos newPose = posCenter.offset(x, y, z);
                if (checkDistanceAndCondition(newPose)) {
                    return newPose;
                }
            }
        }
        return null;
    }

    private BlockPos findNextMinePosLayer(boolean up) {
        BlockPos posStanding = mc.player.getOnPos();
        BlockPos posCenter = posStanding.offset(0, 1, 0);
        int lowest = minY.get();
        int highest = maxY.get();
        IntList yLevelList = IntArrayList.toList(IntStream.range(lowest, highest));
        if (!up) {
            Collections.reverse(yLevelList);
        }
        for (int y : yLevelList) {
            for (var plate : InteractExtra.INSTANCE.getPlatesAround()) {
                int x = plate.x;
                int z = plate.y;
                BlockPos newPose = posCenter.offset(x, y, z);
                if (checkDistanceAndCondition(newPose)) {
                    return newPose;
                }
            }
        }
        return null;
    }

    private BlockPos findNextMinePosTunnel() {
        BlockPos posStanding = mc.player.getOnPos();
        BlockPos posCenter = posStanding.offset(0, 1, 0);
        int lowest = minY.get();
        int highest = maxY.get();
        // horizontal
        Direction facingDirection = mc.player.getDirection();
        Direction facingDirectionCross = facingDirection.getClockWise();
        IntList searchingWidth = new IntArrayList();
        searchingWidth.add(0);
        for (var i = 1; i <= width.get(); ++i) {
            searchingWidth.add(i);
            searchingWidth.add(-i);
        }
        double reach = InteractExtra.INSTANCE.getBlockReachDistance();
        for (var k = 0; k <= reach; ++k) {
            BlockPos currentCenter = posCenter.relative(facingDirection, k);
            for (var i = lowest; i < highest; ++i) {
                BlockPos currentHeightCenter = currentCenter.offset(0, i, 0);
                for (var j : searchingWidth) {
                    BlockPos newPos = currentHeightCenter.relative(facingDirectionCross, j);
                    if (checkDistanceAndCondition(newPos)) {
                        return newPos;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findNextMinePosSquare() {
        BlockPos posStanding = mc.player.getOnPos();
        BlockPos posCenter = posStanding.offset(0, 1, 0);
        int lowest = minY.get();
        int highest = maxY.get();
        // horizontal
        int wid = width.get();
        for (var i = lowest; i < highest; ++i) {
            for (var j = -wid; j <= wid; ++j) {
                for (int k = -wid; k <= wid; ++k) {
                    BlockPos newPos = posCenter.offset(j, i, k);
                    if (checkDistanceAndCondition(newPos)) {
                        return newPos;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findNextMinePosRandom() {
        BlockPos posStanding = mc.player.getOnPos();
        BlockPos posCenter = posStanding.offset(0, 1, 0);
        int lowest = minY.get();
        int highest = maxY.get();
        List<BlockPos> availablePos = new ArrayList<>();
        for (var vec : InteractExtra.INSTANCE.getBlocksAround()) {
            int x = vec.getX();
            int y = vec.getY();
            int z = vec.getZ();
            if (y >= lowest && y <= highest) {
                BlockPos newPose = posCenter.offset(x, y, z);
                if (checkDistanceAndCondition(newPose)) {
                    availablePos.add(newPose);
                }
            }
        }
        // holy shit, who needs it
        return availablePos.isEmpty() ? null : availablePos.get(rand.nextInt(0, availablePos.size()));
    }
}
