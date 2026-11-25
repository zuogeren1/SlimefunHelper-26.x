package me.matl114.hacks.modules.mine;

import com.mojang.datafixers.util.Pair;
import java.awt.*;
import java.util.*;
import me.matl114.accessors.access.PlayerMoveC2SPacketAccess;
import me.matl114.accessors.hacks.PlayerInteractionAccess;
import me.matl114.events.*;
import me.matl114.events.Event;
import me.matl114.events.channels.EventChannelDispatcher;
import me.matl114.events.impl.BlockBreak;
import me.matl114.events.impl.BlockUpdate;
import me.matl114.events.impl.Render3D;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.move.RotationManager;
import me.matl114.hacks.modules.task.ServerFeature;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.LegalMiningMode;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.hacks.utils.ClientUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.hacks.utils.NetworkUtils;
import me.matl114.hacks.utils.RaycastUtils;
import me.matl114.hacks.utils.RenderUtils;
import me.matl114.hacks.utils.WorldUtils;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
//#if MC >= 260300
//$$ import net.minecraft.network.protocol.game.ServerboundPunchPacket;
//#else
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
//#endif
import net.minecraft.util.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.GameType;

public class MineExtra extends BaseModule {
    public static MineExtra INSTANCE;
    public static final EventChannelDispatcher<BlockBreak> doubleMineBreakTick =
            new EventChannelDispatcher<>(BlockBreak::stage);

    private record PendingFailBreak(int seqNum, BlockPos pos, int startBreakTick) {}

    private record PredictedDoubleBreak(BlockState state, int startTick, ClientLevel world) {}

    private PendingFailBreak pendingFailBreak;
    private final Map<BlockPos, PredictedDoubleBreak> cacheMap = new HashMap<>();

    public MineExtra() {
        super("MineExtra");
        INSTANCE = this;
    }

    public final ModulePath fastbreak = makePath(Configs.MINE_CONFIG, "fast-break");

    public final FlagRef quickMine = flagBuilder(fastbreak.addEnable()).build();

    public final KeyBindRef quickMineKeyBind = toggleHotkey(
                    fastbreak.addHotkey(), new MultiKeyBind(), fastbreak.addEnable())
            .build();

    public final EnumRef<Mode> fastBreakBypassMode = builder(fastbreak.add("bypass-mode"), Mode.class)
            .defaultValue(Mode.NO_BYPASS)
            .build();

    public final DoubleRef breakThreshold = builder(fastbreak.add("break-threshold"), Double.class)
            .defaultValue(0.99)
            .validator(Configs.doubleRange(0.0, 1.1))
            .build();

    public final IntRef breakSpeedExtraTicks = builder(fastbreak.add("break-speed-extra-ticks"), IntRef.TYPE)
            .defaultValue(1)
            .validator(Configs.intRange(0, 5))
            .build();

    public final IntRef grimACProgressThreshold = builder(
                    fastbreak.add("grim-progress-punishment-threshold"), Integer.class)
            .defaultValue(150)
            .validator(Configs.intRange(0, 1000))
            .show(() -> fastBreakBypassMode.get().isIn(Mode.BYPASS_GRIM_LEGIT))
            .build();

    public final FlagRef optimizeOneBlock =
            flagBuilder(fastbreak.add("same-block-optimize")).build();

    public final FlagRef ghostHandMine =
            flagBuilder(fastbreak.add("ghost-hand-mine")).build();

    public final FlagRef ghostHandSwapWhenStart = flagBuilder(fastbreak.add("ghost-hand-swap-when-start"))
            .show(ghostHandMine::get)
            .build();

