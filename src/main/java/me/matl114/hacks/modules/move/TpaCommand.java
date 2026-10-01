package me.matl114.hacks.modules.move;

import java.awt.*;
import java.util.List;
import me.matl114.managers.command.MainCommand;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.commands.commandGroup.SubCommand;
import me.matl114.utils.commands.commandGroup.TreeSubCommand;
import me.matl114.utils.commands.params.ArgumentInputStream;
import me.matl114.utils.commands.params.ArgumentReader;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import me.matl114.utils.commands.params.api.CommandExecution;
import me.matl114.utils.commands.params.impl.DispatchArgumentType;
import me.matl114.utils.commands.params.impl.PosArgumentType;
import me.matl114.utils.commands.params.types.ExecutePos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public class TpaCommand extends BaseModule {
    public TpaCommand() {
        super("TpaCommand");
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerCommandBootstrap(this::bootStrapTpaCommand);
    }

    public void bootStrapTpaCommand(MainCommand mainCommand) {
        TreeSubCommand main = mainCommand.mainBuilder().name("tpa").build();
        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("tp")
                    .helper("message.command.tpa.tp.help")
                    .arg(SimpleCommandArgs.argumentBuilder(PosArgumentType::new)
                            .name("position")
                            .build())
                    .post(e -> e.executor(this::onTp))
                    .complete();
        }
        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("mark")
                    .helper("message.command.tpa.mark.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("type")
                            .select(List.of("player", "camera", "this", "pos", "target", "cross", "clear"), "camera")
                            .build())
                    .arg(new DispatchArgumentType<Object>("extra")
                            .registerArgumentDispatcher(
                                    0,
                                    "pos",
                                    SimpleCommandArgs.argumentBuilder(PosArgumentType::new)
                                            .name("dispatch_pos")
                                            .build())
                            .registerArgumentDispatcher(
                                    0,
                                    "target",
                                    SimpleCommandArgs.argumentBuilder(MovTasks.TpaArgumentType::new)
                                            .name("dispatch_tpa")
                                            .build())
                            .registerArgumentDispatcher(
                                    0,
                                    "player",
                                    SimpleCommandArgs.argumentBuilder()
                                            .name("dispatch_player")
                                            .tabSupplier(() -> EntityUtils.getWorldPlayerNames(false))
                                            .build())
                            .registerDispatcher(
                                    (p, args) -> true,
                                    SimpleCommandArgs.argumentBuilder()
                                            .name("dispatch_default")
                                            .build()))
                    .post(e -> e.executor(this::onMark))
                    .complete();
        }
        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("tpa")
                    .helper("message.command.tpa.tpa.help")
                    .arg(SimpleCommandArgs.argumentBuilder(MovTasks.TpaArgumentType::new)
                            .name("tpa_target")
                            .build())
                    .post(e -> e.executor(this::onTpa))
                    .complete();
        }
    }

    public boolean onTp(CommandExecution p, ArgumentInputStream re, ArgumentReader reader) {
        ExecutePos executePos = re.nextArg();
        if (executePos != null) {
            Vector3d vector3d = executePos.getPosition(p);
            onTpa(new Vec3(vector3d.x, vector3d.y, vector3d.z));
        } else {
            p.sendMessage("输入了无效坐标!");
        }
        return true;
    }

    public boolean onTpa(CommandExecution var1, ArgumentInputStream streamArgs, ArgumentReader argsReader) {
        ExecutePos pos = streamArgs.nextArg();
        if (pos != null) {
            Vector3d vector3d = pos.getPosition(var1);
            onTpa(new Vec3(vector3d.x, vector3d.y, vector3d.z));
        } else {
            var1.sendMessage("输入了无效目标位置!");
        }
        return true;
    }

    public void onTpa(Vec3 pos) {
        MovTasks.executeTp(pos, 320, true, true);
    }

    public boolean onMark(CommandExecution var1, ArgumentInputStream re, ArgumentReader reader) {
        String type = re.nextNonnull();
        Vec3 pos;
        Player sender = var1.getExecutorPlayer();
        switch (type) {
            case "this" -> pos = sender.position();
            case "camera" -> pos = RenderUtils.getCameraEntityPos();
            case "cross" -> pos = mc.hitResult.getLocation();
            case "player" -> {
                String var = re.nextNonnull();
                Entity player = EntityUtils.getPlayerByName(var);
                if (player != null) {
                    pos = player.position();
                } else {
                    var1.sendMessage(Component.literal("找不到实体或者玩家: " + var).withStyle(ChatFormatting.RED));
                    return true;
                }
            }
            case "pos" -> {
                ExecutePos executePos = re.nextArg();
                if (executePos != null) {
                    var vcd3 = executePos.getPosition(var1);
                    pos = new Vec3(vcd3.x, vcd3.y, vcd3.z);
                } else {
                    var1.sendMessage(Component.literal("无效的坐标").withStyle(ChatFormatting.RED));
                    return true;
                }
            }
            case "target" -> {
                ExecutePos executePos = re.nextArg();
                if (executePos != null) {
                    var vcd3 = executePos.getPosition(var1);
                    pos = new Vec3(vcd3.x, vcd3.y, vcd3.z);
                } else {
                    var1.sendMessage(Component.literal("无效的特殊位置").withStyle(ChatFormatting.RED));
                    return true;
                }
            }
            case "clear" -> {
                MovTasks.MARK = null;
                return true;
            }
            default -> {
                var1.sendMessage(Component.literal("不存在的mark类型: " + type).withStyle(ChatFormatting.RED));
                return true;
            }
        }
        MovTasks.MARK = pos;
        Debug.chat("标记成功: ", ChatUtils.getDisplayedLocationDouble(pos));
        RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                        new RenderTasks.BoxObject(sender.dimensions.makeBoundingBox(MovTasks.MARK), Color.GREEN))
                .setAutoStop(() -> MovTasks.MARK != pos));
        return true;
    }
}
