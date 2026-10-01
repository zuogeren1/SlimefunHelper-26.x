package me.matl114.hacks.modules.move;

import com.mojang.datafixers.util.Pair;
import java.util.HashMap;
import java.util.Map;
import me.matl114.managers.command.MainCommand;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.WorldUtils;
import me.matl114.utils.commands.commandGroup.CommandContext;
import me.matl114.utils.commands.commandGroup.SubCommand;
import me.matl114.utils.commands.commandGroup.TreeSubCommand;
import me.matl114.utils.commands.params.ArgumentInputStream;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import me.matl114.utils.commands.params.api.CommandExecution;
import me.matl114.utils.commands.params.types.ExecutePos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public class TargetCommand extends BaseModule {
    public TargetCommand() {
        super("TargetCommand");
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerCommandBootstrap(this::bootStrapTargetCommand);
    }

    Map<String, Pair<Vec3, Float>> lastCachedPosition = new HashMap<>();

    public void bootStrapTargetCommand(MainCommand mainCommand) {
        TreeSubCommand main = mainCommand.mainBuilder().name("target").build();
        {
            main.subBuilder(SubCommand.treeBuilder())
                    .name("calculate")
                    .post(m -> m.subBuilder(SubCommand.taskBuilder())
                            .name("waypoint")
                            .helper("message.command.target.calculate.waypoint.help")
                            .arg(SimpleCommandArgs.argumentBuilder()
                                    .name("waypoint")
                                    .tabSupplier(WorldUtils::getWaypointNames)
                                    .build())
                            .post(s -> s.executor(CommandContext.execute(this::onCalculateWaypoint)))
                            .complete()
                            .subBuilder(SubCommand.taskBuilder())
                            .name("pos")
                            .helper("message.command.target.calculate.pos.help")
                            .arg(SimpleCommandArgs.argumentBuilder(MovTasks.TpaAndPosArgumentType::new)
                                    .name("target")
                                    .build())
                            .post(s -> s.executor(CommandContext.execute(this::onCalculatePosition)))
                            .complete()
                            .subBuilder(SubCommand.taskBuilder())
                            .name("chunk")
                            .helper("message.command.target.calculate.chunk.help")
                            .arg(SimpleCommandArgs.argumentBuilder()
                                    .name("z")
                                    .intValue()
                                    .build())
                            .arg(SimpleCommandArgs.argumentBuilder()
                                    .name("x")
                                    .intValue()
                                    .build())
                            .post(s -> s.executor(CommandContext.execute(this::onCalculateChunk)))
                            .complete())
                    .complete();
        }
        {
            main.subBuilder(SubCommand.treeBuilder())
                    .name("target")
                    .post(m -> m.subBuilder(SubCommand.taskBuilder())
                            .name("waypoint")
                            .helper("message.command.target.target.waypoint.help")
                            .arg(SimpleCommandArgs.argumentBuilder()
                                    .name("waypoint")
                                    .tabSupplier(WorldUtils::getWaypointNames)
                                    .build())
                            .post(s -> s.executor(CommandContext.execute(this::onTargetWaypoint)))
                            .complete()
                            .subBuilder(SubCommand.taskBuilder())
                            .name("pos")
                            .helper("message.command.target.target.pos.help")
                            .arg(SimpleCommandArgs.argumentBuilder(MovTasks.TpaAndPosArgumentType::new)
                                    .name("target")
                                    .build())
                            .post(s -> s.executor(CommandContext.execute(this::onTargetPosition)))
                            .complete()
                            .subBuilder(SubCommand.taskBuilder())
                            .name("chunk")
                            .helper("message.command.target.target.chunk.help")
                            .arg(SimpleCommandArgs.argumentBuilder()
                                    .name("z")
                                    .intValue()
                                    .build())
                            .arg(SimpleCommandArgs.argumentBuilder()
                                    .name("x")
                                    .intValue()
                                    .build())
                            .post(s -> s.executor(CommandContext.execute(this::onTargetChunk)))
                            .complete())
                    .complete();
        }
    }

    public void onCalculateWaypoint(CommandExecution p, ArgumentInputStream re) {
        String waypoint = re.nextNonnullString();
        WorldUtils.Waypoint waypointIns = WorldUtils.getWaypoint(waypoint);
        if (waypointIns == null) {
            p.sendMessage(Component.literal("找不到这个坐标点").withStyle(ChatFormatting.RED));
        } else {
            WorldUtils.WaypointData waypointData = waypointIns.getData();
            if (waypointData instanceof WorldUtils.WaypointData.Direction dir) {
                if (lastCachedPosition.containsKey(waypointIns.getDisplayName())) {
                    Pair<Vec3, Float> position = lastCachedPosition.remove(waypointIns.getDisplayName());
                    Vec3 pos = position.getFirst();
                    float direction = position.getSecond();
                    Vec3 currentPos = mc.player.position();
                    float currentDirection = dir.azimuth();
                    Vec2 vec2f = intersectRays(pos, direction, currentPos, currentDirection);
                    if (vec2f != null) {
                        p.sendMessage(Component.literal("计算当前坐标点位置大致位于: ")
                                .append(ChatUtils.getDisplayedLocation(new Vec3(vec2f.x, 64, vec2f.y))));
                    } else {
                        p.sendMessage(Component.literal("当前位置无法正确推断,请重新选去两点"));
                    }
                } else {
                    lastCachedPosition.put(waypointIns.getDisplayName(), Pair.of(mc.player.position(), dir.azimuth()));
                    p.sendMessage(Component.literal("记录当前测算位置中,请移动若干位置后重新输入指令").withStyle(ChatFormatting.GREEN));
                }
            } else {
                p.sendMessage(Component.literal("当前坐标点已有确定坐标").withStyle(ChatFormatting.GREEN));
                if (waypointData instanceof WorldUtils.WaypointData.Pos pos) {
                    p.sendMessage(Component.literal("Pos: ").append(ChatUtils.getDisplayedLocation(pos.pos())));
                } else if (waypointData instanceof WorldUtils.WaypointData.Chunk chunk) {
                    ChunkPos chunkPos = chunk.pos();
                    Vec3 pos = new Vec3(chunkPos.x << 4, 64, chunkPos.z << 4);
                    p.sendMessage(Component.literal("Pos: ").append(ChatUtils.getDisplayedLocation(pos)));
                }
            }
        }
    }

    public void onCalculatePosition(CommandExecution p, ArgumentInputStream re) {
        ExecutePos pos = re.nextArg();
        if (pos != null) {
            Vector3d vector3d = pos.getPosition(p);
            Vec3 vec3d = (new Vec3(vector3d.x, vector3d.y, vector3d.z));
            p.sendMessage(Component.literal("Pos: ").append(ChatUtils.getDisplayedLocation(vec3d)));
            p.sendMessage(Component.literal("NetherPos: ")
                    .append(ChatUtils.getDisplayedLocation(vec3d.scale((double) 1 / 8))));
            p.sendMessage(Component.literal("WorldPos: ").append(ChatUtils.getDisplayedLocation(vec3d.scale(8))));
            BlockPos blockPos = BlockPos.containing(vec3d);
            p.sendMessage(Component.literal("ChunkPos: ")
                    .append(ChatUtils.getDisplayedLocation(blockPos.getX() >> 4, blockPos.getZ() >> 4)));
        } else {
            p.sendMessage("输入了无效坐标!");
        }
    }

    public void onCalculateChunk(CommandExecution p, ArgumentInputStream re) {
        int x = re.nextInt();
        int z = re.nextInt();
        p.sendMessage(Component.literal("ChunkPos: %d %d".formatted(x, z)));
        p.sendMessage(Component.literal("Pos: ").append(ChatUtils.getDisplayedLocation(x << 4, z << 4)));
    }

    public void onTargetWaypoint(CommandExecution p, ArgumentInputStream re) {
        String waypoint = re.nextNonnullString();
        WorldUtils.Waypoint waypointIns = WorldUtils.getWaypoint(waypoint);
        if (waypointIns == null) {
            p.sendMessage(Component.literal("找不到这个坐标点").withStyle(ChatFormatting.RED));
        } else {
            var waypointData = waypointIns.getData();
            if (waypointData instanceof WorldUtils.WaypointData.Pos pos) {
                Vec3 rotate = pos.pos().subtract(mc.player.getEyePosition()).normalize();
                PlayerStateManager.setPlayerRotationSafe(mc.player, rotate);
            } else if (waypointData instanceof WorldUtils.WaypointData.Chunk chunk) {
                ChunkPos chunkPos = chunk.pos();
                Vec2 rotate = new Vec2(
                        (float) ((chunkPos.x << 4) - mc.player.getX()), (float) ((chunkPos.z << 4) - mc.player.getZ()));
                PlayerStateManager.setPlayerYawSafe(mc.player, rotate);
            } else if (waypointData instanceof WorldUtils.WaypointData.Direction direction) {
                PlayerStateManager.setPlayerYawSafe(mc.player, (float) direction.azimuth() * 57.29578f);
            }
        }
    }

    public void onTargetPosition(CommandExecution p, ArgumentInputStream re) {
        ExecutePos pos = re.nextArg();
        if (pos != null) {
            Vector3d vector3d = pos.getPosition(p);
            Vec3 target = new Vec3(
                            vector3d.x - mc.player.getX(), vector3d.y - mc.player.getY(), vector3d.z - mc.player.getZ())
                    .normalize();
            PlayerStateManager.setPlayerRotationSafe(mc.player, target);
        } else {
            p.sendMessage("输入了无效坐标!");
        }
    }

    public void onTargetChunk(CommandExecution p, ArgumentInputStream re) {
        int x = re.nextInt();
        int z = re.nextInt();
        Vec2 rotate = new Vec2((float) ((x << 4) - mc.player.getX()), (float) ((z << 4) - mc.player.getZ()));
        PlayerStateManager.setPlayerYawSafe(mc.player, rotate);
    }

    public static Vec2 intersectRays(Vec3 posA, float azimuthA, Vec3 posB, float azimuthB) {
        double x1 = posA.x, z1 = posA.z;
        double x2 = posB.x, z2 = posB.z;
        double sinA = Math.sin(azimuthA);
        double cosA = Math.cos(azimuthA);
        double sinB = Math.sin(azimuthB);
        double cosB = Math.cos(azimuthB);

        double d1x = sinA;
        double d1z = -cosA;
        double d2x = sinB;
        double d2z = -cosB;

        double dx = x2 - x1;
        double dz = z2 - z1;

        double det = d1x * d2z - d1z * d2x;
        if (det == 0) {
            return null; // 平行，无交点
        }

        double t = (dx * d2z - dz * d2x) / det;

        double ix = x1 + t * d1x;
        double iz = z1 + t * d1z;
        return new Vec2((float) ix, (float) iz);
    }
}
