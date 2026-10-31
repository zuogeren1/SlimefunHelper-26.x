package me.matl114.hacks.modules.survival;

import java.util.*;
import java.util.function.Predicate;
import me.matl114.accessors.access.ChunkAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.BlockUpdate;
import me.matl114.events.impl.Render2D;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.config.TracingOption;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.move.goal.GoalBlockPos;
import me.matl114.hacks.utils.move.goal.GoalList;
import me.matl114.hacks.utils.move.goal.GoalNearBlockPos;
import me.matl114.hacks.utils.move.goal.IPathGoal;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.hacks.utils.render.RenderMode;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.hooks.BaritoneHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.CommonUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class TrialInfoESP extends BaseModule {
    public TrialInfoESP() {
        super("TrialESP");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "render-utils.trial-esp");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final KeyBindRef pathToNearestSpawner = hotkey(root.add("path-to-nearest-spawner"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::pathToNearestSpawner))
            .build();

    public final KeyBindRef pathToNearestOminousVault = hotkey(root.add("path-to-nearest-ominous-vault"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::pathToNearestOminousVault))
            .build();

    public final DoubleRef textScale = doubleBuilder(root.add("text-scale"))
            .defaultValue(0.75D)
            .validator(Configs.doubleRange(0.1D, 4.0D))
            .build();

    public final DoubleRef textOffset =
            doubleBuilder(root.add("text-offset")).defaultValue(1.0D).build();

    public final NBTRef<TracingOption> traceOption = builder(root.add("trace-option"), TracingOption.class)
            .defaultValue(new TracingOption(true, false))
            .build();

    public final FlagRef renderEnterPosition =
            flagBuilder(root.add("render-enter-position"), true).build();

    public final FlagRef renderVaults =
            flagBuilder(root.add("render-vaults"), true).build();

    public final FlagRef renderSpawners =
            flagBuilder(root.add("render-spawners"), true).build();

    public final FlagRef renderKeys = flagBuilder(root.add("render-keys"), true).build();

    public final EnumRef<RenderMode> textMode = builder(root.add("text-mode"), RenderMode.class)
            .defaultValue(RenderMode.RENDER_2D)
            .build();

    public final NBTRef<WrapColor> colorSpawner = builder(root.add("color-spawner"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.YELLOW))
            .build();

    public final NBTRef<WrapColor> colorVaultSimple = builder(root.add("color-vault-simple"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.AQUA))
            .build();

    public final NBTRef<WrapColor> colorVaultOminous = builder(root.add("color-vault-ominous"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.GOLD))
            .build();

    public final NBTRef<WrapColor> colorNotActive = builder(root.add("color-not-active"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.RED))
            .build();

    public final NBTRef<WrapColor> colorMisc = builder(root.add("color-misc"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.YELLOW))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::onTick);
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
        registerListener(Listener.getWorldSwitchPoint(), this::onSwitchWorld);
        registerListener(
                Listener.getBlockUpdateListener().getChannel(Blocks.TRIAL_SPAWNER), this::onTrialSpawnerActivated);
        registerListener(RenderListener.getRender2DEvent(), this::onRender2D);
    }

    public final RenderCollector<RenderElements.Text> textCollector = RenderCollectors.createTextCollector();
    public final RenderCollector<List<Vec3>> lineRenderCollector = RenderCollectors.createLinesCollector();
    public final RenderCollector<AABB> boxCollector = RenderCollectors.createBoxCollector(true, false, false);
    public final RenderCollector<AABB> boxCollector2 = RenderCollectors.createBoxCollector(false, true, false);
    public final RenderCollector<Vec3> traceCollector = RenderCollectors.createTracerCollector();
    private Map<ChunkPos, Set<BlockPos>> cachedVaultsAndTrials = new HashMap<>();
    private Vec3 enterTrialPosition;
    TimerExecutor cacheClearTimer = new TimerExecutor();

    private void pathToNearestSpawner() {
        if (checkNull() || !BaritoneHooks.getInstance().isBaritoneAPISupported()) {
            return;
        }
        boolean found = pathToCachedPositions(pos -> mc.level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity
                && mc.level.getBlockState(pos).getValue(TrialSpawnerBlock.STATE)
                        == TrialSpawnerState.WAITING_FOR_PLAYERS);
        if (!found && enterTrialPosition != null) {
            BaritoneHooks.getInstance()
                    .setBaritoneCurrentGoal(new GoalBlockPos(BlockPos.containing(enterTrialPosition)));
        }
    }

    private void pathToNearestOminousVault() {
        pathToCachedPositions(pos -> mc.level.getBlockEntity(pos) instanceof VaultBlockEntity vault
                && mc.level.getBlockState(pos).getValue(VaultBlock.OMINOUS)
                && canOpenVault(vault));
    }

    private boolean canOpenVault(VaultBlockEntity vault) {
        Set<UUID> openedPlayers = WorldManager.INSTANCE.getVaultOpenPlayers(vault);
        Set<UUID> remainingPlayers = new HashSet<>(openedPlayers);
        remainingPlayers.removeAll(vault.getSharedData().getConnectedPlayers());
        if (remainingPlayers.size() != openedPlayers.size()) {
            WorldManager.INSTANCE.recordVaultOpenedBy(vault, remainingPlayers);
            openedPlayers = remainingPlayers;
        }
        return !openedPlayers.contains(mc.player.getUUID());
    }

    private boolean pathToCachedPositions(Predicate<BlockPos> filter) {
        if (checkNull() || !BaritoneHooks.getInstance().isBaritoneAPISupported()) {
            return false;
        }
        List<IPathGoal> goals = new ArrayList<>();
        for (var positions : cachedVaultsAndTrials.values()) {
            for (BlockPos pos : positions) {
                if (!filter.test(pos)) {
                    continue;
                }
                goals.add(new GoalNearBlockPos(pos));
            }
        }
        if (!goals.isEmpty()) {
            BaritoneHooks.getInstance().setBaritoneCurrentGoal(new GoalList(goals));
            return true;
        }
        return false;
    }

    public void onTick() {
        if (cacheClearTimer.run(1000)) {
            cachedVaultsAndTrials = new HashMap<>();
        }
        textCollector.clear();
        lineRenderCollector.clear();
        boxCollector.clear();
        boxCollector2.clear();
        traceCollector.clear();
        TracingOption tracingOption = traceOption.get();
        int miscRgb = colorMisc.get().withAlpha(255);
        if (enterTrialPosition != null
                && mc.player != null
                && mc.player.distanceToSqr(enterTrialPosition) > 256.0D * 256.0D) {
            enterTrialPosition = null;
        }
        if (enable.get() && renderEnterPosition.get() && enterTrialPosition != null) {
            Vec3 start = enterTrialPosition;
            lineRenderCollector.submit(List.of(start, start.add(0.0D, 256.0D, 0.0D)), miscRgb);
        }
        if (!checkNull()) {
            for (var chunk : CommonUtils.chunks(false)) {
                ChunkPos pos = chunk.getPos();
                if (!cachedVaultsAndTrials.containsKey(pos)) {
                    Set<BlockPos> sets = new HashSet<>();
                    for (var blockEntities : ChunkAccess.of(chunk).blockEntityEntries()) {
                        var bt = blockEntities.getValue().getType();
                        if (bt == BlockEntityTypes.VAULT || bt == BlockEntityTypes.TRIAL_SPAWNER) {
                            sets.add(blockEntities.getKey());
                        }
                    }
                    cachedVaultsAndTrials.put(pos, sets);
                }
            }
            boolean anyTrialNearby = false;
            for (var re : cachedVaultsAndTrials.values()) {
                for (var bp : re) {
                    anyTrialNearby = true;
                    BlockEntity be = mc.level.getBlockEntity(bp);
                    List<Component> textLines = new ArrayList<>();
                    WrapColor color;
                    boolean renderBlock;
                    if (be instanceof TrialSpawnerBlockEntity be1) {
                        renderBlock = renderSpawners.get();
                        BlockState currentState = mc.level.getBlockState(bp);
                        TrialSpawnerState state = currentState.getValue(TrialSpawnerBlock.STATE);
                        if (state == TrialSpawnerState.COOLDOWN) {
                            highlightTrialKey(bp, miscRgb, tracingOption);
                        }
                        if (state == TrialSpawnerState.ACTIVE) {
                            color = colorSpawner.get();
                        }
                        if (state == TrialSpawnerState.WAITING_FOR_PLAYERS) {
                            color = colorSpawner.get();
                            textLines.add(Component.translatable("message.module.trial-info-esp.display.trial-ready"));
                        } else if (state == TrialSpawnerState.COOLDOWN) {
                            color = colorNotActive.get();
                            OptionalLong cooldownLong = WorldManager.INSTANCE.getTrialSpawnerCooldownStartTime(be1);
                            String time;
                            if (cooldownLong.isPresent()) {
                                long cooldownTime = System.currentTimeMillis() - cooldownLong.getAsLong();
                                long totalSeconds = cooldownTime / 1000;
                                long minutes = totalSeconds / 60; // 总分钟数（不向小时进位）
                                long seconds = totalSeconds % 60; // 剩余的秒数
                                time = minutes + "m" + seconds + "s";
                            } else {
                                time = "?";
                            }
                            textLines.add(
                                    Component.translatable("message.module.trial-info-esp.display.trial-cooldown", time));
                        } else if (state != TrialSpawnerState.INACTIVE) {
                            color = colorNotActive.get();
                            OptionalLong activeLong = WorldManager.INSTANCE.getTrialSpawnerActiveStartTime(be1);
                            String time;
                            if (activeLong.isPresent()) {
                                long activeTime = System.currentTimeMillis() - activeLong.getAsLong();
                                long totalSeconds = activeTime / 1000;
                                long minutes = totalSeconds / 60;
                                long seconds = totalSeconds % 60;
                                time = minutes + "m" + seconds + "s";
                            } else {
                                time = "?";
                            }
                            textLines.add(
                                    Component.translatable("message.module.trial-info-esp.display.trial-active", time));
                        } else {
                            color = colorNotActive.get();
                        }
                        var entity = be1.getTrialSpawner()
                                .getStateData()
                                .getOrCreateDisplayEntity(be1.getTrialSpawner(), mc.level, state);
                        if (entity != null) {
                            textLines.add(Component.translatable(
                                    "message.module.trial-info-esp.display.trial-type",
                                    entity.getType().getDescription()));
                        }
                    } else if (be instanceof VaultBlockEntity be2) {
                        renderBlock = renderVaults.get();
                        BlockState currentState = mc.level.getBlockState(bp);
                        boolean omin = currentState.getValue(VaultBlock.OMINOUS);
                        textLines.add(
                                omin
                                        ? Component.translatable("message.module.trial-info.esp.display.vault-type.ominous")
                                        : Component.translatable("message.module.trial-info.esp.display.vault-type.common"));
                        if (canOpenVault(be2)) {
                            textLines.add(Component.translatable("message.module.trial-info-esp.display.vault-can-open"));
                            color = omin ? colorVaultOminous.get() : colorVaultSimple.get();
                        } else {
                            textLines.add(
                                    Component.translatable("message.module.trial-info-esp.display.vault-can-not-open"));
                            color = colorNotActive.get();
                        }
                    } else {
                        continue;
                    }
                    if (!textLines.isEmpty()) {
                        MutableComponent result = Component.empty().append(textLines.get(0));
                        for (int i = 1; i < textLines.size(); i++) {
                            result = result.append(Component.literal("\n")).append(textLines.get(i));
                        }
                        Vec3 textPos = Vec3.atCenterOf(bp).add(0.0D, textOffset.get(), 0.0D);
                        if (enable.get() && renderBlock) {
                            textCollector.submit(
                                    new RenderElements.Text(result, textPos, (float) textScale.get()),
                                    color.withAlpha(255));
                        }
                    }
                    if (enable.get() && renderBlock && tracingOption.box()) {
                        boxCollector.submit(new AABB(bp), color.withAlpha(255));
                        boxCollector2.submit(new AABB(bp), color.withAlpha(64));
                    }
                    if (enable.get() && renderBlock && tracingOption.line()) {
                        traceCollector.submit(Vec3.atCenterOf(bp), color.withAlpha(255));
                    }
                }
            }
        }
    }

    public void onRender3D(Event<Render3D> event) {
        if (checkNull()) return;
        if (!enable.get()) {
            return;
        }
        RenderUtils.startDrawVirtual(event.context().stack());
        try {
            if (textMode.get().isIn(RenderMode.RENDER_3D)) {
                textCollector.render3D(event.context().stack(), event.context().partialTicks());
            }
            lineRenderCollector.render3D(
                    event.context().stack(), event.context().partialTicks());
            traceCollector.render3D(event.context().stack(), event.context().partialTicks());
            boxCollector.render3D(event.context().stack(), event.context().partialTicks());
            boxCollector2.render3D(event.context().stack(), event.context().partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(event.context().stack());
        }
    }

    public void onRender2D(Event<Render2D> vdraw) {
        if (checkNull()) return;
        if (!enable.get()) {
            return;
        }
        if (textMode.get().isIn(RenderMode.RENDER_2D)) {
            vdraw.context.drawContext().pushMatrix();
            try {
                textCollector.render2D(
                        vdraw.context.drawContext(), vdraw.context().partialTicks());
            } finally {
                vdraw.context.drawContext().popMatrix();
            }
        }
    }

    private void highlightTrialKey(BlockPos spawnerPos, int color, TracingOption tracingOption) {
        AABB keyBox = new AABB(spawnerPos.above());
        for (ItemEntity item : mc.level.getEntities(EntityTypes.ITEM, keyBox, entity -> {
            ItemStack stack = entity.getItem();
            return stack.is(Items.TRIAL_KEY) || stack.is(Items.OMINOUS_TRIAL_KEY);
        })) {
            submitHighlight(item, color, tracingOption);
        }
    }

    private void submitHighlight(Entity entity, int color, TracingOption tracingOption) {
        if (enable.get() && renderKeys.get() && tracingOption.box()) {
            boxCollector.submit(entity.getBoundingBox(), color);
        }
        if (enable.get() && renderKeys.get() && tracingOption.line()) {
            traceCollector.submit(entity.getBoundingBox().getCenter(), color);
        }
    }

    private void onTrialSpawnerActivated(Event<BlockUpdate> event) {
        if (enterTrialPosition != null || mc.player == null) {
            return;
        }
        BlockState oldState = event.context.oldState();
        BlockState newState = event.context.newState();
        if (oldState.getBlock() == Blocks.TRIAL_SPAWNER
                && newState.getBlock() == Blocks.TRIAL_SPAWNER
                && oldState.getValue(TrialSpawnerBlock.STATE) != TrialSpawnerState.ACTIVE
                && newState.getValue(TrialSpawnerBlock.STATE) == TrialSpawnerState.ACTIVE) {
            enterTrialPosition = mc.player.position();
        }
    }

    private void onSwitchWorld() {
        cachedVaultsAndTrials.clear();
    }
}
