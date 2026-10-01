package me.matl114.hacks.modules.survival;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import me.matl114.managers.command.MainCommand;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hooks.BaritoneHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.FileManager;
import me.matl114.managers.config.ConfigEnum;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.file.FileStorage;
import me.matl114.utils.*;
import me.matl114.utils.commands.CommandUtils;
import me.matl114.utils.commands.commandGroup.CommandContext;
import me.matl114.utils.commands.commandGroup.SubCommand;
import me.matl114.utils.commands.commandGroup.TreeSubCommand;
import me.matl114.utils.commands.params.ArgumentInputStream;
import me.matl114.utils.commands.params.ArgumentReader;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import me.matl114.utils.commands.params.api.CommandExecution;
import me.matl114.utils.commands.params.api.TabResult;
import me.matl114.versioned.api.VRender;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class PathManager extends BaseModule {
    public static PathManager INSTANCE;

    private static final int SNAPSHOT_ROLLBACK_TICKS = 20;
    private static final double SNAPSHOT_ROLLBACK_DISTANCE = 3.0D;
    private static final double SNAPSHOT_ROLLBACK_DISTANCE_SQUARED =
            SNAPSHOT_ROLLBACK_DISTANCE * SNAPSHOT_ROLLBACK_DISTANCE;
    private static final Vec3 SNAPSHOT_RENDER_FROM = new Vec3(-0.25D, -0.25D, -0.25D);
    private static final Vec3 SNAPSHOT_RENDER_TO = new Vec3(0.25D, 0.25D, 0.25D);
    private static final String PATH_PATH = "path_storage";
    private static final int TRIM_LOOKAHEAD = 10;
    private static final double TRIM_MAX_DISTANCE_SQUARED = 25.0D;
    private static final double PATH_SEG_SPLIT_OFFSET = 20.0D;
    private static final double PATH_SEG_FINISH_DISTANCE_SQUARED = 4.0D;
    private static final int PATH_SEG_PROGRESS_LOOKAHEAD = 50;
    private static final int PATH_SEG_NETHER_PATH_LIMIT = 40;
    private static final double PATH_SEG_STUCK_DISTANCE_SQUARED = 36.0D;
    private static final int PATH_SEG_STUCK_TICKS = 200;

    public final ModulePath pathManager = makePath(Configs.SURVIVAL_CONFIG, "travelling-control.path-manager");

    public final DoubleRef autoWriteDistance = doubleBuilder(pathManager.add("auto-write-distance"))
            .defaultValue(80.0D)
            .validator(value -> value > 0.0D)
            .build();

    public final FlagRef render = flagBuilder(pathManager.add("render")).build();

    public final EnumRef<Mode> rerunMode = builder(pathManager.add("rerun-mode"), Mode.class)
            .defaultValue(Mode.ELYTRA_FLIGHT)
            .build();

    public final DoubleRef goalDistance = doubleBuilder(pathManager.add("goal-distance"))
            .defaultValue(400.0D)
            .validator(Configs.doubleRange(100, 4e10))
            .show(() -> this.rerunMode.get().isIn(Mode.BARITONE_GOAL))
            .build();

    private FileStorage recordingStorage;
    private String recordingPathFile;
    private RecordPath recordingPath;
    private boolean recordingFlightStarted;
    private RecordSnapshot recordingSnapshot;

    private PathRerunContext currentPath;
    private boolean rerunningBaritone = false;
    public File SAVE_FILE = FileManager.getInstance().getAndCreateFile(PATH_PATH);

    public PathManager() {
        super("PathManager");
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerCommandBootstrap(this::bootStrapPathCommand);
        registerListener(Listener.getPreGameTick(), this::onPreTick);
        registerListener(Listener.getPreGameTick(), this::onTickRunningBaritone);
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldSwitch);
        registerListener(Listener.getServerLeavePoint(), this::onDisconnect);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundRespawnPacket.class), this::onRespawn);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
        registerListener(Listener.getPreGameTick(), this::onTickRunningBaritoneGoal);
    }

    @Override
    public void unregisterAll() {
        super.unregisterAll();
        finishPath("模块卸载", null);
        stopCurrentRunningBaritone();
    }

    public void bootStrapPathCommand(MainCommand mainCommand) {
        TreeSubCommand main = mainCommand.subMainBuilder().name("pathm").build();
        main.subBuilder(SubCommand.taskBuilder())
                .name("start")
                .helper("message.command.pathm.start.help")
                .arg(SimpleCommandArgs.argumentBuilder().name("path_file").build())
                .arg(SimpleCommandArgs.argumentBuilder()
                        .name("force")
                        .defaultValue("")
                        .select("force")
                        .build())
                .post(e -> e.executor(this::onStart))
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("restart")
                .helper("message.command.pathm.restart.help")
                .arg(SimpleCommandArgs.argumentBuilder()
                        .name("path_file")
                        .tabCompletor(TabResult.ofStreamSupplier(CommandUtils.fileSupplier(SAVE_FILE, (ex) -> {
                            return ex.endsWith(".nbt") || ex.endsWith(".dat");
                        })))
                        .build())
                .post(e -> e.executor(this::onReStart))
                .complete()
                .subBuilder(SubCommand.treeBuilder())
                .name("modify")
                .helper("message.command.pathm.modify.help")
                .post(s -> s.subBuilder(SubCommand.taskBuilder())
                        .name("push")
                        .helper("message.command.pathm.modify.push.help")
                        .post(s1 -> s1.executor(CommandContext.execute(this::onPush)))
                        .complete()
                        .subBuilder(SubCommand.taskBuilder())
                        .name("pop")
                        .helper("message.command.pathm.modify.pop.help")
                        .post(s1 -> s1.executor(CommandContext.execute(this::onPop)))
                        .complete()
                        .subBuilder(SubCommand.taskBuilder())
                        .name("pause")
                        .helper("message.command.pathm.modify.pause.help")
                        .post(s1 -> s1.executor(CommandContext.execute(this::onPause)))
                        .complete()
                        .subBuilder(SubCommand.taskBuilder())
                        .name("continue")
                        .helper("message.command.pathm.modify.continue.help")
                        .post(s1 -> s1.executor(CommandContext.execute(this::onContinue)))
                        .complete())
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("stop")
                .helper("message.command.pathm.stop.help")
                .post(e -> e.executor(this::onStop))
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("load")
                .helper("message.command.pathm.load.help")
                .arg(SimpleCommandArgs.argumentBuilder()
                        .name("path_file")
                        .tabCompletor(TabResult.ofStreamSupplier(CommandUtils.fileSupplier(SAVE_FILE, (ex) -> {
                            return ex.endsWith(".nbt") || ex.endsWith(".dat");
                        })))
                        .build())
                .arg(SimpleCommandArgs.argumentBuilder()
                        .name("reverse")
                        .bool(false)
                        .build())
                .post(e -> e.executor(this::onLoad))
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("unload")
                .helper("message.command.pathm.unload.help")
                .post(e -> e.executor(this::onUnload))
                .complete()
                .subBuilder(SubCommand.taskBuilder())
                .name("trim")
                .helper("message.command.pathm.trim.help")
                .arg(SimpleCommandArgs.argumentBuilder()
                        .name("path_file")
                        .tabCompletor(TabResult.ofStreamSupplier(CommandUtils.fileSupplier(SAVE_FILE, (ex) -> {
                            return ex.endsWith(".nbt") || ex.endsWith(".dat");
                        })))
                        .build())
                .arg(SimpleCommandArgs.argumentBuilder()
                        .name("outfile")
                        .defaultObject(null)
                        .build())
                .post(e -> e.executor(this::onTrim))
                .complete()
                .subBuilder(SubCommand.treeBuilder())
                .name("rerun")
                .post(s -> s.subBuilder(SubCommand.taskBuilder())
                        .name("start")
                        .helper("message.command.pathm.rerun.start.help")
                        .post(e -> e.executor(this::onRerun))
                        .complete()
                        .subBuilder(SubCommand.taskBuilder())
                        .name("stop")
                        .helper("message.command.pathm.rerun.stop.help")
                        .post(e -> e.executor(this::onRerunStop))
                        .complete())
                .complete();
    }

    @Override
    public void addCustomWidgets(Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank) {
        super.addCustomWidgets(acceptor, dx, dy, dblank);
        acceptor.accept(createTitle("widget.path-manager.command", 0, dblank, dx, dy));
    }

    boolean pauseRecord = false;

    private boolean onStart(CommandExecution context, ArgumentInputStream args, ArgumentReader rest) {
        if (checkNull()) {
            context.sendMessage("&c当前不在游戏内，无法开始路径录制");
            return true;
        }
        if (recordingStorage != null) {
            context.sendMessage("&c当前已经在录制路径: " + recordingPathFile);
            return true;
        }
        String pathFile = args.nextNonnullString();
        String force = args.nextNonnullString();
        if (!pathFile.endsWith(".nbt")) {
            pathFile = pathFile + ".nbt";
        }
        File file = new File(SAVE_FILE, pathFile);
        if (!"force".equals(force) && file.exists()) {
            context.sendMessage("&c路径文件已存在: " + pathFile + "，请输入 force 或更换文件名");
            return true;
        }
        startPath(pathFile, FileManager.getInstance().getStorage(file));
        context.sendMessage("&a开始等待鞘翅飞行，路径文件: " + pathFile);
        return true;
    }

    private boolean onReStart(CommandExecution context, ArgumentInputStream args, ArgumentReader rest) {
        if (checkNull()) {
            context.sendMessage("&c当前不在游戏内，无法开始路径录制");
            return true;
        }
        if (recordingStorage != null) {
            context.sendMessage("&c当前已经在录制路径: " + recordingPathFile);
            return true;
        }
        String pathFile = args.nextNonnullString();
        FileStorage storage = FileManager.getInstance().getStorage(new File(SAVE_FILE, pathFile), true, false);
        if (storage == null) {
            context.sendMessage("&c路径文件不存在: " + pathFile);
            return true;
        }
        var loadedPath = readPath(storage);
        if (loadedPath == null || loadedPath.bp().isEmpty()) {
            context.sendMessage("&c路径文件为空或格式不正确: " + pathFile);
            storage.markDeprecated(true);
            return true;
        }
        String currentServer = CommonUtils.getServerName();
        String currentWorld = currentWorldKey();
        if (!Objects.equals(loadedPath.server(), currentServer)) {
            context.sendMessage("&e路径服务器不一致: 文件=" + loadedPath.server() + " 当前=" + currentServer + "，仍继续加载");
        }
        if (!Objects.equals(loadedPath.world(), currentWorld)) {
            context.sendMessage("&c路径维度不一致: 文件=" + loadedPath.world() + " 当前=" + currentWorld + "，已取消加载");
            storage.markDeprecated(true);
            return true;
        }
        restartPath(pathFile, storage, loadedPath.bp());
        context.sendMessage("&a已载入历史路线记录，路径文件: " + pathFile);
        onPause(context);
        return true;
    }

    private void onPush(CommandExecution context) {
        if (recordingStorage == null || recordingPath == null) {
            context.sendMessage("&c当前没有正在录制的路径");
            return;
        }
        startSnapshot(mc.player.blockPosition());
        context.sendMessage("&a当前位置以添加");
        return;
    }

    public void onPop(CommandExecution context) {
        if (recordingStorage == null || recordingPath == null) {
            context.sendMessage("&c当前没有正在录制的路径");
            return;
        }
        if (recordingPath.bp.isEmpty()) {
            context.sendMessage("&c当前没有多余的路径点");
            return;
        }

        recordingPath.bp.remove(recordingPath.bp.size() - 1);
        restartSnapshot();
        context.sendMessage("&a当前位置以添加");
        return;
    }

    public void onPause(CommandExecution context) {
        if (recordingStorage == null || recordingPath == null) {
            context.sendMessage("&c当前没有正在录制的路径");
            return;
        }
        pauseRecord = true;
        context.sendMessage(Component.literal("&a当前记录已暂停, 输入!!pathm modify continue (点击该文本以补全)继续录制")
                .withStyle(s -> s.withClickEvent(
                        ChatUtils.getSuggestCommand(MainCommand.getMainCommandPrefix() + "pathm modify continue"))));
    }

    public void onContinue(CommandExecution context) {
        if (recordingStorage == null || recordingPath == null) {
            context.sendMessage("&c当前没有正在录制的路径");
            return;
        }
        pauseRecord = false;
        context.sendMessage("&a当前记录已继续");
    }

    private boolean onStop(CommandExecution context, ArgumentInputStream args, ArgumentReader rest) {
        if (recordingStorage == null) {
            context.sendMessage("&c当前没有正在录制的路径");
            return true;
        }
        int size = finishPath("手动停止", context);
        context.sendMessage("&a路径录制已停止，已保存点数: " + size);
        return true;
    }

    private boolean onLoad(CommandExecution context, ArgumentInputStream args, ArgumentReader rest) {
        if (checkNull()) {
            context.sendMessage("&c当前不在游戏内，无法加载路径");
            return true;
        }
        String pathFile = args.nextNonnullString();
        boolean reverse = args.nextBoolean();
        RecordPath loadedPath;
        try (FileStorage storage = FileManager.getInstance().getStorage(new File(SAVE_FILE, pathFile), true, false)) {
            if (storage == null) {
                context.sendMessage("&c路径文件不存在: " + pathFile);
                return true;
            }
            storage.read();
            loadedPath = readPath(storage);
        }

        if (loadedPath == null || loadedPath.bp().isEmpty()) {
            context.sendMessage("&c路径文件为空或格式不正确: " + pathFile);
            return true;
        }

        String currentServer = CommonUtils.getServerName();
        String currentWorld = currentWorldKey();
        if (!Objects.equals(loadedPath.server(), currentServer)) {
            context.sendMessage("&e路径服务器不一致: 文件=" + loadedPath.server() + " 当前=" + currentServer + "，仍继续加载");
        }
        if (!Objects.equals(loadedPath.world(), currentWorld)) {
            context.sendMessage("&c路径维度不一致: 文件=" + loadedPath.world() + " 当前=" + currentWorld + "，已取消加载");
            return true;
        }

        List<BlockPos> loaded = new ArrayList<>(loadedPath.bp());
        if (reverse) {
            Collections.reverse(loaded);
        }
        var alignLoaded = alignPathToNearest(loaded);
        boolean cutMode = rerunMode.get().isIn(Mode.BARITONE);
        currentPath = createRerunContext(pathFile, alignLoaded, cutMode);
        stopCurrentRunningBaritone();
        context.sendMessage(
                "&a已加载路径: " + pathFile + ", 当前位置 %d / %d".formatted(loaded.size() - alignLoaded.size(), loaded.size())
                        + "，剩余段数: " + currentPath.remainingSegments());
        return true;
    }

    private boolean onUnload(CommandExecution context, ArgumentInputStream args, ArgumentReader rest) {
        if (currentPath == null) {
            context.sendMessage("&c当前没有正在加载的路径");
            return true;
        }
        currentPath = null;
        stopCurrentRunningBaritone();
        context.sendMessage("&c已卸载当前路径");
        return true;
    }

    private boolean onRerun(CommandExecution context, ArgumentInputStream args, ArgumentReader rest) {
        if (currentPath == null) {
            context.sendMessage("&c当前没有正在加载的路径");
            return true;
        }
        if (rerunningBaritone) {
            context.sendMessage("&c当前有正在执行的路径, 请使用指令!!rerun stop终止");
            return true;
        }
        rerunningBaritone = true;
        if (rerunMode.get().isIn(Mode.ELYTRA_FLIGHT)) {
            context.sendMessage("&c当前执行类型为 ELYTRA_FLIGHT, 暂时不支持,请切换为 BARITONE 模式以使用");
            rerunningBaritone = false;
            return true;
        }
        context.sendMessage("&a开始重新执行当前路径");
        return true;
    }

    private boolean onRerunStop(CommandExecution context, ArgumentInputStream args, ArgumentReader rest) {
        if (currentPath == null) {
            context.sendMessage("&c当前没有正在加载的路径");
            return true;
        }
        if (!rerunningBaritone) {
            context.sendMessage("&c当前没有正在执行的路径");
            return true;
        }
        stopCurrentRunningBaritone();
        context.sendMessage("&c当前执行路径已终止");
        return true;
    }

    private boolean onTrim(CommandExecution context, ArgumentInputStream args, ArgumentReader rest) {
        String pathFile = args.nextNonnullString();
        String outputPathFile = args.nextArg();
        if (outputPathFile == null || outputPathFile.isBlank()) {
            outputPathFile = pathFile;
        }
        File file = new File(SAVE_FILE, pathFile);
        File outputFile = new File(SAVE_FILE, outputPathFile);
        if (!file.exists()) {
            context.sendMessage("&c路径文件不存在: " + pathFile);
            return true;
        }
        if (recordingStorage != null
                && (recordingStorage.getFile().equals(file)
                        || recordingStorage.getFile().equals(outputFile))) {
            context.sendMessage("&c该路径正在录制中，无法修剪: " + recordingPathFile);
            return true;
        }
        context.sendMessage("&a开始修剪路径: " + pathFile + " -> " + outputPathFile);
        CompletableFuture.runAsync(() -> trimPathFile(context, pathFile, file, outputFile, outputFile));
        return true;
    }

    private void trimPathFile(
            CommandExecution context, String pathFile, File file, File outputPathFile, File outputFile) {
        RecordPath loadedPath;
        try (FileStorage storage = FileManager.getInstance().getStorage(file, true, false)) {
            if (storage == null) {
                context.sendMessage("&c路径文件不存在: " + pathFile);
                return;
            }
            storage.read();
            loadedPath = readPath(storage);
        } catch (Throwable e) {
            Debug.info("PathManager failed to read path for trim: " + pathFile);
            Debug.info(e);
            context.sendMessage("&c路径读取失败: " + pathFile);
            return;
        }
        if (loadedPath == null || loadedPath.bp().isEmpty()) {
            context.sendMessage("&c路径文件为空或格式不正确: " + pathFile);
            return;
        }
        List<BlockPos> trimmed = trimPath(loadedPath.bp());
        try (FileStorage storage =
                FileManager.getInstance().getStorage(outputFile, false, true).asAutoSave()) {
            writePath(storage, new RecordPath(trimmed, loadedPath.world(), loadedPath.server()));
            context.sendMessage("&a路径修剪完成: "
                    + pathFile
                    + " -> "
                    + outputPathFile
                    + "，原点数: "
                    + loadedPath.bp().size()
                    + "，现点数: "
                    + trimmed.size());
        } catch (Throwable e) {
            Debug.info("PathManager failed to write trimmed path: " + outputPathFile);
            Debug.info(e);
            context.sendMessage("&c路径写入失败: " + outputPathFile);
        }
    }

    private List<BlockPos> trimPath(List<BlockPos> path) {
        if (path.size() <= 2) {
            return List.copyOf(path);
        }
        List<BlockPos> trimmed = new ArrayList<>();
        int current = 0;
        while (current < path.size()) {
            BlockPos currentPos = path.get(current);
            trimmed.add(currentPos.immutable());
            int next = current + 1;
            int maxNext = Math.min(path.size() - 1, current + TRIM_LOOKAHEAD);
            for (int target = maxNext; target >= current + 2; --target) {
                if (isDetour(currentPos, path.get(target - 1), path.get(target))) {
                    next = target;
                    break;
                }
            }
            current = next;
        }
        return trimmed;
    }

    private boolean isDetour(BlockPos point, BlockPos lineStart, BlockPos lineEnd) {
        Vec3 pointVec = Vec3.atCenterOf(point);
        Vec3 startVec = Vec3.atCenterOf(lineStart);
        Vec3 endVec = Vec3.atCenterOf(lineEnd);
        Vec3 line = endVec.subtract(startVec);
        double lineLengthSquared = line.lengthSqr();
        if (lineLengthSquared <= 0.0D) {
            return false;
        }
        double projection = pointVec.subtract(startVec).dot(line) / lineLengthSquared;
        if (projection < 0.0D || projection > 1.0D) {
            return false;
        }
        Vec3 foot = startVec.add(line.scale(projection));
        return pointVec.distanceToSqr(foot) < TRIM_MAX_DISTANCE_SQUARED;
    }

    private void onPreTick(Event<LocalPlayer> event) {
        if (recordingStorage != null) {
            if (checkNull()) {
                finishPath("录制任务意外退出", null);
            } else {
                LocalPlayer player = mc.player;
                if (!recordingFlightStarted) {
                    if (!player.isFallFlying()) {
                        return;
                    }
                    startSnapshot(player.blockPosition());
                    recordingFlightStarted = true;
                    return;
                }
                recordCurrentPosition(player.blockPosition());
            }
        }
    }

    private void onWorldSwitch(Event<Level> event) {
        finishPath("切换世界", null);
        currentPath = null;
        stopCurrentRunningBaritone();
    }

    private void onDisconnect(Event<Void> event) {
        finishPath("断开连接", null);
        currentPath = null;
        stopCurrentRunningBaritone();
    }

    private void onRespawn(Event<ClientboundRespawnPacket> event) {
        finishPath("玩家重生", null);
        currentPath = null;
        stopCurrentRunningBaritone();
    }

    private static final int POSITION_FLAG = VRender.createTextPositionFlag(0, 1);

    private void onRender(Event<Render3D> event) {
        if (!render.get() || checkNull()) {
            return;
        }
        PathSeg currentSeg = currentPath == null ? null : currentPath.currentSegment();
        List<BlockPos> currentSegSubPath = currentSeg == null ? List.of() : currentSeg.currentSubPath();
        if (recordingSnapshot == null && currentSegSubPath.isEmpty()) {
            return;
        }
        Vec3 feetPos = RenderUtils.getCameraPos();
        RenderUtils.startDrawVirtual(event.context().stack());
        try {
            if (recordingSnapshot != null) {
                Vec3 snapshotPos = Vec3.atCenterOf(recordingSnapshot.snapshotPos());
                RenderUtils.drawOutlinedBox(
                        event.context().stack(),
                        snapshotPos.add(SNAPSHOT_RENDER_FROM),
                        snapshotPos.add(SNAPSHOT_RENDER_TO),
                        Color.CYAN);
                Vec3 delta = snapshotPos.subtract(feetPos);
                RenderUtils.drawLineVirtualCameraCoord(
                        event.context().stack(), delta, RenderUtils.getTracerOrigin(0.0F), Color.CYAN);
                var stack = event.context.stack();
                stack.pushPose();
                stack.translate(delta.x, delta.y + 0.25, delta.z);
                // title的高度是9 我们希望这个9在 0.75 ~ 1.0之间
                // 我希望他看向我
                float scaling = (float) delta.length();
                stack.mulPose(RenderUtils.getBillboardRotation(Display.BillboardConstraints.CENTER, 0, 0));
                stack.scale(0.002F * scaling, 0.002F * scaling, 1);
                VRender.getInstance()
                        .drawTextCameraCoord(
                                Component.literal("距离: %.1f".formatted(scaling)).getVisualOrderText(),
                                stack,
                                Vec3.ZERO,
                                VRender.createTextPositionFlag(0, 1),
                                Color.WHITE,
                                VRender.DEFAULT_TEXT);

                stack.popPose();
                Vec3 lastPos = snapshotPos;
                if (recordingPath != null) {
                    var lst = recordingPath.bp();
                    var size = lst.size();
                    for (var i = size - 1; i >= 0; --i) {
                        var bbb = lst.get(i);

                        Vec3 ppp = Vec3.atCenterOf(bbb);
                        RenderUtils.drawOutlinedBox(
                                event.context().stack(),
                                ppp.add(SNAPSHOT_RENDER_FROM),
                                ppp.add(SNAPSHOT_RENDER_TO),
                                Color.CYAN);
                        RenderUtils.drawLineVirtual(event.context().stack(), ppp, lastPos, Color.CYAN);
                        lastPos = ppp;
                        if (bbb.distToCenterSqr(feetPos) > MathUtils.s2(autoWriteDistance.get() * 2)) {
                            break;
                        }
                    }
                }
            }
            if (!currentSegSubPath.isEmpty()) {
                renderPathSegment(event.context().stack(), currentSegSubPath, Color.GREEN);
            }
        } finally {
            RenderUtils.stopDrawVirtual(event.context().stack());
        }
    }

    private void renderPathSegment(PoseStack matrices, List<BlockPos> path, Color color) {
        Vec3 lastPos = null;
        for (BlockPos pos : path) {
            Vec3 pointPos = Vec3.atCenterOf(pos);
            RenderUtils.drawOutlinedBox(
                    matrices, pointPos.add(SNAPSHOT_RENDER_FROM), pointPos.add(SNAPSHOT_RENDER_TO), color);
            if (lastPos != null) {
                RenderUtils.drawLineVirtual(matrices, lastPos, pointPos, color);
            }
            lastPos = pointPos;
            if (lastPos.distanceToSqr(mc.player.position()) > MathUtils.s2(autoWriteDistance.get() * 8)) {
                break;
            }
        }
    }

    private void startPath(String pathFile, FileStorage storage) {
        recordingStorage = storage;
        recordingPathFile = pathFile;
        recordingPath = new RecordPath(new ArrayList<>());
        recordingFlightStarted = false;
        recordingSnapshot = null;
        pauseRecord = false;
    }

    private void restartPath(String pathFile, FileStorage storage, List<BlockPos> blockPos) {
        recordingStorage = storage;
        recordingPathFile = pathFile;
        recordingPath = new RecordPath(new ArrayList<>(blockPos));

        recordingFlightStarted = true;
        recordingSnapshot = null;
        restartSnapshot();
    }

    private void endPath(FileStorage storage) {
        if (storage != null) {
            storage.markDeprecated(true);
        }
        recordingStorage = null;
        recordingPathFile = null;
        recordingPath = null;
        recordingFlightStarted = false;
        recordingSnapshot = null;
    }

    private int finishPath(String reason, CommandExecution context) {
        if (recordingStorage == null) {
            return 0;
        }
        FileStorage storage = recordingStorage;
        String pathFile = recordingPathFile;
        int savedSize = recordingPath == null ? 0 : recordingPath.bp().size();
        try {
            savedSize = recordingPath == null ? 0 : recordingPath.bp().size();
            writePath(storage, recordingPath == null ? new RecordPath(List.of()) : recordingPath);
            storage.write();
            if (context != null) {
                context.sendMessage("&a路径已保存: " + pathFile + "，原因: " + reason);
            }
        } catch (Throwable e) {
            Debug.info("PathManager failed to save path: " + pathFile);
            Debug.info(e);
            if (context != null) {
                context.sendMessage("&c路径保存失败: " + pathFile);
            }
        } finally {
            endPath(storage);
        }
        return savedSize;
    }

    private void startSnapshot(BlockPos pos) {
        if (recordingPath == null || pos == null) {
            return;
        }
        List<BlockPos> path = recordingPath.bp();
        if (path.isEmpty() || !path.getLast().equals(pos)) {
            path.add(pos.immutable());
        }
        //
        if (recordingStorage != null) {
            recordingStorage.write(RecordPath.CODEC, recordingPath);
        }
        recordingSnapshot = new RecordSnapshot(pos);
    }

    private void restartSnapshot() {
        if (recordingPath != null && !recordingPath.bp.isEmpty()) {
            BlockPos pos = recordingPath.bp().get(recordingPath.bp.size() - 1);
            recordingSnapshot = new RecordSnapshot(pos);
            recordingSnapshot.lastPosition = mc.player.blockPosition();
        } else {
            startSnapshot(mc.player.blockPosition());
        }
    }

    private void recordCurrentPosition(BlockPos current) {
        if (recordingSnapshot == null) {
            startSnapshot(current);
            return;
        }

        RecordSnapshot snapshot = recordingSnapshot;
        snapshot.tick();
        if (rollbackSnapshotIfNeeded(snapshot, current)) {
            if (recordingSnapshot != null) {
                recordingSnapshot.updateLastPosition(current);
            }
            return;
        }
        if (current.equals(snapshot.lastPosition())) {
            return;
        }
        if (shouldWriteBeforeCurrent(snapshot, current)) {
            BlockPos lastPosition = snapshot.lastPosition();
            startSnapshot(lastPosition);
            recordingSnapshot.updateLastPosition(current);
            return;
        }
        snapshot.updateLastPosition(current);
    }

    private boolean rollbackSnapshotIfNeeded(RecordSnapshot snapshot, BlockPos current) {
        if (recordingPath == null || recordingPath.bp().size() <= 1 || !snapshot.shouldRollback(current)) {
            return false;
        }
        List<BlockPos> path = recordingPath.bp();
        BlockPos previousPoint = path.get(path.size() - 2); // 回滚后会变成最后一个点
        if (!canSee(previousPoint, current)) {
            // 上一个点与当前位置不可见，不回滚（避免丢失关键转弯点）
            return false;
        }
        path.remove(path.size() - 1);
        recordingSnapshot = new RecordSnapshot(path.getLast());
        return true;
    }

    private boolean shouldWriteBeforeCurrent(RecordSnapshot snapshot, BlockPos current) {
        if (pauseRecord) {
            return false;
        }
        double writeDistance = autoWriteDistance.get();
        double disSqr = snapshot.snapshotPos().distSqr(current);
        if (disSqr < MathUtils.s2(writeDistance)) {
            return !canSee(snapshot.snapshotPos(), current);
        }
        return true;
    }

    private boolean canSee(BlockPos from, BlockPos to) {
        if (mc.level == null || mc.player == null) {
            return false;
        }
        if (from.distSqr(to) > MathUtils.s2(autoWriteDistance.get() + 10)) {
            return false;
        }
        Vec3[] fromCorners = makeBodyCorners(makeBodyBox(Vec3.atBottomCenterOf(from)));
        Vec3[] toCorners = makeBodyCorners(makeBodyBox(Vec3.atBottomCenterOf(to)));
        for (int i = 0; i < fromCorners.length; ++i) {
            if (!hasClearLine(fromCorners[i], toCorners[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean hasClearLine(Vec3 from, Vec3 to) {
        HitResult result =
                mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return result.getType() != HitResult.Type.BLOCK;
    }

    private AABB makeBodyBox(Vec3 bottomCenter) {
        return new AABB(
                bottomCenter.x - 0.4D,
                bottomCenter.y,
                bottomCenter.z - 0.4D,
                bottomCenter.x + 0.4D,
                bottomCenter.y + 1.9D,
                bottomCenter.z + 0.4D);
    }

    private Vec3[] makeBodyCorners(AABB box) {
        return new Vec3[] {
            new Vec3(box.minX, box.minY, box.minZ),
            new Vec3(box.maxX, box.minY, box.minZ),
            new Vec3(box.minX, box.maxY, box.minZ),
            new Vec3(box.maxX, box.maxY, box.minZ),
            new Vec3(box.minX, box.minY, box.maxZ),
            new Vec3(box.maxX, box.minY, box.maxZ),
            new Vec3(box.minX, box.maxY, box.maxZ),
            new Vec3(box.maxX, box.maxY, box.maxZ)
        };
    }

    private void writePath(FileStorage storage, RecordPath path) {
        DataResult<?> encoded = storage.write(RecordPath.CODEC, path);
        if (encoded.isError()) {
            throw new IllegalArgumentException(
                    encoded.error().map(error -> error.message()).orElse("未知编码错误"));
        }
    }

    private RecordPath readPath(FileStorage storage) {
        DataResult<RecordPath> decoded = storage.read(RecordPath.CODEC);
        if (decoded.isError()) {
            Debug.info("PathManager failed to decode path: "
                    + decoded.error().map(error -> error.message()).orElse("未知解码错误"));
            return null;
        }
        return decoded.result().orElse(null);
    }

    public List<BlockPos> getCurrentPath() {
        return currentPath == null ? List.of() : currentPath.remainingPoints();
    }

    private List<BlockPos> alignPathToNearest(List<BlockPos> path) {
        if (checkNull()) {
            return List.copyOf(path);
        }
        BlockPos current = mc.player.blockPosition();
        int nearest = 0;
        double minDistance = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); ++i) {
            double distance = path.get(i).distSqr(current);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = i;
            }
        }
        return List.copyOf(path.subList(nearest, path.size()));
    }

    private PathRerunContext createRerunContext(String pathFile, List<BlockPos> path, boolean cut) {
        return new PathRerunContext(pathFile, splitPathSegments(path, cut));
    }

    private List<PathSeg> splitPathSegments(List<BlockPos> path, boolean cut) {
        if (path.isEmpty()) {
            return List.of();
        }
        double maxDistanceSquared = MathUtils.s2(autoWriteDistance.get() + PATH_SEG_SPLIT_OFFSET);
        List<PathSeg> segments = new ArrayList<>();
        List<BlockPos> current = new ArrayList<>();
        BlockPos previous = null;
        for (BlockPos pos : path) {
            if (pos == null) {
                continue;
            }
            BlockPos immutable = pos.immutable();
            if (current.isEmpty()) {
                current.add(immutable);
                previous = immutable;
                continue;
            }
            if (previous.distSqr(immutable) > maxDistanceSquared) {
                segments.add(new PathSeg(current, cut));
                current = new ArrayList<>();
            }
            current.add(immutable);
            previous = immutable;
        }
        if (!current.isEmpty()) {
            segments.add(new PathSeg(current, cut));
        }
        return List.copyOf(segments);
    }

    private static String currentWorldKey() {
        return mc.level == null ? "" : mc.level.dimension().identifier().toString();
    }

    int lastUpdatedIndex = 0;

    public void onTickRunningBaritone(Event<LocalPlayer> event) {
        if (!rerunningBaritone || currentPath == null || checkNull()) {
            return;
        }
        if (!rerunMode.get().isIn(Mode.BARITONE)) {
            return;
        }
        if (!BaritoneHooks.getInstance().isEnabled()) {
            stopCurrentRunningBaritone();
            return;
        }
        //        if(!BaritoneHooks.getInstance().isElytraProcessing()){
        //            BaritoneHooks.getInstance().handleCommand("elytra");
        //            return;
        //        }
        PathSeg currentSeg = currentPath.currentSegment();
        if (currentSeg == null) {
            stopCurrentRunningBaritone();

            return;
        }
        BlockPos playerPos = event.context().blockPosition();
        if (!currentSeg.started()) {
            lastUpdatedIndex = 0;
            setBaritoneGoal(currentSeg.start());
            currentSeg.markAutoGoalIssued();
            if (currentSeg.start().distSqr(playerPos) < 10000) {
                currentSeg.updateIndex(playerPos);
                setBaritoneNetherPath(currentSeg.currentSubPath());
                setBaritoneGoal(currentSeg.end());
                currentSeg.markStarted();
            }
            return;
        } else {
            if (currentSeg.updateIndex(playerPos)) {
                setBaritoneNetherPath(currentSeg.currentSubPath());
                if (lastUpdatedIndex + 350 < currentSeg.currentIndex) {
                    lastUpdatedIndex = currentSeg.currentIndex;
                    setBaritoneGoal(currentSeg.end());
                }
            }
            if (currentSeg.tickStuck(playerPos) && rebuildCurrentSegmentFromStuckPoint(currentSeg)) {
                return;
            }
        }
        if (playerPos.distSqr(currentSeg.end()) <= 10000) {
            endBaritonePathOverride();
            currentPath.advanceSegment();
            var seg = currentPath.currentSegment();
            if (seg == null) {
                stopCurrentRunningBaritone();
                setBaritoneGoal(currentSeg.end());
                logI18N("message.module.path-manager.rerun-finished");
                MovTasks.getElytraFlight().enable.set(true);
            } else {
                setBaritoneGoal(seg.start());
            }
        }
    }

    public void onTickRunningBaritoneGoal(Event<LocalPlayer> event) {
        if (!rerunningBaritone || currentPath == null || checkNull()) {
            return;
        }
        if (!rerunMode.get().isIn(Mode.BARITONE_GOAL)) {
            return;
        }
        if (!BaritoneHooks.getInstance().isEnabled()) {
            stopCurrentRunningBaritone();
            return;
        }
        //        if(!BaritoneHooks.getInstance().isElytraProcessing()){
        //            BaritoneHooks.getInstance().handleCommand("elytra");
        //            return;
        //        }
        PathSeg currentSeg = currentPath.currentSegment();
        if (currentSeg == null) {
            stopCurrentRunningBaritone();
            return;
        }
        BlockPos playerPos = event.context().blockPosition();
        if (!currentSeg.started()) {
            setBaritoneGoal(currentSeg.start());
            currentSeg.markAutoGoalIssued();
            if (currentSeg.start().distSqr(playerPos) < 10000) {
                currentSeg.updateGoalIndex(playerPos, goalDistance.get());
                setBaritoneGoal(currentSeg.currentBlockPos());
                currentSeg.markStarted();
            }
            return;
        } else {
            if (playerPos.distSqr(currentSeg.currentBlockPos()) < 10000) {
                currentSeg.updateGoalIndex(playerPos, goalDistance.get());
                setBaritoneGoal(currentSeg.currentBlockPos());
            }
        }
        if (playerPos.distSqr(currentSeg.end()) <= 10000) {
            endBaritonePathOverride();
            currentPath.advanceSegment();
            var seg = currentPath.currentSegment();
            if (seg == null) {
                stopCurrentRunningBaritone();
                setBaritoneGoal(currentSeg.end());
                logI18N("message.module.path-manager.rerun-finished");
                MovTasks.getElytraFlight().enable.set(true);
            } else {
                setBaritoneGoal(seg.start());
            }
        }
    }

    private boolean rebuildCurrentSegmentFromStuckPoint(PathSeg currentSeg) {
        int targetIndex = findFarthestUnloadedIndex(currentSeg);
        if (targetIndex < 0) {
            currentSeg.resetStuckState();
            return false;
        }
        BlockPos target = currentSeg.points().get(targetIndex);
        endBaritonePathOverride();
        setBaritoneGoal(target);
        currentPath.replaceCurrentSegment(new PathSeg(
                currentSeg.points().subList(targetIndex, currentSeg.points().size()), currentSeg.cut));
        return true;
    }

    private int findFarthestUnloadedIndex(PathSeg currentSeg) {
        int currentIndex = currentSeg.currentIndex();
        int endIndex = Math.min(currentIndex + 20, currentSeg.points().size() - 1);
        for (int i = currentIndex; i <= endIndex; ++i) {
            if (!isChunkLoaded(currentSeg.points().get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isChunkLoaded(BlockPos pos) {
        return mc.level != null && WorldUtils.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private void stopCurrentRunningBaritone() {
        if (rerunningBaritone) {
            rerunningBaritone = false;
            endBaritonePathOverride();
            BaritoneHooks.getInstance().cancelBaritone();
            if (currentPath != null) {
                currentPath.resetRunningState();
            }
        }
    }
    // baritone related

    List<BlockPos> currentPathingSegment;

    private void setBaritoneNetherPath(List<BlockPos> path) {
        currentPathingSegment = prepareNetherPath(path);

        BaritoneHooks.getInstance().setBaritoneNetherPathSupplier(() -> {
            if (rerunningBaritone) {
                return currentPathingSegment;
            }
            return null;
        });
    }

    private List<BlockPos> prepareNetherPath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        int end = Math.min(path.size(), 400);
        List<BlockPos> adjusted = new ArrayList<>(end);
        for (BlockPos pos : path.subList(0, end)) {
            adjusted.add(adjustPathPointByEnvironment(pos));
        }
        return List.copyOf(adjusted);
    }

    private BlockPos adjustPathPointByEnvironment(BlockPos pos) {
        if (pos == null || mc.level == null) {
            return pos;
        }
        int above = firstNonAirDistance(pos, 1);
        int below = firstNonAirDistance(pos, -1);
        if (above > below && below < 3) {
            return pos.offset(0, above, 0).immutable();
        }
        if (below > above && above < 3) {
            return pos.offset(0, -below, 0).immutable();
        }
        return pos.immutable();
    }

    private int firstNonAirDistance(BlockPos pos, int direction) {
        for (int distance = 1; distance <= 3; ++distance) {
            BlockPos checkPos = pos.offset(0, direction * distance, 0);
            if (isLoadedNonAir(checkPos)) {
                return distance;
            }
        }
        return 0;
    }

    private boolean isLoadedNonAir(BlockPos pos) {
        if (!isChunkLoaded(pos)) {
            return false;
        }
        var state = mc.level.getBlockState(pos);
        return !state.isAir() && !state.liquid();
    }

    private void setBaritoneAutoGoal(BlockPos pos) {}

    private void setBaritoneGoal(BlockPos pos) {
        BaritoneHooks.getInstance().setBaritoneCurrentElytraDestination(pos);
    }

    private void endBaritonePathOverride() {
        currentPathingSegment = null;
        BaritoneHooks.getInstance().setBaritoneNetherPathSupplier(null);
    }

    public enum Mode implements ConfigEnum {
        BARITONE,
        BARITONE_GOAL,
        ELYTRA_FLIGHT;

        @Override
        public String getConfigEnumType() {
            return "path_manager_flight_mode";
        }
    }

    public static class RecordPath {
        private static final Codec<List<BlockPos>> POS_CODEC =
                Codec.LONG.xmap(BlockPos::of, BlockPos::asLong).listOf();

        public static final Codec<RecordPath> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        POS_CODEC.fieldOf("pos").forGetter(RecordPath::bp),
                        Codec.STRING.fieldOf("world").forGetter(RecordPath::world),
                        Codec.STRING.fieldOf("server").forGetter(RecordPath::server))
                .apply(instance, RecordPath::new));

        private final List<BlockPos> bp;
        private final String world;
        private final String server;

        public RecordPath(List<BlockPos> bp) {
            this(bp, currentWorldKey(), CommonUtils.getServerName());
        }

        public RecordPath(List<BlockPos> bp, String world, String server) {
            this.bp = new ArrayList<>();
            for (BlockPos pos : bp) {
                if (pos != null) {
                    this.bp.add(pos.immutable());
                }
            }
            this.world = world == null ? "" : world;
            this.server = server == null ? "" : server;
        }

        public List<BlockPos> bp() {
            return bp;
        }

        public String world() {
            return world;
        }

        public String server() {
            return server;
        }
    }

    private static class RecordSnapshot {
        private final BlockPos snapshotPos;
        private BlockPos lastPosition;
        private int ticks;

        private RecordSnapshot(BlockPos snapshotPos) {
            this.snapshotPos = snapshotPos.immutable();
            this.lastPosition = this.snapshotPos;
        }

        private void tick() {
            ++ticks;
        }

        private boolean shouldRollback(BlockPos current) {
            return ticks > SNAPSHOT_ROLLBACK_TICKS
                    && snapshotPos.distSqr(current) <= SNAPSHOT_ROLLBACK_DISTANCE_SQUARED;
        }

        private void updateLastPosition(BlockPos pos) {
            lastPosition = pos.immutable();
        }

        private BlockPos snapshotPos() {
            return snapshotPos;
        }

        private BlockPos lastPosition() {
            return lastPosition;
        }
    }

    private static class PathRerunContext {
        private final String pathFile;
        private final List<PathSeg> segments;
        private int currentIndex;

        private PathRerunContext(String pathFile, List<PathSeg> segments) {
            this.pathFile = pathFile == null ? "" : pathFile;
            this.segments = new ArrayList<>(segments);
        }

        private PathSeg currentSegment() {
            if (currentIndex >= segments.size()) {
                return null;
            }
            return segments.get(currentIndex);
        }

        private void replaceCurrentSegment(PathSeg segment) {
            if (segment != null && currentIndex < segments.size()) {
                segments.set(currentIndex, segment);
            }
        }

        private void advanceSegment() {
            PathSeg current = currentSegment();
            if (current != null) {
                current.resetRunningState();
            }
            ++currentIndex;
        }

        private int remainingSegments() {
            return Math.max(0, segments.size() - currentIndex);
        }

        private List<BlockPos> remainingPoints() {
            List<BlockPos> points = new ArrayList<>();
            for (int i = currentIndex; i < segments.size(); ++i) {
                points.addAll(segments.get(i).points());
            }
            return List.copyOf(points);
        }

        private void resetRunningState() {
            for (PathSeg seg : segments) {
                seg.resetRunningState();
            }
            currentIndex = 0;
        }
    }

    private static class PathSeg {
        private final List<BlockPos> points;
        private final BlockPos start;
        private final BlockPos end;
        private int currentIndex;
        private boolean autoGoalIssued;
        private boolean started;
        private BlockPos stuckReference;
        private int stuckTicks;
        private final boolean cut;

        private PathSeg(List<BlockPos> points, boolean cut) {
            this.cut = cut;
            List<BlockPos> immutablePoints = new ArrayList<>();
            BlockPos lastPos = null;
            for (BlockPos pos : points) {
                if (lastPos != null && cut) {
                    double distance = lastPos.distSqr(pos);
                    if (distance > 25) {
                        Vec3 vec3d1 = Vec3.atCenterOf(lastPos);
                        Vec3 vec3d2 = Vec3.atCenterOf(pos);
                        Vec3 delta = vec3d2.subtract(vec3d1);
                        double len = delta.length();
                        delta = delta.normalize();
                        int seq = (((int) len - 1) / 5) + 1;
                        for (var re = 1; re < seq; ++re) {
                            immutablePoints.add(BlockPos.containing(vec3d1.add(delta.scale(len * re / (double) seq))));
                        }
                    }
                }
                immutablePoints.add(pos.immutable());
                lastPos = pos;
            }
            this.points = List.copyOf(immutablePoints);
            this.start = this.points.getFirst();
            this.end = this.points.getLast();
        }

        private List<BlockPos> points() {
            return points;
        }

        private BlockPos start() {
            return start;
        }

        private BlockPos end() {
            return end;
        }

        private boolean started() {
            return started;
        }

        private int currentIndex() {
            return currentIndex;
        }

        private List<BlockPos> currentSubPath() {

            return List.copyOf(points.subList(Math.min(points.size() - 1, currentIndex + 1), points.size()));
        }

        private BlockPos currentBlockPos() {
            return points.get(currentIndex);
        }

        private boolean tickStuck(BlockPos playerPos) {
            if (playerPos == null) {
                return false;
            }
            if (stuckReference == null) {
                stuckReference = playerPos.immutable();
                stuckTicks = 0;
                return false;
            }
            if (stuckReference.distSqr(playerPos) > PATH_SEG_STUCK_DISTANCE_SQUARED) {
                stuckReference = playerPos.immutable();
                stuckTicks = 0;
                return false;
            }
            return ++stuckTicks > PATH_SEG_STUCK_TICKS;
        }

        private void resetStuckState() {
            stuckReference = null;
            stuckTicks = 0;
        }

        private boolean updateIndex(BlockPos playerPos) {
            if (playerPos == null || currentIndex >= points.size() - 1) {
                return false;
            }
            int lastIndex = currentIndex;

            int searchEnd = Math.min(points.size() - 1, currentIndex + PATH_SEG_PROGRESS_LOOKAHEAD);
            int bestIndex = searchEnd;
            double bestDistance = Double.MAX_VALUE;

            for (int i = currentIndex; i <= searchEnd; ++i) {
                //                if(mc.player.getPos().squaredDistanceTo(points.get(i).toCenterPos()) < 16){
                //                    currentIndex = i + 1;
                //                }
                double distance = points.get(i).distSqr(playerPos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }
            currentIndex = bestIndex;

            return currentIndex != lastIndex;
        }

        private boolean updateGoalIndex(BlockPos playerPos, double goalDistance) {
            if (playerPos == null || currentIndex >= points.size() - 1) {
                return false;
            }
            for (; currentIndex < points.size() - 1; ++currentIndex) {
                double distance = points.get(currentIndex).distSqr(playerPos);
                if (distance > MathUtils.s2(goalDistance)) {
                    break;
                }
            }
            return true;
        }

        private void markAutoGoalIssued() {
            autoGoalIssued = true;
        }

        private void markStarted() {
            autoGoalIssued = true;
            started = true;
            resetStuckState();
        }

        private void resetRunningState() {
            autoGoalIssued = false;
            started = false;
            currentIndex = 0;
            resetStuckState();
        }
    }
}
