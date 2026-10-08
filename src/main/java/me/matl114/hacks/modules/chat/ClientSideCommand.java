package me.matl114.hacks.modules.chat;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.ResultConsumer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.utils.Debug;
import me.matl114.utils.ItemStackUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;

public class ClientSideCommand extends BaseModule {
    public final ModulePath clientSideCommand = makePath(Configs.CHAT_CONFIG, "client-side-command");

    public ClientSideCommand() {
        super("ClientSideCommand");
        bindFlag(enable);
    }

    public final FlagRef enable =
            flagBuilder(clientSideCommand.add("client-side-command-override")).build();

    public final FlagRef enableGive =
            flagBuilder(clientSideCommand.add("client-side-give")).build();

    private static final Predicate<SharedSuggestionProvider> requirement = (val) -> true;
    private static final Command<SharedSuggestionProvider> success = (val) -> Command.SINGLE_SUCCESS;

    private <T extends SharedSuggestionProvider> void addOurCommandNodesInRoot(RootCommandNode<T> node) {
        // try add deop command
        // fix: plugin give commands
        if (enableGive.get()) {
            CommandNode<T> give = node.getChild("minecraft:give");
            LiteralCommandNode<T> giveCommand;
            if (give == null) {
                giveCommand =
                        new LiteralCommandNode<>("minecraft:give", null, (Predicate<T>) requirement, null, null, false);
                node.addChild(giveCommand);
            } else {
                giveCommand = (LiteralCommandNode<T>) give;
            }
            if (node.getChild("give") == null) {
                LiteralCommandNode<T> mcGiveCommand =
                        new LiteralCommandNode<>("give", null, (Predicate<T>) requirement, giveCommand, null, false);
                node.addChild(mcGiveCommand);
            }
            if (give == null) {
                CommandBuildContext commandRegistryAccess =
                        CommandBuildContext.simple(ItemStackUtils.delegate(), FeatureFlags.DEFAULT_FLAGS);

                ArgumentCommandNode<T, EntitySelector> targetArgument = new ArgumentCommandNode<>(
                        "targets",
                        EntityArgument.players(),
                        null,
                        (Predicate<T>) requirement,
                        null,
                        null,
                        false,
                        // use default because if "minecraft:give" node is absent, then we definitely have no permission
                        // of requesting this
                        null);
                giveCommand.addChild(targetArgument);
                ArgumentCommandNode<T, ItemInput> itemArgument = new ArgumentCommandNode<>(
                        "item",
                        ItemArgument.item(commandRegistryAccess),
                        (Command<T>) success,
                        (Predicate<T>) requirement,
                        null,
                        null,
                        false,
                        null);
                targetArgument.addChild(itemArgument);
                ArgumentCommandNode<T, Integer> countAmount = new ArgumentCommandNode<>(
                        "count",
                        IntegerArgumentType.integer(1),
                        (Command<T>) success,
                        (Predicate<T>) requirement,
                        null,
                        null,
                        false,
                        null);
                itemArgument.addChild(countAmount);
            }
        }
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundCommandsPacket.class),
                (Consumer<Event<ClientboundCommandsPacket>>) this::onClientCommandReload);
        registerListener(Listener.getChatSend(), this::onCommandSend, 1);
    }

    private <T extends SharedSuggestionProvider> void onClientCommandReload(Event<ClientboundCommandsPacket> reload) {
        // 数据包处理抛异常时事件系统仍会派发“处理完”事件（finally 里发），那时客户端往往已经断开，
        // connection 为 null；这里不能再抛，否则会顶掉原始异常、让真正的错误从日志里消失。
        if (mc.getConnection() == null) {
            return;
        }
        CommandDispatcher<T> clientTree =
                (CommandDispatcher<T>) mc.getConnection().getCommands();
        RootCommandNode<T> root = clientTree.getRoot();
        if (root != null && enable.get()) {
            addOurCommandNodesInRoot(root);
        }
    }

    public List<String> supportedCommand = List.of("give", "minecraft:give");

    public void onCommandSend(Event<String> commandEvent) {
        String command = commandEvent.context();
        if (enable.get() && command.startsWith("/")) {
            command = command.substring(1);
            if (supportedCommand.stream().anyMatch(command::startsWith)) {

                if (dispatchVanillaCommand(command)) {
                    commandEvent.cancel();
                    return;
                }
            }
        }
    }

    private static ResultConsumer<ClientSuggestionProvider> consumer = (c, s, r) -> {};

    private boolean dispatchVanillaCommand(String command) {
        // Debug.info(command);
        if (mc.player == null) return false;
        mc.player.setPermissions(PermissionSet.ALL_PERMISSIONS);
        try {
            ParseResults<ClientSuggestionProvider> parse =
                    (ParseResults) mc.getConnection().getCommands().parse(command, (ClientSuggestionProvider)
                            mc.getConnection().getSuggestionsProvider());
            if (parse.getReader().canRead()) {
                if (parse.getExceptions().size() == 1) {
                    throw parse.getExceptions().values().iterator().next();
                } else if (parse.getContext().getRange().isEmpty()) {
                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
                            .dispatcherUnknownCommand()
                            .createWithContext(parse.getReader());
                } else {
                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
                            .dispatcherUnknownArgument()
                            .createWithContext(parse.getReader());
                }
            }

            final String commandStr = parse.getReader().getString();
            final CommandContextBuilder<ClientSuggestionProvider> originalBuilder = parse.getContext();
            // flatten this
            List<CommandContextBuilder<ClientSuggestionProvider>> modifiers = new ArrayList<>();
            CommandContextBuilder<ClientSuggestionProvider> contextData = originalBuilder;
            while (true) {
                CommandContextBuilder<ClientSuggestionProvider> child = contextData.getChild();
                if (child == null) {
                    if (contextData.getCommand() == null) {
                        consumer.onCommandComplete(originalBuilder.build(commandStr), false, 0);
                        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
                                .dispatcherUnknownCommand()
                                .createWithContext(parse.getReader());
                    }
                    break;
                }
                modifiers.add(contextData);
                contextData = child;
            }
            Map<String, ParsedArgument<ClientSuggestionProvider, ?>> argsMap = contextData.getArguments();
            if (commandStr.startsWith("give") || commandStr.startsWith("minecraft:give")) {
                return handleClientSideGiveCommand(argsMap, command);
            }
        } catch (CommandSyntaxException e) {
            Debug.chat(getErrorMessage(e));
        } catch (Throwable e) {
            Debug.chat(Component.literal("Internal Error!").withStyle(ChatFormatting.RED), e);
        }
        return false;
    }

    private boolean handleClientSideGiveCommand(
            Map<String, ParsedArgument<ClientSuggestionProvider, ?>> argsMap, String command)
            throws CommandSyntaxException {
        if (enableGive.get()) {
            if (mc.gameMode.getPlayerMode().isCreative()) {
                Debug.chat(Component.literal("尝试在客户端执行give指令").withStyle(ChatFormatting.GREEN));
                ParsedArgument<ClientSuggestionProvider, ?> entityArgument = argsMap.get("targets");
                EntitySelector entitySelector = (EntitySelector) entityArgument.getResult();
                StringRange range = entityArgument.getRange();
                if (entitySelector.isSelfSelector()
                        || Objects.equals(
                                mc.player.getScoreboardName(), command.substring(range.getStart(), range.getEnd()))) {
                    ItemInput itemStack = (ItemInput) argsMap.get("item").getResult();
                    int count = argsMap.containsKey("count")
                            ? (Integer) argsMap.get("count").getResult()
                            : 1;
                    // 1.21.11 是 createStack(count, false)，第二参是"是否做超堆叠校验"，
                    // 传 false = 放行。26.2 的 createItemStack 无条件校验且 2 参重载已不存在，
                    // 超堆叠会抛 CommandSyntaxException → 命令被转发给服务端（行为完全不同）。
                    // 这里捕获后改为直接构造，恢复旧版"静默生成"的语义。
                    ItemStack itemStackToGive;
                    try {
                        itemStackToGive = itemStack.createItemStack(count);
                    } catch (Exception e) {
                        itemStackToGive = new ItemStack(itemStack.item(), count, itemStack.components());
                    }
                    InvTasks.creativeGive(itemStackToGive, count);
                    Debug.chat(Component.literal("命令执行成功！").withStyle(ChatFormatting.GREEN));
                    return true;
                } else {
                    Debug.chat(Component.literal("你选中了其他生物,指令转向服务端执行!").withStyle(ChatFormatting.YELLOW));
                    return false;
                }
            } else {
                Debug.chat(Component.literal("你启用了客户端指令的功能,但是你并不是创造模式!").withStyle(ChatFormatting.YELLOW));
                return false;
            }
        } else {
            Debug.chat(Component.literal("尝试在客户端执行give指令,但是你没有启用客户端give指令").withStyle(ChatFormatting.RED));
            return false;
        }
    }

    private static Component getErrorMessage(CommandSyntaxException e) {
        Component message = ComponentUtils.fromMessage(e.getRawMessage());
        String context = e.getContext();

        return context != null
                ? Component.translatable("command.context.parse_error", message, e.getCursor(), context)
                : message;
    }
}
