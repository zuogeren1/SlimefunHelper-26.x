package me.matl114.hacks.modules.survival;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.chat.RemoteCommand;
import me.matl114.hacks.modules.interact.Interact;
import me.matl114.hacks.modules.interact.InteractExtra;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.RemoteCommandExecution;
import me.matl114.hacks.utils.RenderUtils;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.move.PathingSchedular;
import me.matl114.hacks.utils.move.goal.GoalBlockPos;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.command.MainCommand;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.StringRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.commands.commandGroup.BridgeSubCommand;
import me.matl114.utils.commands.commandGroup.SubCommand;
import me.matl114.utils.commands.commandGroup.TreeSubCommand;
import me.matl114.utils.commands.params.ArgumentReader;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import me.matl114.utils.commands.params.api.CommandExecution;
import me.matl114.utils.commands.params.api.TabResult;
import me.matl114.utils.render.RenderCollector;
import me.matl114.versioned.api.VRecord;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
//#if MC >= 260300
//$$ import net.minecraft.world.level.block.entity.SignTextSlot;
//#endif
import net.minecraft.world.phys.Vec3;

public class PearlBot extends BaseModule {
    private static final String USERNAME_PATTERN = "[A-Za-z0-9_]{3,16}";
    private static final int INTERACTION_TIMEOUT_TICKS = 40;

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "survival-interact-utils.pearl-bot");
    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final KeyBindRef sendPearlTpRequestHotkey = hotkey(root.add("send-pearl-tp-request"), new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::sendPearlTpRequest))
            .build();

    public final StringRef preferredBot =
            builder(root.add("preferred-bot"), StringRef.TYPE).defaultValue("").build();

    public final ModulePath emergencyPull = root.add("emergency-pull");
    public final FlagRef emergencyPullEnable =
            flagBuilder(emergencyPull.add("enable")).build();
    public final StringRef emergencyPullPlayer = builder(emergencyPull.add("player"), StringRef.TYPE)
            .defaultValue("")
            .build();

    public final ModulePath autoSend = root.add("auto-send");
    public final FlagRef autoSendTotemTriggerEnable =
            flagBuilder(autoSend.add("totem-trigger-enable")).build();
    public final IntRef autoSendTotemCountThreshold = builder(autoSend.add("totem-count-threshold"), IntRef.TYPE)
            .defaultValue(99)
            .validator(Configs.INT_NONNEGATIVE)
            .build();
    public final FlagRef autoSendMinHeightEnable =
            flagBuilder(autoSend.add("min-height-enable")).build();
    public final IntRef autoSendMinHeightThreshold = builder(autoSend.add("min-height-threshold"), IntRef.TYPE)
            .defaultValue(256)
            .build();
    public final FlagRef autoSendHealthEnable =
            flagBuilder(autoSend.add("health-enable")).build();
    public final IntRef autoSendHealthThreshold = builder(autoSend.add("health-threshold"), IntRef.TYPE)
            .defaultValue(4)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    private final PathingSchedular pathingSchedular = new PathingSchedular();
    private final RenderCollector<Entity> pearlRenderCollector =
            RenderCollectors.createEntityBoxCollector(true, false, false);
    private final RenderCollector<List<Vec3>> pearlLineCollector = RenderCollectors.createLinesCollector();
    private PullTask pullTask;

    public PearlBot() {
        super("PearlBot");
        bindFlag(enable);
        pathingSchedular
                .active(() -> pullTask != null && pullTask.pearl != null && enable.get())
                .processGoal(() ->
                        pullTask == null || pullTask.standPos == null ? null : new GoalBlockPos(pullTask.standPos));
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
        registerListener(Listener.getPostTick(), this::onAutoSendTick);
        registerListener(Listener.getPostTick(), this::updateRender);
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundEntityEventPacket.class), this::onTotemTrigger);
        registerListener(Listener.getOtherPlayerJoinPoint(), this::onEmergencyPlayerEntryAdd);
        registerListener(Listener.getOtherPlayerEntryUpdate(), this::onEmergencyPlayerEntryUpdate);
        registerCommandBootstrap(this::registerPearlBotCommand);
        RemoteCommand.INSTANCE.REMOTE.registerSub(new BridgeSubCommand(
                "pearlbot", createPearlBotCommand(RemoteCommandExecution.CONTROLLER_PERMISSION + ".pearlbot")));
    }

    @Override
    public void addCustomWidgets(
            Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank, ElementFactory factory) {
        super.addCustomWidgets(acceptor, dx, dy, dblank, factory);
        acceptor.accept(factory.createTitle("widget.remote-command.use-arguments", 0, dblank, dx, dy));
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        clearPullTask();
        clearRender();
    }

    private void registerPearlBotCommand(MainCommand mainCommand) {
        mainCommand.registerSub(new BridgeSubCommand("pearlbot", createPearlBotCommand(null)));
    }

    private TreeSubCommand createPearlBotCommand(String permissionPrefix) {
        SubCommand.Builder<TreeSubCommand> builder =
                SubCommand.treeBuilder().name("pearlbot").helper("widget.pearl-bot.command.help");
        TreeSubCommand command = builder.build();
        var locate = command.subBuilder(SubCommand.taskBuilder())
                .name("locate")
                .permission(permissionPrefix == null ? null : permissionPrefix + ".locate")
                .arg(SimpleCommandArgs.argumentBuilder().name("player").build())
                .post(task -> task.executor((sender, args, reader) ->
                        executeLocate(sender, args.nextArgOrDefault(() -> ""), "locate", reader)))
                .buildAndRegister();
        command.subBuilder(SubCommand.taskBuilder())
                .name("find")
                .permission(permissionPrefix == null ? null : permissionPrefix + ".find")
                .arg(SimpleCommandArgs.argumentBuilder().name("player").build())
                .post(task -> task.executor((sender, args, reader) ->
                        executeLocate(sender, args.nextArgOrDefault(() -> ""), "find", reader)))
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("pull")
                .permission(permissionPrefix == null ? null : permissionPrefix + ".pull")
                .arg(SimpleCommandArgs.argumentBuilder().name("player").build())
                .post(task -> task.executor(
                        (sender, args, reader) -> executePull(sender, args.nextArgOrDefault(() -> ""), reader)))
                .complete();
        command.setFallbackCommand(locate, TabResult.EMPTY);
        return command;
    }

    private boolean executeLocate(CommandExecution sender, String requestedName, String action, ArgumentReader reader) {
        PearlSearchResult result = findPearlsForCommand(sender, requestedName, action, reader, true);
        if (result != null) {
            sendStatus(
                    sender,
                    result.pearls.isEmpty()
                            ? "widget.pearl-bot.feedback.no-pearl"
                            : "widget.pearl-bot.feedback.interactable");
        }
        return true;
    }

    private boolean executePull(CommandExecution sender, String requestedName, ArgumentReader reader) {
        if (reader.hasNext()) {
            return true;
        }
        clearPullTask();
        PearlSearchResult result = findPearlsForCommand(sender, requestedName, "pull", reader, false);
        if (result != null) {
            pullTask = new PullTask(mc.level, result.ownerName, result.ownerUuid, false);
        }
        return true;
    }

    private PearlSearchResult findPearlsForCommand(
            CommandExecution sender, String requestedName, String action, ArgumentReader reader, boolean searchPearls) {
        if (reader.hasNext()) {
            return null;
        }
        RemoteCommand remoteCommand = RemoteCommand.INSTANCE;
        if (!(sender instanceof RemoteCommandExecution) && remoteCommand != null) {
            String remoteCommandText = "pearlbot " + action + (requestedName.isBlank() ? "" : " " + requestedName);
            String botName = preferredBot.get().trim();
            if (!botName.isEmpty()) {
                if (!remoteCommand.sendRemoteCommandTo(botName, remoteCommandText)) {
                    sendStatus(sender, "widget.pearl-bot.feedback.send-failed");
                }
                return null;
            }
            if (remoteCommand.automaticallyControlsOthers()) {
                if (!remoteCommand.sendRemoteCommand(remoteCommandText)) {
                    sendStatus(sender, "widget.pearl-bot.feedback.send-failed");
                }
                return null;
            }
            if (!remoteCommand.allowsControl()) {
                sendStatus(sender, "widget.pearl-bot.feedback.no-preferred-bot");
                return null;
            }
        }
        if (!enable.get()) {
            return null;
        }
        if (checkNull() || WorldManager.INSTANCE == null) {
            return null;
        }

        requestedName = requestedName.trim();
        RemoteCommandExecution remoteSender = sender instanceof RemoteCommandExecution remote ? remote : null;
        UUID ownerUuid = null;
        if (requestedName.isEmpty()) {
            if (remoteSender != null) {
                requestedName = remoteSender.getSenderName();
                if (remoteSender.getSenderProfile() != null) {
                    ownerUuid = VRecord.getId(remoteSender.getSenderProfile());
                }
            } else {
                requestedName = VRecord.getName(mc.player.getGameProfile());
                ownerUuid = mc.player.getUUID();
            }
        } else if (mc.getConnection() != null) {
            PlayerInfo entry = mc.getConnection().getPlayerInfo(requestedName);
            if (entry != null) {
                ownerUuid = VRecord.getId(entry.getProfile());
            }
        }

        if (requestedName == null || !requestedName.matches(USERNAME_PATTERN)) {
            return null;
        }

        List<ThrownEnderpearl> pearls = searchPearls ? findPearlsByOwner(requestedName, ownerUuid) : List.of();
        return new PearlSearchResult(requestedName, ownerUuid, pearls);
    }

    private void sendPearlTpRequest() {
        RemoteCommand remoteCommand = RemoteCommand.INSTANCE;
        if (remoteCommand == null) {
            return;
        }
        String botName = preferredBot.get().trim();
        if (!botName.isEmpty()) {
            remoteCommand.sendRemoteCommandTo(botName, "pearlbot pull");
        } else if (remoteCommand.automaticallyControlsOthers()) {
            remoteCommand.sendRemoteCommand("pearlbot pull");
        } else if (!remoteCommand.allowsControl()) {
            logI18N("widget.pearl-bot.feedback.no-preferred-bot");
        }
    }

    private void onAutoSendTick() {
        if (checkNull() || !enable.get()) {
            return;
        }

        LocalPlayer player = mc.player;
        if (autoSendHealthEnable.get() && player.getHealth() <= autoSendHealthThreshold.get()) {
            logI18N("message.pearl-bot.auto-send.health-triggered");
            sendPearlTpRequest();
            autoSendHealthEnable.set(false);
            return;
        }
        if (autoSendMinHeightEnable.get() && player.getY() < autoSendMinHeightThreshold.get()) {
            logI18N("message.pearl-bot.auto-send.min-height-triggered");
            sendPearlTpRequest();
            autoSendMinHeightEnable.set(false);
        }
    }

    private void updateRender() {
        pearlRenderCollector.clear();
        pearlLineCollector.clear();
        PullTask task = pullTask;
        if (checkNull() || !enable.get()) {
            return;
        }
        if (task == null || task.pearl == null || isPearlDead(task.pearl)) {
            return;
        }

        int color = 0xFF55FFFF;
        if (EntityUtils.isEntityValid(task.pearl)) {
            pearlRenderCollector.submit(task.pearl, color);
        }
        pearlLineCollector.submit(List.of(task.pearlPos, task.pearlPos.add(0.0D, 256.0D, 0.0D)), color);
    }

    private void onRender3D(Event<Render3D> event) {
        if (!enable.get()) {
            return;
        }
        RenderUtils.startDrawVirtual(event.context.stack());
        try {
            pearlRenderCollector.render3D(event.context.stack(), event.context.partialTicks());
            pearlLineCollector.render3D(event.context.stack(), event.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(event.context.stack());
        }
    }

    private void clearRender() {
        pearlRenderCollector.clear();
        pearlLineCollector.clear();
    }

    private void onTotemTrigger(Event<ClientboundEntityEventPacket> event) {
        ClientboundEntityEventPacket packet = event.context;
        if (!enable.get()
                || !autoSendTotemTriggerEnable.get()
                || packet.getEventId() != EntityEvent.PROTECTED_FROM_DEATH
                || mc.level == null
                || mc.player == null) {
            return;
        }

        Entity entity = packet.getEntity(mc.level);
        if (entity == null || entity.getId() != mc.player.getId()) {
            return;
        }

        double remainingTotems = InventoryUtils.computePlayerInventory(
                        stack -> stack.getItem() == Items.TOTEM_OF_UNDYING ? (double) stack.getCount() : null, false)
                - 1;
        if (remainingTotems <= autoSendTotemCountThreshold.get()) {
            logI18N("message.pearl-bot.auto-send.totem-triggered");
            sendPearlTpRequest();
        }
    }

    private void onEmergencyPlayerEntryAdd(Event<PlayerInfo> event) {
        triggerEmergencyPull(event.context);
    }

    private void onEmergencyPlayerEntryUpdate(Event<PlayerInfo> event) {
        if (event.getArgs(0) == ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE) {
            triggerEmergencyPull(event.context);
        }
    }

    private void triggerEmergencyPull(PlayerInfo changedEntry) {
        if (!enable.get() || !emergencyPullEnable.get() || checkNull() || mc.getConnection() == null) {
            return;
        }
        String playerName = emergencyPullPlayer.get().trim();
        String changedName = VRecord.getName(changedEntry.getProfile());
        if (!playerName.matches(USERNAME_PATTERN) || !playerName.equalsIgnoreCase(changedName)) {
            return;
        }
        PlayerInfo currentEntry = mc.getConnection().getPlayerInfo(playerName);
        if (currentEntry == null || currentEntry.getGameMode() != GameType.SURVIVAL) {
            return;
        }
        if (pullTask != null && pullTask.isEmergencyFor(playerName)) {
            return;
        }

        clearPullTask();
        pullTask = new PullTask(mc.level, playerName, VRecord.getId(currentEntry.getProfile()), true);
    }

    private List<ThrownEnderpearl> findPearlsByOwner(String ownerName, UUID ownerUuid) {
        List<ThrownEnderpearl> pearls = new ArrayList<>();
        for (Entity entity : mc.level.getEntities().getAll()) {
            if (entity instanceof ThrownEnderpearl pearl && isOwnedBy(pearl, ownerName, ownerUuid)) {
                pearls.add(pearl);
            }
        }
        return pearls;
    }

    private boolean isOwnedBy(ThrownEnderpearl pearl, String ownerName, UUID ownerUuid) {
        if (pearl.getOwner() instanceof Player owner) {
            return matchesOwner(VRecord.getName(owner.getGameProfile()), owner.getUUID(), ownerName, ownerUuid);
        }

        WorldManager worldManager = WorldManager.INSTANCE;
        UUID storedUuid = worldManager.getThrownEntityOwner(pearl);
        String storedName = worldManager.getThrownEntityOwnerName(pearl);
        if (storedUuid != null || (storedName != null && !storedName.isBlank())) {
            return matchesOwner(storedName, storedUuid, ownerName, ownerUuid);
        }

        return pearl.getOwner() == null && ownerName.equalsIgnoreCase(findPearlOwnerFromNearbySign(pearl));
    }

    private boolean matchesOwner(String actualName, UUID actualUuid, String ownerName, UUID ownerUuid) {
        return (ownerUuid != null && ownerUuid.equals(actualUuid))
                || (actualName != null && actualName.equalsIgnoreCase(ownerName));
    }

    private String findPearlOwnerFromNearbySign(ThrownEnderpearl pearl) {
        Set<String> signOwners = new LinkedHashSet<>();
        BlockPos pearlPos = pearl.blockPosition();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockEntity blockEntity = mc.level.getBlockEntity(pearlPos.offset(x, y, z));
                    if (blockEntity instanceof SignBlockEntity sign) {
                        String signOwner = readSingleSignOwner(sign);
                        if (signOwner != null) {
                            signOwners.add(signOwner);
                        }
                    }
                }
            }
        }
        return signOwners.size() == 1 ? signOwners.iterator().next() : null;
    }

    private String readSingleSignOwner(SignBlockEntity sign) {
        String name = null;
        for (Component lineText : readSignLines(sign)) {
            String text = lineText.getString().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (name != null || !text.matches(USERNAME_PATTERN)) {
                return null;
            }
            name = text;
        }
        return name;
    }

    private List<Component> readSignLines(SignBlockEntity sign) {
        List<Component> lines = new ArrayList<>();
        //#if MC >= 260300
        //$$ for (SignTextSlot slot : new SignTextSlot[] {SignTextSlot.FRONT, SignTextSlot.BACK}) {
        //$$     lines.addAll(sign.getText(slot).getMessages(false));
        //$$ }
        //#else
        lines.addAll(List.of(sign.getFrontText().getMessages(false)));
        lines.addAll(List.of(sign.getBackText().getMessages(false)));
        //#endif
        return lines;
    }

    private boolean bindPullTask(PullTask task, List<ThrownEnderpearl> pearls) {
        if (task.pearl != null) {
            return true;
        }
        PullTarget target = null;
        for (ThrownEnderpearl pearl : pearls.stream()
                .sorted(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(mc.player)))
                .toList()) {
            target = findPullTarget(pearl);
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            return false;
        }

        if (InteractExtra.INSTANCE == null) {
            return false;
        }
        BlockPos upperBlockPos = target.upperBlockPos;
        BlockPos standPos = null;
        if (!InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), upperBlockPos)) {
            standPos = findPullStandPos(upperBlockPos);
            if (standPos == null) {
                return false;
            }
        }

        if (standPos != null && pathingSchedular.engine() instanceof PathingSchedular.NonePathingEngine) {
            return false;
        }
        task.bindPearl(target.pearl, upperBlockPos, standPos, target.placeBlock);
        return true;
    }

    private BlockPos findPullStandPos(BlockPos upperBlockPos) {
        if (InteractExtra.INSTANCE == null) {
            return null;
        }
        return PathingSchedular.findNearbyStandableStop(upperBlockPos, candidate -> {
            BlockPos support = candidate.below();
            var feet = mc.level.getBlockState(candidate);
            var head = mc.level.getBlockState(candidate.above());
            return !candidate.equals(mc.player.blockPosition())
                    && mc.level.getBlockState(support).isCollisionShapeFullBlock(mc.level, support)
                    && !feet.liquid()
                    && !head.liquid()
                    && InteractExtra.INSTANCE.isWithinInteractRange(Vec3.atBottomCenterOf(candidate), upperBlockPos);
        });
    }

    private PullTarget findPullTarget(ThrownEnderpearl pearl) {
        BlockPos pearlPos = pearl.blockPosition();
        for (int y = pearlPos.getY(); y >= pearlPos.getY() - 1; y--) {
            BlockPos waterPos = new BlockPos(pearlPos.getX(), y, pearlPos.getZ());
            if (!mc.level.getBlockState(waterPos).getFluidState().is(FluidTags.WATER)) {
                continue;
            }

            BlockPos upperBlockPos = waterPos.above().immutable();
            var targetState = mc.level.getBlockState(upperBlockPos);
            if (targetState.isAir()) {
                if (findPlaceableItem() != null) {
                    return new PullTarget(pearl, upperBlockPos, true);
                }
                continue;
            }
            if (!targetState.liquid()) {
                return new PullTarget(pearl, upperBlockPos, false);
            }
        }
        return null;
    }

    private IndexEntry<ItemStack> findPlaceableItem() {
        return InventoryUtils.findPlayerItem(stack -> stack.getItem() instanceof BlockItem, false, false);
    }

    private void sendStatus(CommandExecution sender, String translationKey, String... args) {
        sender.sendMessage(Component.translatable(translationKey, (Object[]) args));
    }

    private void onPreInputEvent() {
        if (!enable.get()) {
            clearPullTask();
            return;
        }

        if (checkNull()) {
            clearPullTask();
            return;
        }

        updateEmergencyPullTask();
        PullTask task = pullTask;
        if (task == null) {
            return;
        }
        if (mc.level != task.world) {
            clearPullTask();
            return;
        }

        PlayerInfo ownerEntry =
                mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(task.ownerName);
        if (ownerEntry != null) {
            task.ownerUuid = VRecord.getId(ownerEntry.getProfile());
        }
        boolean ownerCanBePulled =
                ownerEntry != null && (!task.emergency || ownerEntry.getGameMode() == GameType.SURVIVAL);

        if (task.pearl == null) {
            List<ThrownEnderpearl> pearls = findPearlsByOwner(task.ownerName, task.ownerUuid);
            if (pearls.isEmpty() || !bindPullTask(task, pearls)) {
                return;
            }
        }
        if (!ensurePullStandPos(task)) {
            return;
        }

        Entity loadedPearl = task.world.getEntities().get(task.pearlUuid);
        if (loadedPearl instanceof ThrownEnderpearl currentPearl) {
            task.setPearl(currentPearl);
        }
        if (isPearlDead(task.pearl)) {
            if (task.interactionTick >= 0) {
                finishPull(task);
            } else {
                task.clearPearl();
                stopPathing();
            }
            return;
        }
        if (task.interactionTick >= 0) {
            if (Tasks.getTick() - task.interactionTick > INTERACTION_TIMEOUT_TICKS) {
                task.interactionTick = -1;
            } else {
                return;
            }
        }
        if (!(loadedPearl instanceof ThrownEnderpearl)) {
            if (isAtPullPosition(task)) {
                stopPathing();
            } else if (!(pathingSchedular.engine() instanceof PathingSchedular.NonePathingEngine)) {
                pathingSchedular.tickPathing(mc.player);
            }
            return;
        }
        var targetState = mc.level.getBlockState(task.upperBlockPos);
        if (task.placeBlock) {
            if (!targetState.isAir() || findPlaceableItem() == null) {
                return;
            }
        } else if (targetState.isAir() || targetState.liquid()) {
            return;
        }
        if (Interact.INSTANCE == null || InteractExtra.INSTANCE == null) {
            return;
        }
        boolean atPullPosition = (task.standPos == null || task.standPos.equals(mc.player.blockPosition()))
                && InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), task.upperBlockPos);
        if (atPullPosition) {
            stopPathing();
            if (!ownerCanBePulled) {
                return;
            }
            if (!task.placeBlock
                    && SequencedActionManager.INSTANCE != null
                    && SequencedActionManager.INSTANCE.isWaitingBlockResponse(task.upperBlockPos)) {
                return;
            }
            boolean actionStarted =
                    task.placeBlock ? placeBlockAtTarget(task) : Interact.INSTANCE.interactBlock(task.upperBlockPos);
            if (actionStarted) {
                task.interactionTick = Tasks.getTick();
            }
            return;
        }
        if (pathingSchedular.engine() instanceof PathingSchedular.NonePathingEngine) {
            return;
        }
        pathingSchedular.tickPathing(mc.player);
    }

    private boolean ensurePullStandPos(PullTask task) {
        if (task.standPos != null
                || InteractExtra.INSTANCE == null
                || InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), task.upperBlockPos)) {
            return true;
        }
        BlockPos standPos = findPullStandPos(task.upperBlockPos);
        if (standPos == null) {
            return false;
        }
        task.standPos = standPos;
        return true;
    }

    private boolean isAtPullPosition(PullTask task) {
        return (task.standPos == null || task.standPos.equals(mc.player.blockPosition()))
                && (InteractExtra.INSTANCE == null
                        || InteractExtra.INSTANCE.isWithinInteractRange(mc.player.position(), task.upperBlockPos));
    }

    private void updateEmergencyPullTask() {
        if (!emergencyPullEnable.get()) {
            if (pullTask != null && pullTask.emergency) {
                clearPullTask();
            }
            return;
        }

        String playerName = emergencyPullPlayer.get().trim();
        if (!playerName.matches(USERNAME_PATTERN)) {
            if (pullTask != null && pullTask.emergency) {
                clearPullTask();
            }
            return;
        }
        if (pullTask != null) {
            if (!pullTask.emergency || pullTask.isEmergencyFor(playerName)) {
                return;
            }
            clearPullTask();
        }

        PlayerInfo entry =
                mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(playerName);
        pullTask = new PullTask(mc.level, playerName, entry == null ? null : VRecord.getId(entry.getProfile()), true);
    }

    private boolean placeBlockAtTarget(PullTask task) {
        IndexEntry<ItemStack> item = findPlaceableItem();
        if (item == null || InvExtra.INSTANCE == null || Interact.INSTANCE == null) {
            return false;
        }
        Runnable restore = InvExtra.INSTANCE.swapItemToHand(item.index(), false, GhostHandMode.INV_SWAP);
        if (restore == null) {
            return false;
        }

        try {
            return Interact.INSTANCE.placeBlock(task.upperBlockPos);
        } finally {
            InteractionTasks.finishInteractGhostHand(Interact.INSTANCE.blockMode.get(), restore);
        }
    }

    private void clearPullTask() {
        pullTask = null;
        stopPathing();
    }

    private boolean isPearlDead(ThrownEnderpearl pearl) {
        Entity.RemovalReason removalReason = pearl.getRemovalReason();
        return removalReason != null && removalReason.shouldDestroy();
    }

    private void finishPull(PullTask task) {
        clearPullTask();
        if (task.emergency) {
            emergencyPullEnable.set(false);
        }
    }

    private void stopPathing() {
        if (PathingSchedular.currentWorkingInstance != null
                && PathingSchedular.currentWorkingInstance.val() == pathingSchedular) {
            pathingSchedular.disable();
        } else {
            pathingSchedular.enable(false);
        }
    }

    private static final class PullTask {
        private final ClientLevel world;
        private final String ownerName;
        private final boolean emergency;
        private UUID ownerUuid;
        private UUID pearlUuid;
        private ThrownEnderpearl pearl;
        private Vec3 pearlPos;
        private BlockPos upperBlockPos;
        private BlockPos standPos;
        private boolean placeBlock;
        private int interactionTick = -1;

        private PullTask(ClientLevel world, String ownerName, UUID ownerUuid, boolean emergency) {
            this.world = world;
            this.ownerName = ownerName;
            this.ownerUuid = ownerUuid;
            this.emergency = emergency;
        }

        private void bindPearl(ThrownEnderpearl pearl, BlockPos upperBlockPos, BlockPos standPos, boolean placeBlock) {
            setPearl(pearl);
            this.upperBlockPos = upperBlockPos;
            this.standPos = standPos;
            this.placeBlock = placeBlock;
        }

        private void setPearl(ThrownEnderpearl pearl) {
            this.pearl = pearl;
            this.pearlUuid = pearl.getUUID();
            this.pearlPos = pearl.position();
        }

        private void clearPearl() {
            pearl = null;
            pearlUuid = null;
            pearlPos = null;
            upperBlockPos = null;
            standPos = null;
            interactionTick = -1;
        }

        private boolean isEmergencyFor(String playerName) {
            return emergency && ownerName.equalsIgnoreCase(playerName);
        }
    }

    private record PearlSearchResult(String ownerName, UUID ownerUuid, List<ThrownEnderpearl> pearls) {}

    private record PullTarget(ThrownEnderpearl pearl, BlockPos upperBlockPos, boolean placeBlock) {}
}