    public final EnumRef<GhostHandMode> ghostHandPreferredMode = builder(
                    fastbreak.add("ghost-hand-preferred-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final ModulePath cooldownGroup = fastbreak.add("cooldown-manage");
    {
        portConfigs(fastbreak.add("break-cooldown"), cooldownGroup.add("break-cooldown"));
        portConfigs(fastbreak.add("cooldown-override"), cooldownGroup.add("cooldown-override"));
        portConfigs(fastbreak.add("cooldown-punishment-fix"), cooldownGroup.add("cooldown-punishment-fix"));
    }

    public final FlagRef cooldownOverride = builder(cooldownGroup.add("cooldown-override"), Boolean.class)
            .defaultValue(true)
            .build();

    public final IntRef breakCooldown = builder(cooldownGroup.add("break-cooldown"), Integer.class)
            .defaultValue(5)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef optimCooldown = builder(cooldownGroup.add("optim-cooldown"), Integer.class)
            .defaultValue(0)
            .validator(Configs.intRange(0, 5))
            .build();

    public final IntRef grimAcCooldownThreshold = builder(
                    cooldownGroup.add("grim-cooldown-punishment-threshold"), Integer.class)
            .defaultValue(150)
            .validator(Configs.intRange(0, 1000))
            .show(() -> fastBreakBypassMode.get().isIn(Mode.BYPASS_GRIM_LEGIT, Mode.BYPASS_GRIM_BAD_PACKETS))
            .build();
    public final NBTRef<OptionalPrimitive<Integer>> cooldownFix = builder(
                    cooldownGroup.add("cooldown-punishment-fix"), OptionalPrimitive.INT_TYPE)
            .defaultValue(new OptionalPrimitive<>(true, NBTTypes.INT_TYPE, 5))
            .show(() -> quickMine.get() && this.fastBreakBypassMode.get().isIn(Mode.BYPASS_GRIM_BAD_PACKETS))
            .build();

    public final NBTRef<OptionalPrimitive<Integer>> startCooldownFix = builder(
                    cooldownGroup.add("cooldown-punishment-fix-start-mine"), OptionalPrimitive.INT_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.INT_TYPE, 8))
            .show(() -> quickMine.get()
                    && this.fastBreakBypassMode.get().isIn(Mode.BYPASS_GRIM_BAD_PACKETS, Mode.BYPASS_GRIM_LEGIT))
            .build();

    public final ModulePath vanillaBreak = fastbreak.add("vanilla-break");

    {
        portConfigs(fastbreak.add("vanilla-fast-break-fix"), vanillaBreak.add("vanilla-fast-break-fix"));
        portConfigs(fastbreak.add("no-swing-for-vanilla-break"), vanillaBreak.add("no-swing-for-vanilla-break"));
    }

    public final FlagRef fasterVanillaBreak = builder(vanillaBreak.add("faster-vanilla-break"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef vanillaFastBreakFix = builder(vanillaBreak.add("vanilla-fast-break-fix"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef noSwing =
            flagBuilder(vanillaBreak.add("no-swing-for-vanilla-break")).build();

    public final ModulePath doubleBreakMine = fastbreak.add("double-break-mine");

    {
        portConfigs(fastbreak.add("double-break"), doubleBreakMine.addEnable());
        portConfigs(fastbreak.add("ghost-hand-fail-break"), doubleBreakMine.add("ghost-hand"));
    }

    public final FlagRef doubleBreak = flagBuilder(doubleBreakMine.addEnable()).build();

    public final FlagRef doubleBreakVanilla = builder(doubleBreakMine.add("enable-vanilla-behavior"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef ghostHandFailBreak =
            flagBuilder(doubleBreakMine.add("ghost-hand")).build();

    public final EnumRef<GhostHandMode> ghostHandFailBreakMode = builder(
                    doubleBreakMine.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef ghostHandFailBreakEatingAbort =
            flagBuilder(doubleBreakMine.add("eating-abort")).build();

    public final NBTRef<OptionalPrimitive<Integer>> doubleBreakPredictDestroy = builder(
                    doubleBreakMine.add("predict-destroy"), OptionalPrimitive.INT_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.INT_TYPE, 5))
            .build();

    public final ModulePath badPackets = fastbreak.add("packet-fix");

    {
        portConfigs(fastbreak.add("fix-multi-break"), badPackets.add("fix-multi-break"));
        portConfigs(fastbreak.add("fix-swing-packet"), badPackets.add("fix-swing-packet"));
        portConfigs(fastbreak.add("silent-break"), badPackets.add("silent-break"));
    }

    public final FlagRef silent = flagBuilder(badPackets.add("silent-break")).build();

    public final FlagRef multiBreakFix =
            flagBuilder(badPackets.add("fix-multi-break")).build();

    public final FlagRef swingFix =
            flagBuilder(badPackets.add("fix-swing-packet")).build();

    public final ModulePath render = fastbreak.add("render");

    {
        portConfigs(fastbreak.add("render-current-break-pos"), render.add("render-current-break-pos"));
        portConfigs(fastbreak.add("render-frame-color"), render.add("render-frame-color"));
        portConfigs(fastbreak.add("render-progress-color"), render.add("render-progress-color"));
    }

    public final FlagRef mineRender =
            flagBuilder(render.add("render-current-break-pos")).build();

    public final NBTRef<WrapColor> frameColor = builder(render.add("render-frame-color"), WrapColor.class)
            .defaultValue(new WrapColor((Color.BLUE)))
            .build();

    public final NBTRef<WrapColor> progressColor = builder(render.add("render-progress-color"), WrapColor.class)
            .defaultValue(new WrapColor((Color.YELLOW)))
            .build();

    public final NBTRef<WrapColor> doubleBreakColor = builder(render.add("double-break-color"), WrapColor.class)
            .defaultValue(new WrapColor((Color.MAGENTA)))
            .build();

    public final FlagRef renderOnlyWhenMine =
            flagBuilder(render.add("render-only-when-mine")).build();

    private IndexEntry<ItemStack> getBestMiningToFor(
            BlockState currentState, GhostHandMode mode, boolean attributeSwap) {
        IndexEntry<ItemStack> defaultEntry = InventoryUtils.getSelectedItem();
        BlockState calculatingState =
                (currentState.isAir() || currentState.liquid()) ? Blocks.OBSIDIAN.defaultBlockState() : currentState;
        float defaultSpeed = WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(
                mc.player, calculatingState, defaultEntry.val(), attributeSwap);
        float hardNess = currentState.getBlock().defaultDestroyTime();
        float predictProgress = hardNess < 0 ? 0 : (defaultSpeed / hardNess);
        // consider insta break situation
        if (predictProgress > 1.1f) {
            return defaultEntry;
        }
        // only select speed > handItem, in the case that player holding a low dur tool
        IndexEntry<ItemStack> result = InventoryUtils.findBestPlayerItem(
                item -> {
                    if (item.isEmpty()
                            || item.getMaxDamage() < 10
                            || item.has(DataComponents.UNBREAKABLE)
                            || item.getDamageValue() < item.getMaxDamage() - 10) {
                        double speed = WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(
                                mc.player, calculatingState, item, attributeSwap);
                        if (speed >= defaultSpeed) {
                            return speed;
                        } else {
                            return null;
                        }
                    }
                    return null;
                },
                mode.getSearchSize(false),
                false,
                true);
        return result != null ? result : defaultEntry;
    }

    public IndexEntry<ItemStack> getGhostHandMiningTool(BlockState currentState) {
        return getGhostHandMiningTool(currentState, false);
    }

    public IndexEntry<ItemStack> getGhostHandMiningTool(BlockState currentState, boolean attributeSwap) {
        IndexEntry<ItemStack> defaultEntry = InventoryUtils.getSelectedItem();
        if (!ghostHandMine.get()) {
            return defaultEntry;
        }
        return getBestMiningToFor(currentState, GhostHandMode.INV_SWAP, attributeSwap);
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreTick(), this::onTickDoubleBreak, Integer.MIN_VALUE + 10);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onPresetLoad);
        registerListener(Listener.getGameJoinPoint(), this::resetStatistics);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class), this::onMine, Integer.MAX_VALUE);
        registerListener(
                SequencedActionManager.getSequencedActionResponse().getChannel(BlockBreak.class), this::onFailBreakAck);
        registerListener(Listener.getBlockUpdateListener(), this::onFailBreakBlockUpdate);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class),
                this::onGrimSBFastBreakExplode,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class),
                this::onSilentBreak,
                Integer.MAX_VALUE);
        //#if MC >= 260300
        //$$ registerListener(
        //$$         Listener.getPacketPostScheduleSendPoint().getChannel(ServerboundPunchPacket.class),
        //$$         this::onLastSwing,
        //$$         true);
        //#else
        registerListener(
                Listener.getPacketPostScheduleSendPoint().getChannel(ServerboundSwingPacket.class),
                this::onLastSwing,
                true);
        //#endif
        registerListener(Listener.getPreGameTick(), this::onGrimCooldownResetPackets);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundMovePlayerPacket.class), this::onPlayerMove);
        registerListener(Listener.getPreGameTick(), this::onTickGhostHandFailBreak);
        registerListener(Listener.getPreTick(), this::onPreUpdateMineRender, Integer.MIN_VALUE + 100);
        registerListener(Listener.getPostTick(), this::onPostUpdateMineRender);
    }

    private void onFailBreakBlockUpdate(Event<BlockUpdate> event) {
        BlockUpdate update = event.context();
        cacheMap.remove(update.pos());
        if (mc.gameMode == null) {
            return;
        }
        PlayerInteractionAccess access = PlayerInteractionAccess.of(mc.gameMode);
        if (update.newState().isAir() && Objects.equals(access.getCurrentFailBreakPos(), update.pos())) {
            access.clearActiveFailBreak();
        }
    }

    private void onFailBreakAck(Event<IndexEntry<BlockBreak>> event) {
        if (checkNull()) return;
        if (pendingFailBreak == null) return;
        IndexEntry<BlockBreak> ackEntry = event.context();
        BlockBreak ack = ackEntry.val();
        PendingFailBreak pending = pendingFailBreak;
        // has more ack
        if (ackEntry.index() > pending.seqNum()) {
            pendingFailBreak = null;
            return;
        }
        if (ack.stage() != BlockBreak.Stage.POST || ackEntry.index() != pending.seqNum()) {
            return;
        }
        pendingFailBreak = null;
        PlayerInteractionAccess access = PlayerInteractionAccess.of(mc.gameMode);
        if (!access.isFailBreakEmpty()) {
            return;
        }
        if (mc.level.getBlockState(pending.pos()).isAir()) {
            return;
        }
        access.commitFailBreak(pending.pos(), pending.startBreakTick());
    }

    int lastSwingPacket = 0;
    BlockPos lastGrimACWrongBreakCheck;
    BlockPos currentMiningBlock;

    public void onLastSwing() {
        lastSwingPacket = Tasks.getTick();
    }

    public Pair<Runnable, BlockPos> instaBreakGhostHand;
    BlockPos lastServerPos;
    Direction lastServerDirection;
    boolean thisTickHasBroken;
    boolean lastMineProgressDeceive = false;

    public void finishMineGhostHand(Runnable runnable) {
        if (ServerFeature.INSTANCE.attributeSwap.get()) {
            InvExtra.INSTANCE.pushNextTickPostTickGhostHandCallback(runnable);
        } else {
            runnable.run();
        }
    }

    public void onMine(Event<ServerboundPlayerActionPacket> packetEvent) {
        if (packetEvent.isCancelled()) {
            return;
        }
        ServerboundPlayerActionPacket packet = packetEvent.context();
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            if (mc.gameMode != null
                    && Objects.equals(
                            PlayerInteractionAccess.of(mc.gameMode).getCurrentMiningPos(), packet.getPos())) {
                lastStartingMineIsInstantBreak = mc.player != null
                        && mc.level != null
                        && instantSpeedPredict1(packet.getPos()) > Math.min(1.0F, breakThreshold.get());
                shouldUseQuickMine();
            }
            updateGainedAdvantageCooldown(packetEvent);
        }
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            onPostStopMiningLegally(packetEvent);
        }
        if (instaBreakGhostHand != null
                && packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && ghostHandMine.get()
                && mc.player != null
                && mc.gameMode != null
                && Objects.equals(packet.getPos(), instaBreakGhostHand.getSecond())) {
            if (instaBreakGhostHand.getFirst() != null) {
                Runnable first = instaBreakGhostHand.getFirst();
                // no need to delay because instant mine do not delay
                PacketManager.schedulePostScheduleCallback(packet, first);
            }
            instaBreakGhostHand = null;
        }
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK -> {
                // just for fixing grimac abort badpackets WrongBreak module
                // filter badPackets
                if (packet.getPos().getY() < 1145) {
                    lastGrimACWrongBreakCheck = packet.getPos();
                    currentMiningBlock = packet.getPos();
                    lastMineProgressDeceive = false;
                } else {
                    lastMineProgressDeceive = true;
                }
            }
            case STOP_DESTROY_BLOCK -> {
                lastGrimACWrongBreakCheck = null;
                currentMiningBlock = null;
                // FastBreak cooldown tryBypass
                lastFinishBreakingTick = Tasks.getTick();
                lastFinishBreakingCooldownTill = Tasks.getTick()
                        + cooldownManaging(
                                (cooldownOverride.get() && breakCooldown.get() >= 0) ? breakCooldown.get() : 5);
                lastOptimFinishBreakCooldownTill = Tasks.getTick() + cooldownManaging(optimCooldown.get());
            }
            case ABORT_DESTROY_BLOCK -> {
                if (!Objects.equals(lastGrimACWrongBreakCheck, packet.getPos())) {
                    packetEvent.cancel();
                } else {
                    lastGrimACWrongBreakCheck = null;
                }
            }
            default -> {
                return;
            }
        }
        // statistic update
        PlayerInteractionAccess access = PlayerInteractionAccess.of(mc.gameMode);

        if (packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                && mc.level != null
                && access.isFailBreakEmpty()
                && pendingFailBreak == null
                && Objects.equals(access.getCurrentMiningPos(), packet.getPos())) {
            pendingFailBreak = new PendingFailBreak(
                    packet.getSequence(), packet.getPos().immutable(), lastStartMineBreakingProgressResetTick);
        }

        if (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            // filter bad packets/ instant break
            if (Objects.equals(access.getCurrentMiningPos(), packet.getPos())) {
                lastStartMineBreakingProgressResetTick = Tasks.getTick();
            }
        }
        // swing packet fix
        if (swingFix.get()
                && packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                && Tasks.getTick() != lastSwingPacket
                && Objects.equals(access.getCurrentMiningPos(), packet.getPos())) {
            // will set lastSwingPacket in the listener above
            ClientUtils.swing(mc.player, InteractionHand.MAIN_HAND);
        }
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
            if (thisTickHasBroken
                    && (packet.getDirection() != lastServerDirection
                            || Objects.equals(lastServerPos, packet.getPos()))) {
                if (multiBreakFix.get()) {
                    PacketManager.schedulePostScheduleCallback(packet, () -> {
                        Listener.sendPacketNoEvents(new ServerboundPlayerActionPacket(
                                packet.getAction(),
                                packet.getPos(),
                                packet.getDirection(),
                                NetworkUtils.generateNextSequence()));
                    });
                }
            }
            lastServerDirection = packet.getDirection();
            lastServerPos = packet.getPos();
            thisTickHasBroken = true;
        }
    }

    private float instantSpeedPredict1(BlockPos pos) {
        if (mc.player.isCreative()) return 10000.0F;
        BlockState state = mc.level.getBlockState(pos);
        IndexEntry<ItemStack> stackEntry = getGhostHandMiningTool(state, ServerFeature.INSTANCE.attributeSwap.get());
        return WorldUtils.calcBlockBreakingDelta(
                state, Minecraft.getInstance().level, pos, mc.player, stackEntry.val());
    }

    public void onPlayerMove(Event<ServerboundMovePlayerPacket> packetEvent) {
        var pkt = PlayerMoveC2SPacketAccess.of(packetEvent.context);
        if (pkt.getCause() == PlayerMoveC2SPacketAccess.Cause.SET_BACK
                || pkt.getCause() == PlayerMoveC2SPacketAccess.Cause.LEGACY_SNAP
                || pkt.getCause() == PlayerMoveC2SPacketAccess.Cause.TRIGGER_SIMULATION) {
            return;
        }
        thisTickHasBroken = false;
    }

    public void onGrimSBFastBreakExplode(Event<ServerboundPlayerActionPacket> event) {
        if (quickMine.get()
                && fastBreakBypassMode.get() == Mode.BYPASS_GRIM_BAD_PACKETS
                && mc.player != null
                && !recursivelySendingPackets) {
            var packet = event.context();
            if (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                    && packet.getPos().getY() < 1145
                    && Objects.equals(
                            PlayerInteractionAccess.of(mc.gameMode).getCurrentMiningPos(), packet.getPos())) {
                if (mc.player.getAbilities().instabuild) {
                    return;
                }
                BlockState state = mc.level.getBlockState(packet.getPos());
                // vanilla instant break
                if (state.isAir()) {
                    return;
                }
                // hacking instant break
                double currentBreakSpeed = WorldUtils.calcBlockBreakingDelta(state, mc.level, packet.getPos());
                // instant break, no need to bypass fastbreak
                if (currentBreakSpeed > 1.01) {
                    return;
                }
                PacketManager.schedulePostScheduleCallback(packet, () -> {
                    if (!lastMineProgressDeceive) {
                        mc.gameMode.startPrediction(mc.level, (seq) -> {
                            return new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                                    BlockPos.containing(mc.player.position()).atY(9178),
                                    Direction.DOWN,
                                    seq);
                        });
                    }
                });
            }
        }
    }

    public void onSilentBreak(Event<ServerboundPlayerActionPacket> eventPlayerAction) {
        if (silent.get()
                && eventPlayerAction.context.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && Objects.equals(
                        PlayerInteractionAccess.of(mc.gameMode).getCurrentMiningPos(),
                        eventPlayerAction.context.getPos())) {
            BlockPos pos = eventPlayerAction.context.getPos();
            PacketManager.schedulePostScheduleCallback(eventPlayerAction.context, () -> {
                var acc = PlayerInteractionAccess.of(mc.gameMode);
                if (Objects.equals(pos, acc.getCurrentMiningPos())) {
                    acc.sendAbortBreakPacket();
                }
            });
        }
    }

    int thisTickStartBreakPackets = 0;
    boolean recursivelySendingPackets = false;

    public void onGrimCooldownResetPackets() {
        thisTickStartBreakPackets = 0;
        if (quickMine.get() && fastBreakBypassMode.get() == Mode.BYPASS_GRIM_BAD_PACKETS && mc.player != null) {
            // exact tick we send,
            if (currentMiningBlock != null && Tasks.getTick() - lastFinishBreakingTick >= 6) {
                if (mc.player.getAbilities().instabuild) {
                    return;
                }
                if (!cooldownFix.get().isPresent()) return;
                int cnt = cooldownFix.get().getValue();
                while (gainedAdvantageCooldown > 50 && thisTickStartBreakPackets < cnt) {
                    mc.gameMode.startPrediction(
                            mc.level,
                            (seq) -> new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                                    BlockPos.containing(mc.player.position()).atY(9178),
                                    Direction.DOWN,
                                    seq));
                }
                ;
            }
        }
    }

    public void onTickGhostHandFailBreak() {
        if (checkNull()) return;
        InvExtra.INSTANCE.tickNextTickCallbacks();
        if (PlayerInteractionAccess.of(mc.gameMode).getCurrentFailBreakPos() != null
                && ghostHandFailBreak.get()) {
            tickGhostHandDoubleBreak(null, ghostHandFailBreakMode.get(), false);
        }
    }

    private void onTickNaturalDoubleBreak() {
        if (checkNull()
                || mc.gameMode == null
                || mc.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            return;
        }
        PlayerInteractionAccess access = PlayerInteractionAccess.of(mc.gameMode);
        BlockPos failBreakPos = access.getCurrentFailBreakPos();
        if (failBreakPos == null) {
            return;
        }
        BlockState state = mc.level.getBlockState(failBreakPos);
        if (state.isAir() || state.liquid()) {
            return;
        }
        float progress = access.getFailBreakMiningProgress();
        if (progress > 1.0F) {
            dispatchDoubleMineBreakTick(new BlockBreak(
                    failBreakPos, BlockBreak.Stage.POST, mc.player.getItemInHand(InteractionHand.MAIN_HAND), progress));
        }
    }

    private void dispatchDoubleMineBreakTick(BlockBreak blockBreak) {
        BlockPos pos = blockBreak.blockPos().immutable();
        ClientLevel world = mc.level;
        BlockState capturedState = null;
        if (!(blockBreak.progress() > 1.0F)) {
            return;
        }
        if (doubleBreakPredictDestroy.get().isPresent() && world != null && !cacheMap.containsKey(pos)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && !state.liquid()) {
                capturedState = state;
            }
        }
        doubleMineBreakTick.broadcast(blockBreak);
        if (capturedState != null
                && mc.level == world
                && capturedState.equals(world.getBlockState(pos))
                && world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())) {
            cacheMap.put(pos, new PredictedDoubleBreak(capturedState, Tasks.getTick() + 1, world));
        }
    }

    private void onTickDoubleBreak() {
        onTickNaturalDoubleBreak();
        if (cacheMap.isEmpty()) {
            return;
        }
        int tick = Tasks.getTick();
        int restoreDelay = doubleBreakPredictDestroy.get().getValue();
        Iterator<Map.Entry<BlockPos, PredictedDoubleBreak>> iterator =
                cacheMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, PredictedDoubleBreak> entry = iterator.next();
            PredictedDoubleBreak cached = entry.getValue();
            if (mc.level != cached.world()) {
                iterator.remove();
                continue;
            }
            if ((long) tick < (long) cached.startTick() + restoreDelay) {
                continue;
            }
            iterator.remove();
            cached.world().setBlockAndUpdate(entry.getKey(), cached.state());
        }
    }

    public int lastTickGhostHandFailBreak = 0;

    private boolean tickMineFailBreak(BlockState state, ItemStack tool, boolean groundDeceive) {
        // do not mine liquid, that's a disaster
        // do not mine air, shit
        if (state.getBlock().defaultDestroyTime() >= 0.0F && !state.liquid() && !state.isAir()) {
            var access = PlayerInteractionAccess.of(mc.gameMode);
            var speed = access.predictFailMiningProgressWithTool(tool, true);
            if (groundDeceive && !mc.player.onGround()) {
                speed *= 5;
            }
            submitFailBreakProgress(speed);
            return speed > 0.99;
        } else {
            submitFailBreakProgress(0.0F);
            return false;
        }
    }

    public void tickGhostHandDoubleBreak(Runnable currentTickCallback, GhostHandMode mode, boolean groundDeceive) {
        if (lastTickGhostHandFailBreak == Tasks.getTick()) {
            if (currentTickCallback != null) {
                finishMineGhostHand(currentTickCallback);
            }
            return;
        }
        if (mc.player.isUsingItem() && ghostHandFailBreakEatingAbort.get()) {
            if (currentTickCallback != null) {
                finishMineGhostHand(currentTickCallback);
            }
            return;
        }
        if (InvExtra.INSTANCE.hasNextTickGhostHandCallback()) {
            if (currentTickCallback != null) {
                finishMineGhostHand(currentTickCallback);
            }
            return;
        }
        lastTickGhostHandFailBreak = Tasks.getTick();

        BlockPos failPos = PlayerInteractionAccess.of(mc.gameMode).getCurrentFailBreakPos();
        if (failPos == null) {
            if (currentTickCallback != null) {
                finishMineGhostHand(currentTickCallback);
            }
            return;
        }
        BlockState blockState = mc.level.getBlockState(failPos);
        IndexEntry<ItemStack> currentItemSlot = getBestMiningToFor(blockState, GhostHandMode.INV_SWAP, false);
        ItemStack currentTool = currentItemSlot.val();
        if (tickMineFailBreak(blockState, currentTool, groundDeceive)) {
            // force delay, because it might save some packets
            if (currentTickCallback != null) {
                finishMineGhostHand(currentTickCallback);
            }
            GhostHandMode preferredMode = mode.isAcceptable(currentItemSlot.index()) ? mode : GhostHandMode.INV_SWAP;
            Runnable callback = InvExtra.INSTANCE.swapItemToHand(currentItemSlot.index(), false, preferredMode);
            InvExtra.INSTANCE.pushNextTickGhostHandCallback(callback);
        } else {
            if (currentTickCallback != null) {
                finishMineGhostHand(currentTickCallback);
            }
        }
    }

    public int lastFinishBreakingTick;
    public int lastFinishBreakingCooldownTill;
    public int lastOptimFinishBreakCooldownTill;

    public int gainedAdvantageCooldown;

    public int lastStartMineBreakingProgressResetTick = 0;

    public boolean lastStartingMineIsInstantBreak = false;

    public int gainedAdvantageMining;

    public int ignoreNextFastBreakStatus = 0;

    public void resetStatistics() {
        lastFinishBreakingTick = 0;
        lastFinishBreakingCooldownTill = 0;
        lastOptimFinishBreakCooldownTill = 0;
        gainedAdvantageCooldown = 0;
        lastStartingMineIsInstantBreak = false;
        gainedAdvantageMining = 0;
        ignoreNextFastBreakStatus = 0;
        pendingFailBreak = null;
    }

    public boolean isVanillaMineCooldownComplete(int extra) {
        return Tasks.getTick() - lastFinishBreakingTick >= 6 + extra;
    }

    public int getOptimMiningPacketCooldown(int extra) {
        return Math.max(0, lastOptimFinishBreakCooldownTill + extra - Tasks.getTick());
    }

    public int getMiningPacketCooldown(int extra) {
        return Math.max(0, lastFinishBreakingCooldownTill + extra - Tasks.getTick());
    }

    public boolean handleMineMode(BlockPos pos, LegalMiningMode mode) {
        if (pos == null || mc.player == null) {
            return false;
        }
        if (mode == LegalMiningMode.NONE) {
            return true;
        }
        boolean directlyHit = RaycastUtils.canRaycastHit(
                mc.player,
                PlayerStateManager.INSTANCE.lastPitch,
                PlayerStateManager.INSTANCE.lastYaw,
                pos,
                InteractExtra.INSTANCE.getBlockReachDistance());
        return switch (mode) {
            case NONE -> true;
            case LEGACY_SNAP -> {
                if (!directlyHit) {
                    LegacySnapRotManager.INSTANCE.snapAt(
                            Vec3.atCenterOf(pos).subtract(mc.player.getEyePosition()).normalize(), false);
                }
                yield true;
            }
            case DELAY_MOVEMENT -> {
                if (directlyHit) {
                    yield true;
                }
                RotationManager manager = RotationManager.INSTANCE;
                if (manager != null) {
                    manager.request(RotationManager.ofBlock(() -> pos).applyMoveFix(true));
                }
                yield false;
            }
        };
    }

    public boolean isSafeCooldownThreshold() {
        if (fastBreakBypassMode.get().hasAc()) {
            if (fastBreakBypassMode.get() == Mode.BYPASS_GRIM_LEGIT) {
                if (gainedAdvantageCooldown > grimAcCooldownThreshold.get()) {
                    return false;
                }
            } else if (fastBreakBypassMode.get() == Mode.BYPASS_GRIM_BAD_PACKETS) {
                // still magic numbers...
                if (doubleBreak.get()) {
                    if (gainedAdvantageCooldown > 50) {
                        return false;
                    }
                } else {
                    if (gainedAdvantageCooldown > 500) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private int cooldownManaging(int cooldownOverride) {
        if (cooldownOverride < 5) {
            if (!isSafeCooldownThreshold()) {
                // shit......
                return 5;
            }
        }
        return cooldownOverride;
    }

    private void updateGainedAdvantageCooldown(Event<ServerboundPlayerActionPacket> event) {
        thisTickStartBreakPackets += 1;
        MineExtra mineExtra = this;
        // escape init case
        if (lastFinishBreakingTick == 0) return;
        int thisCurrentTick = Tasks.getTick();
        // this means it is ok to directly mine
        boolean canResetThisTime = false;
        if (!mc.player.getAbilities().instabuild) {
            if (thisCurrentTick >= lastFinishBreakingTick + 6) {
                canResetThisTime = true;
                gainedAdvantageCooldown = (int) (gainedAdvantageCooldown * 0.9);
            } else {
                gainedAdvantageCooldown += 300 - (thisCurrentTick - lastFinishBreakingTick) * 50;
            }
        } else {
            gainedAdvantageCooldown = Math.min(grimAcCooldownThreshold.get(), gainedAdvantageCooldown);
        }
        gainedAdvantageCooldown = Mth.clamp(gainedAdvantageCooldown, -1000, 1000);

        // we will deal the cooldown shit of bad packets mode in the duplication count of bad packets
        if (canResetThisTime
                && gainedAdvantageCooldown > 50
                && isVanillaMineCooldownComplete(1)
                && mineExtra.fastBreakBypassMode.get().isIn(Mode.BYPASS_GRIM_LEGIT, Mode.BYPASS_GRIM_BAD_PACKETS)
                && (startCooldownFix.get().isPresent() || doubleBreak.get())
                && mc.player != null
                && !mc.player.getAbilities().instabuild) {
            // reset
            BlockPos pos = event.context.getPos();
            BlockState state = mc.level.getBlockState(pos);
            // vanilla instant break
            if (state.isAir()) {
                return;
            }
            // hacking instant break
            double currentBreakSpeed = WorldUtils.calcBlockBreakingDelta(state, mc.level, pos);
            // instant break, no need to bypass fastbreak
            if (currentBreakSpeed > 1.01) {
                return;
            }
            int duplicatedMax = startCooldownFix.get().orElse(5);
            // send now, do not delay
            if (thisTickStartBreakPackets < duplicatedMax) {
                boolean isRecursive = recursivelySendingPackets;
                if (!isRecursive) {
                    recursivelySendingPackets = true;
                }

                mc.gameMode.startPrediction(Minecraft.getInstance().level, (sequence -> {
                    return new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                            pos,
                            event.context.getDirection(),
                            sequence);
                }));
                if (!isRecursive) {
                    recursivelySendingPackets = false;
                }
            }
        }
    }

    public void onPostStopMiningLegally(Event<ServerboundPlayerActionPacket> packetEvent) {
        if (packetEvent.isCancelled()
                || packetEvent.context().getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            return;
        }
        BlockPos pos = packetEvent.context().getPos();
        MineExtra mineExtra = MineExtra.INSTANCE;
        int threshold = mineExtra.grimACProgressThreshold.get();
        ignoreNextFastBreakStatus = 0;
        if (mineExtra.fastBreakBypassMode.getValue() == Mode.BYPASS_GRIM_BAD_PACKETS) {
            // badpackets, no punishment, 桀桀桀
            gainedAdvantageMining = 0;
        } else {
            gainedAdvantageMining = (int) (gainedAdvantageMining * 0.9);
            if (gainedAdvantageMining > threshold
                    && mineExtra.fastBreakBypassMode.getValue() == Mode.BYPASS_GRIM_LEGIT
                    && mc.player != null
                    && mc.level != null
                    && mc.gameMode != null) {
                if (!mc.player.getAbilities().instabuild) {
                    LocalPlayer player = Minecraft.getInstance().player;
                    Direction dir = Direction.getApproximateNearest(
                                    Vec3.atCenterOf(pos).subtract(player.getEyePosition()))
                            .getOpposite();
                    mc.gameMode.startPrediction(mc.level, (sequence -> {
                        return new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir, sequence);
                    }));
                }
            }
        }
        gainedAdvantageMining = Mth.clamp(gainedAdvantageMining, -1000, 1000);
    }

    public void onPostStopMiningFastBreak(BlockPos pos, double speed, double currentProgress) {
        ignoreNextFastBreakStatus = 0;
        if (lastStartMineBreakingProgressResetTick == 0) {
            return;
        }
        MineExtra mineExtra = MineExtra.INSTANCE;
        int predictTick = (int) Math.ceil(1 / speed);
        int tickUsed = (int) Math.ceil(currentProgress / speed);
        int diff = predictTick - tickUsed;
        gainedAdvantageMining += (diff + 1) * 50;
        int threshold = mineExtra.grimACProgressThreshold.get();
        gainedAdvantageMining = Mth.clamp(gainedAdvantageMining, -1000, 1000);
        if (gainedAdvantageMining > threshold && mineExtra.fastBreakBypassMode.getValue() == Mode.BYPASS_GRIM_LEGIT) {
            // only when starting bypass will we do
            // trigger a common mine
            ignoreNextFastBreakStatus = 2;
        }
    }

    public boolean shouldExecuteOptimizeOneBlock() {
        if (this.lastStartMineBreakingProgressResetTick == 0) {
            return false;
        }
        return true;
    }

    /**
     * 判断当前主挖掘进度是否已经可以直接走 fastbreak 收尾。
     *
     * <p>这里不消费 ignore 状态，只负责告诉 mixin：当前这一次 update 是否应立刻走 stop 路径。
     */
    public boolean shouldExecuteFastBreak(float currentProgress) {
        return (quickMine.get() && ignoreNextFastBreakStatus <= 0)
                ? (currentProgress >= breakThreshold.get())
                : (currentProgress >= 1.0D);
    }

    /**
     * 判断当前挖掘速度是否达到瞬时破坏条件。
     */
    public boolean shouldTreatAsInstantBreak(float speed) {
        return speed >= 1.0F || (speed > breakThreshold.get() && quickMine.get());
    }

    /**
     * 切换到新方块时，旧方块是否仍值得转入 doubleBreak / failBreak 支线。
     */
    public boolean shouldTryDoubleBreak(float predictedProgress) {
        return doubleBreak.get() && predictedProgress <= 1.0F;
    }

    /**
     * 判断 quickMine 当前 tick 是否允许生效，并在需要时消费一次忽略窗口。
     */
    public boolean shouldUseQuickMine() {
        if (ignoreNextFastBreakStatus > 0) {
            // I accept the status !
            ignoreNextFastBreakStatus -= 1;
            // somehow we left one status here because of fastBreak
            if (ignoreNextFastBreakStatus > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldRenderMine() {
        if (!renderOnlyWhenMine.get()) return true;
        if (!mc.gameMode.isDestroying() && !PacketMine.INSTANCE.autoEnable.get()) {
            if (!optimizeOneBlock.get()) {
                return false;
            }
            BlockPos blockPos = PlayerInteractionAccess.of(mc.gameMode).getCurrentMiningPos();
            BlockState state = mc.level.getBlockState(blockPos);
            if (state.isAir()) {
                return false;
            }
        }
        return true;
    }

    Pair<BlockPos, Float> currentMiningProgressTick = null;
    Pair<BlockPos, Float> currentFailMiningProgressTick = null;
    RenderCollector<AABB> mineBoxCollector = RenderCollectors.createBoxCollector(false, true, false);
    RenderCollector<AABB> mineFrameCollector = RenderCollectors.createBoxCollector(true, false, false);

    private void onPreUpdateMineRender() {
        currentMiningProgressTick = null;
    }

    public void submitProgress(float progress) {
        var access = PlayerInteractionAccess.of(mc.gameMode);
        if (access != null && access.getCurrentMiningPos() != null) {
            currentMiningProgressTick = Pair.of(access.getCurrentMiningPos(), progress);
        }
    }

    public void submitFailBreakProgress(float progress) {
        var access = PlayerInteractionAccess.of(mc.gameMode);
        if (access != null && access.getCurrentFailBreakPos() != null) {
            currentFailMiningProgressTick = Pair.of(access.getCurrentFailBreakPos(), progress);
        }
    }

    private void onPostUpdateMineRender() {
        mineBoxCollector.clear();
        mineFrameCollector.clear();
        if (checkNull()) return;
        if (mineRender.get()) {
            var access = PlayerInteractionAccess.of(mc.gameMode);
            Pair<BlockPos, Float> currentMine;
            Pair<BlockPos, Float> currentFailMine;
            if (currentMiningProgressTick != null
                    && Objects.equals(currentMiningProgressTick.getFirst(), access.getCurrentMiningPos())) {
                currentMine = currentMiningProgressTick;
            } else if (shouldRenderMine()) {
                BlockPos blockPos = access.getCurrentMiningPos();
                BlockState state = mc.level.getBlockState(blockPos);
                var tool = getGhostHandMiningTool(state, false);
                float progress = PlayerInteractionAccess.of(mc.gameMode)
                        .predictCurrentMiningProgressWithTool(tool.val(), 1);
                currentMine = Pair.of(blockPos, progress);
            } else {
                currentMine = null;
            }
            if (currentFailMiningProgressTick != null
                    && Objects.equals(currentFailMiningProgressTick.getFirst(), access.getCurrentFailBreakPos())) {
                currentFailMine = currentFailMiningProgressTick;
            } else if (access.getCurrentFailBreakPos() != null) {
                BlockPos blockPos = access.getCurrentFailBreakPos();
                float progressFail = PlayerInteractionAccess.of(mc.gameMode).getFailBreakMiningProgress(true);
                currentFailMine = Pair.of(blockPos, progressFail);
            } else {
                currentFailMine = null;
            }
            if (currentMine != null
                    && mc.player.position().distanceToSqr(Vec3.atCenterOf(currentMine.getFirst())) < 40000) {
                submitMiningRender(currentMine, frameColor);
            }
            if (currentFailMine != null
                    && mc.player.position().distanceToSqr(Vec3.atCenterOf(currentFailMine.getFirst())) < 40000
                    && !Objects.equals(
                            currentFailMine.getFirst(), currentMine != null ? currentMine.getFirst() : null)) {
                submitMiningRender(currentFailMine, doubleBreakColor);
            }
        }
    }

    private void submitMiningRender(Pair<BlockPos, Float> currentFailMine, NBTRef<WrapColor> doubleBreakColor) {
        mineFrameCollector.submit(new AABB(currentFailMine.getFirst()), doubleBreakColor.get().withAlpha(255));
        float progress = currentFailMine.getSecond();
        if (progress > 0.0F) {
            AABB box;
            BlockPos blockPos = currentFailMine.getFirst();
            BlockState state = mc.level.getBlockState(blockPos);
            if (state.isAir()) {
                box = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
            } else {
                VoxelShape shape = state.getShape(mc.level, blockPos);
                box = shape.isEmpty() ? new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) : shape.bounds();
            }
            Vec3 vec3 = box.getMaxPosition().subtract(box.getMinPosition()).scale(0.5);

            Vec3 vec3d = Vec3.atLowerCornerOf(blockPos).add(box.getCenter());
            float clamped = Mth.clamp(progress, 0.0F, 1.0F);
            mineBoxCollector.submit(
                    new AABB(vec3d.add(vec3.scale(-clamped)), vec3d.add(vec3.scale(clamped))),
                    progressColor.get().withAlpha(64));
        }
    }

    public void onRender(Event<Render3D> renderEvent) {
        if (mineRender.get()) {
            RenderUtils.startDrawVirtual(renderEvent.context.stack());
            try {
                mineFrameCollector.render3D(renderEvent.context.stack(), renderEvent.context.partialTicks());
                mineBoxCollector.render3D(renderEvent.context.stack(), renderEvent.context.partialTicks());
            } finally {
                RenderUtils.stopDrawVirtual(renderEvent.context.stack());
            }
        }
    }

    public float predictBlockBreakingSpeedAt(BlockPos pos) {
        if (mc.player.isCreative()) return 10000.0F;
        BlockState state = mc.level.getBlockState(pos);
        IndexEntry<ItemStack> stackEntry = getGhostHandMiningTool(state);
        float speed1 =
                WorldUtils.getPlayerBlockBreakingSpeedWithCanMineMultiply(mc.player, state, stackEntry.val(), false);
        return WorldUtils.calcBlockBreakingDelta(state, Minecraft.getInstance().level, pos, speed1);
    }

    public void onPresetLoad(Event<EventContainer<ModulePreset>> presetEvent) {
        ModulePreset preset = presetEvent.context.getValue();
        if (preset.isGhostHandPreset()) {
            ghostHandFailBreakMode.set(preset.getGhostHandMode(ghostHandFailBreakMode.get()));
            ghostHandPreferredMode.set(preset.getGhostHandMode(ghostHandPreferredMode.get()));
        }
        if (preset.isSwingMinePreset()) {
            if (preset.getSwingMine(false)) {
                swingFix.set(true);
                noSwing.set(false);
            } else {
                swingFix.set(false);
                noSwing.set(true);
            }
        }
        if (presetEvent.context().getValue().isOptionPreset()) return;
        var modulePreset = presetEvent.context().getValue();
        switch (modulePreset) {
            case AC_GRIM, AC_GRIM_LEGACY -> {
                fastBreakBypassMode.set(Mode.BYPASS_GRIM_BAD_PACKETS);
                vanillaFastBreakFix.set(true);
            }
            default -> {
                fastBreakBypassMode.set(Mode.NO_BYPASS);
                vanillaFastBreakFix.set(false);
            }
        }
    }

    public static enum Mode implements ConfigEnum {
        NO_BYPASS,
        BYPASS_GRIM_LEGIT,
        BYPASS_GRIM_BAD_PACKETS;

        public boolean hasAc() {
            return this != NO_BYPASS;
        }

        @Override
        public String getConfigEnumType() {
            return "fast_break_bypass_mode";
        }
    }
}
