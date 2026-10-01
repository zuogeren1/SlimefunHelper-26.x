package me.matl114.hacks;

import me.matl114.utils.ClientUtils;

import com.google.common.base.Predicates;
import com.mojang.brigadier.tree.CommandNode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.Getter;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.managers.command.MainCommand;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.gui.complex.invcache.InventoryViewScreen;
import me.matl114.hacks.api.*;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.chat.*;
import me.matl114.hacks.modules.inv.ChestHistory;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.modules.survival.SeedOre;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.utils.*;
import me.matl114.utils.commands.commandGroup.*;
import me.matl114.utils.commands.params.ArgumentInputStream;
import me.matl114.utils.commands.params.ArgumentReader;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import me.matl114.utils.commands.params.api.CommandExecution;
import me.matl114.utils.inventory.ItemStackSample;
import me.matl114.utils.tasks.LimitedSpeedExecutor;
import me.matl114.versioned.api.VEntity;
import me.matl114.versioned.api.VRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.TextComponentTagVisitor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.apache.commons.lang3.mutable.MutableInt;
import me.matl114.managers.config.DoubleRef;
import me.matl114.hacks.utils.EntityUtils;

public class ChatTasks {
    public static void init() {}

    @Getter
    @ApiMethod
    public static final ModuleGroup moduleManager = new ModuleGroup("Chat");

    @Getter
    private static ChatExtra chatExtra;

    @Getter
    private static ChatTools chatTools;

    @Getter
    private static ClientSideCommand clientSideCommand;

    @Getter
    private static ChatCombine chatCombine;

    @Getter
    private static InGuiChatBox inGuiChatBox;

    @Getter
    private static EncryptChat encryptChat;

    @Getter
    private static PlayerChat playerChat;

    @Getter
    private static ChatSpamFix chatSpamFix;

    private static void initModules(ModuleManager m) {
        chatExtra = new ChatExtra().register(m);

        chatTools = new ChatTools().register(m);

        clientSideCommand = new ClientSideCommand().register(m);

        chatCombine = new ChatCombine().register(m);
        inGuiChatBox = new InGuiChatBox().register(m);

        encryptChat = new EncryptChat().register(m);

        playerChat = new PlayerChat().register(m);

        chatSpamFix = new ChatSpamFix().register(m);
    }

    static {
        moduleManager.registerFactories(ChatTasks::initModules);
        HackModules.registerModuleGroup(moduleManager);
    }
    // ========================================== utilities ========================================
    private static final Minecraft mc = Minecraft.getInstance();

    // modified from @ChatScreen.class
    public static void sayMessage(String chatText, boolean addToHistory) {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.connection != null) {
            chatText = getChatExtra().normalizeSendText(chatText);
            // in world
            if (addToHistory) {
                ClientUtils.getChat().addRecentChat(chatText);
            }
            if (chatText.startsWith("/")) {
                Minecraft.getInstance().player.connection.sendCommand(chatText.substring(1));
            } else {
                Minecraft.getInstance().player.connection.sendChat(chatText);
            }
        }
    }

    @Getter
    private static final LimitedSpeedExecutor chatExecutor = new LimitedSpeedExecutor(new DoubleRef(5));

    public static void sendDelayChatMessage(Component text) {
        chatExecutor.addDelayedExecuteTask(() -> ClientUtils.getChat(mc).addClientSystemMessage(text));
    }

    static {
        Tasks.registerGameTask(player -> {
            chatExecutor.reset();
        });
    }

    // ====================================== client commands ========================================

    public static class SlimefunHelperCommand extends AbstractMainCommand {
        TreeSubCommand main = mainBuilder().name("").build();

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("reload")
                    .helper("message.command.sfh.reload.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("what")
                            .select(List.of("command", "module", "all"), "command")
                            .build())
                    .post(e -> e.executor(CommandContext.run(SlimefunHelperCommand.this::onReload)))
                    .complete();
        }

        public void onReload(ArgumentInputStream args) {
            var re = args.nextNonnullString();
            switch (re) {
                case "command" -> Tasks.scheduleDelayed(MainCommand::reloadCommand, 1);
                // case "vanilla" -> Tasks.scheduleDelayed(ChatTasks::reloadVanillaClientCommand, 1);
                case "module" -> {
                    CompletableFuture.runAsync(() -> mc.execute(HackModules::reloadModuleGroups));
                }
                case "all" -> {
                    CompletableFuture.runAsync(() -> mc.execute(() -> {
                        HackModules.reloadModuleGroups();
                        MainCommand.reloadCommand();
                    }));
                }
                default -> Debug.chat("不支持的参数类型: " + re);
            }
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("reset")
                    .helper("message.command.sfh.reset.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("what")
                            .select(List.of("clickgui"))
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onReset)))
                    .complete();
        }

        public void onReset(ArgumentInputStream args) {
            var re = args.nextNonnullString();
            switch (re) {
                case "clickgui" -> Tasks.scheduleDelayed(MainTasks.getClickGui()::resetGui, 1);
            }
        }

        List<String> pageType =
                List.of("guide", "rtype", "vanilla", "saved", "itemedit", "invcache", "config", "scanner", "clickgui");

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("openmenu")
                    .helper("message.command.sfh.openmenu.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("page")
                            .select(pageType, "guide")
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onOpenMenu)))
                    .complete();
        }

        public void onOpenMenu(ArgumentInputStream s) {
            switch (s.nextSelect(pageType)) {
                case "rtype" -> Tasks.scheduleDelayed(SlimefunTasks.getSlimefunGuide()::openCraftTypeMenu, 1);
                case "vanilla" -> Tasks.scheduleDelayed(SlimefunTasks.getSlimefunGuide()::openVanillaRecipesMenu, 1);
                case "saved" -> Tasks.scheduleDelayed(SlimefunTasks.getSlimefunGuide()::openSaveItemMenu, 1);
                case "itemedit" -> Tasks.scheduleDelayed(InvTasks::openEditorForPlayer, 1);
                case "invcache" -> Tasks.scheduleDelayed(InvTasks::openInventoryCacheScreen, 1);
                case "config" -> Tasks.scheduleDelayed(MainTasks::openConfigNewStyleScreen, 1);
                case "scanner" -> Tasks.scheduleDelayed(ExtraTasks.getServerScanner()::openScannerScreen, 1);
                case "clickgui" -> Tasks.scheduleDelayed(MainTasks.getClickGui()::openClickGui, 1);
                default -> Tasks.scheduleDelayed(SlimefunTasks.getSlimefunGuide()::openMainGuideMenu, 1);
            }
            Debug.chat(Component.literal("成功打开界面").withStyle(ChatFormatting.GREEN));
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("specialtask")
                    .helper("message.command.sfh.task.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("taskid")
                            .tabSupplier(() -> MainTasks.getSpecialTaskName().stream())
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onTask)))
                    .complete();
        }

        public boolean onTask(Player player, ArgumentInputStream s, ArgumentReader reader) {

            String val = s.nextNonnull();
            String[] extraArg = reader.getRemainingArgs();

            try {
                MainTasks.runSpecialTask(val, extraArg);
            } catch (Throwable e) {
                Debug.chat("运行Task出现错误!:", e.getMessage());
                Debug.info(e);
            }
            return true;
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("asyncspecialtask")
                    .helper("message.command.sfh.asynctask.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("taskid")
                            .tabSupplier(() -> MainTasks.getSpecialTaskName().stream())
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onAsyncTask)))
                    .complete();
        }

        public boolean onAsyncTask(Player player, ArgumentInputStream s, ArgumentReader reader) {
            String val = s.nextNonnull();
            String[] extraArg = reader.getRemainingArgs();
            CompletableFuture.runAsync(() -> {
                try {
                    MainTasks.runSpecialTask(val, extraArg);
                } catch (Throwable e) {
                    Debug.chat("运行Task出现错误!:", e.getMessage());
                    Debug.info(e);
                }
            });
            return true;
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("registry")
                    .helper("message.command.sfh.registry.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("id")
                            .tabSupplier(() -> ItemStackUtils.registry()
                                    .registries()
                                    .map(entry -> entry.key().identifier())
                                    .map(i -> "minecraft".equals(i.getNamespace()) ? i.getPath() : i.toString()))
                            .build())
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("filter")
                            .select("<namespace_filter>:<path_filter>")
                            .defaultValue("")
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onListRegistry)))
                    .complete();
        }

        public void onListRegistry(ArgumentInputStream re) {
            Identifier identifier = Identifier.tryParse(re.nextNonnull());
            ResourceKey registryKey = ResourceKey.createRegistryKey(identifier);
            Registry result =
                    (Registry) ItemStackUtils.registry().lookup(registryKey).orElse(null);
            if (result != null) {
                String filter = re.nextNonnull();
                Debug.chat(Component.literal(identifier.toString() + "所拥有的注册项:").withStyle(ChatFormatting.GREEN));
                Identifier filterId = Identifier.tryParse(filter);
                boolean namespace = filter.contains(":");
                for (var id : result.registryKeySet()) {
                    Identifier identifier1 = ((ResourceKey) id).identifier();
                    String val = identifier1.getPath();
                    if (filterId == null
                            || (val.contains(filterId.getPath())
                                    && (!namespace || identifier1.getNamespace().contains(filterId.getNamespace())))) {
                        Debug.chat(identifier1);
                    }
                }
            } else {
                Debug.chat(Component.literal("不存在的注册表: " + identifier).withStyle(ChatFormatting.RED));
            }
        }

        List<String> resourceTypes = List.of("world", "command", "seed", "plugins", "version");

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("resource")
                    .helper("message.command.sfh.resource.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("id")
                            .select(resourceTypes)
                            .build())
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("filter")
                            .select("<namespace_filter>:<path_filter>")
                            .defaultValue("")
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onResource)))
                    .complete();
        }

        public void onResource(ArgumentInputStream re) {
            String val = re.nextSelect(resourceTypes);
            String filter = re.nextNonnull();
            Identifier filterId = Identifier.tryParse(filter);
            boolean namespace = filter.contains(":");
            List datas = new ArrayList<>();
            switch (val) {
                case "world" -> {
                    datas = mc.getConnection().levels().stream()
                            .map(ResourceKey::identifier)
                            .filter(u -> filterId == null
                                    || (u.getPath().contains(filterId.getPath())
                                            && (!namespace || u.getNamespace().contains(filterId.getNamespace()))))
                            .toList();
                    onResource0(val, datas);
                }
                case "command" -> {
                    datas = mc.getConnection().getCommands().getRoot().getChildren().stream()
                            .map(CommandNode::getName)
                            .filter(u -> u.contains(filter))
                            .sorted(String::compareTo)
                            .toList();
                    onResource0(val, datas);
                }
                case "seed" -> {
                    datas = List.of(
                            Component.literal("服务端加密种子: ")
                                    .append(ChatUtils.getDisplayedLong(mc.level.getBiomeManager().biomeZoomSeed)),
                            Component.literal("当前绑定种子: ")
                                    .append(
                                            SeedOre.INSTANCE.hasCurrentSeed()
                                                    ? ChatUtils.getDisplayedLong(SeedOre.INSTANCE.getCurrentSeed())
                                                    : Component.literal("暂未输入")));
                    onResource0(val, datas);
                }
                case "plugins" -> {
                    Debug.chat(Component.literal("导出Command Namespace获取的数据:").withStyle(ChatFormatting.GREEN));
                    datas = ClientUtils.getServerCommands().stream()
                            .map(n -> {
                                var sp = n.split(":");
                                return sp.length >= 2 ? sp[0] : null;
                            })
                            .filter(Objects::<String>nonNull)
                            .filter(u -> ((String) u).contains(filter))
                            .distinct()
                            .sorted(String::compareTo)
                            .toList();
                    onResource0(val, datas);
                    Debug.chat(Component.literal("导出Version Tab获取的数据:").withStyle(ChatFormatting.GREEN));
                    ClientUtils.getServerPluginResources().thenAccept((list) -> {
                        onResource0(
                                val,
                                list.stream()
                                        .map(str -> str.toLowerCase(Locale.ROOT))
                                        .filter(u -> u.contains(filter))
                                        .distinct()
                                        .sorted(String::compareTo)
                                        .toList());
                    });
                }
                //                    case "gamerule"->{
                //                        datas = mc.level.getGameRules().toNbt().entries.entrySet().stream()
                //                            .map(entry-> entry.getKey()+ ":" + entry.getValue().asString())
                //                            .filter(u-> u.contains(filter))
                //                            .toList();
                //                    }
                default -> {
                    Debug.chat(Component.literal("不支持的资源: " + val).withStyle(ChatFormatting.RED));
                }
            }
        }

        private void onResource0(String name, List datas) {
            Debug.chat(Component.literal(name + "所拥有的数据:").withStyle(ChatFormatting.GREEN));
            for (var identifier1 : datas) {
                Debug.chat(identifier1);
            }
        }

        List<String> infoTypes = List.of(
                "death",
                "spawn",
                "nbt",
                "inventory",
                "ender",
                "trackinventory",
                "plist",
                "team",
                "pentry",
                "waypoint",
                "server");

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("info")
                    .helper("message.command.sfh.info.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("information")
                            .select(infoTypes)
                            .build())
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("user")
                            .dispatchLast(this::onInfoTab)
                            .select("#me")
                            .defaultValue("#me")
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onInfo)))
                    .complete();
        }

        public Stream<String> onInfoTab(String string) {
            return switch (string) {
                case "nbt", "inventory", "trackinventory", "ender" -> EntityUtils.getWorldPlayerNames(true);
                case "pentry", "team" -> WorldUtils.getPlayerListNames();
                case "waypoint" -> WorldUtils.getWaypointNames();
                default -> Stream.empty();
            };
        }

        public void onInfo(ArgumentInputStream re) {
            String info = re.nextSelect(infoTypes);
            Player entity;
            String user = re.nextNonnull();
            entity = Objects.equals("#me", user) ? mc.player : EntityUtils.getPlayerByName(user);
            if (entity != null) {
                Debug.chat("Information about player : ", entity.getScoreboardName());
            }
            switch (info) {
                case "death" -> {
                    if (entity != null) {
                        var death = entity.getLastDeathLocation();
                        if (death.isPresent()) {
                            var deathpoint = death.get();
                            var world = deathpoint.dimension();
                            Debug.chat(
                                    "Last Death Point [World:",
                                    world.identifier(),
                                    ",Pos:",
                                    ChatUtils.getDisplayedLocationDouble(Vec3.atLowerCornerOf(deathpoint.pos())),
                                    "]");
                        } else {
                            Debug.chat("Last Death Point Not Present");
                        }
                    } else {
                        Debug.chat("找不到玩家", user);
                    }
                }
                case "spawn" -> {
                    Debug.chat("当前世界的出生点:");
                    BlockPos pos = mc.level.getRespawnData().globalPos().pos();
                    ResourceKey<Level> key =
                            mc.level.getRespawnData().globalPos().dimension();
                    Debug.chat(
                            "World Spawn Point [World:",
                            key.identifier(),
                            ",Pos:",
                            ChatUtils.getDisplayedLocationDouble(Vec3.atLowerCornerOf(pos)),
                            "]");
                    //                        if(entity != null){
                    //                           // mc.player.spawn
                    //                        }else{
                    //                            Debug.chat("找不到玩家", user);
                    //                        }
                }
                case "nbt" -> {
                    if (entity != null) {
                        var comp = VEntity.saveEntityNbt(entity);
                        comp.remove("Inventory");
                        comp.remove("EnderItems");
                        Debug.chat(new TextComponentTagVisitor("").visit(comp));
                    } else {
                        Debug.chat("找不到玩家", user);
                    }
                }
                case "inventory" -> {
                    if (entity != null) {
                        Inventory enderInventory = entity.getInventory();
                        Tasks.scheduleDelayed(
                                () -> {
                                    ScreenAccess.of(new InventoryViewScreen(
                                                    enderInventory,
                                                    Component.literal("背包预览 - " + entity.getScoreboardName()),
                                                    new ItemStack(Items.CHEST)))
                                            .openFromCurrent();
                                },
                                2);

                    } else {
                        Debug.chat("找不到玩家", user);
                    }
                }
                case "trackinventory" -> {
                    if (entity != null) {
                        PlayerStateManager.PlayerStatus status = PlayerStateManager.INSTANCE.getPlayerStatus(entity);
                        List<ItemStack> stacks;
                        if (status != null) {
                            stacks = status.trackedInventoryItems.stream()
                                    .map(ItemStackSample::sample)
                                    .toList();
                        } else {
                            stacks = List.of();
                        }
                        Tasks.scheduleDelayed(
                                () -> {
                                    ScreenAccess.of(new InventoryViewScreen(
                                                    InventoryUtils.createInventory(stacks),
                                                    Component.literal("背包追踪预览 - " + entity.getScoreboardName()),
                                                    new ItemStack(Items.BARRIER)))
                                            .openFromCurrent();
                                },
                                2);
                    } else {
                        Debug.chat("找不到玩家", user);
                    }
                }
                case "ender" -> {
                    if (entity != null) {
                        Container enderInventory = entity == mc.player
                                ? ChestHistory.INSTANCE.getTrackedEnderChestInventory()
                                : entity.getEnderChestInventory();
                        Tasks.scheduleDelayed(
                                () -> {
                                    ScreenAccess.of(new InventoryViewScreen(
                                                    enderInventory,
                                                    Component.literal("末影箱预览 - " + entity.getScoreboardName()),
                                                    new ItemStack(Items.ENDER_CHEST)))
                                            .openFromCurrent();
                                },
                                2);

                    } else {
                        Debug.chat("找不到玩家", user);
                    }
                }
                case "plist" -> {
                    Debug.chat(Component.literal("当前可视的玩家列表").withStyle(ChatFormatting.GREEN));
                    mc.getConnection().getOnlinePlayers().stream()
                            .sorted(Comparator.comparing(e -> VRecord.getName(e.getProfile())))
                            .map(entry -> {
                                var val = Component.literal(
                                                "%-16s (Display: ".formatted(VRecord.getName(entry.getProfile())))
                                        .append(
                                                entry.getTabListDisplayName() == null
                                                        ? Component.literal("null")
                                                        : entry.getTabListDisplayName())
                                        .append(Component.literal(", GameMode: "
                                                + entry.getGameMode().name() + ")"));
                                Debug.info(val);
                                return val;
                            })
                            .forEach(Debug::chat);
                }
                case "team" -> {
                    String user0 = Objects.equals(user, "#me") ? mc.player.getScoreboardName() : user;
                    PlayerInfo entry = Minecraft.getInstance().getConnection().getPlayerInfo(user0);
                    if (entry != null) {
                        PlayerTeam team = entry.getTeam();
                        if (team != null) {
                            Debug.chat("该玩家所在Team: ", team.getName());
                            Debug.chat(
                                    Component.literal("展示名称: ").withStyle(ChatFormatting.GRAY),
                                    team.getDisplayName() == null ? "" : team.getDisplayName());
                            Debug.chat(
                                    Component.literal("前缀: ").withStyle(ChatFormatting.GRAY),
                                    team.getPlayerPrefix() == null ? "" : team.getPlayerPrefix());
                            Debug.chat(
                                    Component.literal("后缀: ").withStyle(ChatFormatting.GRAY),
                                    team.getPlayerSuffix() == null ? "" : team.getPlayerSuffix());
                            Debug.chat(
                                    Component.literal("颜色: ").withStyle(ChatFormatting.GRAY),
                                    team.getColor() == null ? "" : team.getColor());
                            Debug.chat(
                                    Component.literal("友伤: ").withStyle(ChatFormatting.GRAY),
                                    team.isAllowFriendlyFire());
                            Debug.chat(
                                    Component.literal("显示隐身队友: ").withStyle(ChatFormatting.GRAY),
                                    team.canSeeFriendlyInvisibles());
                            Debug.chat(Component.literal("队员列表:").withStyle(ChatFormatting.GRAY));
                            Debug.chat(Component.literal("-------------------").withStyle(ChatFormatting.GREEN));
                            for (var str : team.getPlayers()) {
                                Debug.chat(str);
                            }
                        } else {
                            Debug.chat("该玩家没有Team");
                        }
                    } else {
                        Debug.chat("找不到玩家", user);
                    }
                }
                case "pentry" -> {
                    String user0 = Objects.equals(user, "#me") ? mc.player.getScoreboardName() : user;
                    PlayerInfo entry = Minecraft.getInstance().getConnection().getPlayerInfo(user0);
                    if (entry != null) {
                        Debug.chat("查询到PlayerEntry");
                        Debug.chat(
                                Component.literal("名字: ").withStyle(ChatFormatting.GRAY),
                                VRecord.getName(entry.getProfile()));
                        Debug.chat(
                                Component.literal("UUID: ").withStyle(ChatFormatting.GRAY),
                                ChatUtils.getClickCopyTargetText(VRecord.getId(entry.getProfile())
                                                .toString())
                                        .withStyle(ChatFormatting.GREEN));
                        Debug.chat(
                                Component.literal("Property: ").withStyle(ChatFormatting.GRAY),
                                ChatUtils.getHoverShowText(
                                        "[点击查看具体数据]",
                                        List.of(Component.literal(VRecord.getProperties(entry.getProfile())
                                                .toString()))));
                        Debug.chat(
                                Component.literal("GameMode: ").withStyle(ChatFormatting.GRAY),
                                entry.getGameMode().name());
                        Debug.chat(
                                Component.literal("DisplayName: ").withStyle(ChatFormatting.GRAY),
                                entry.getTabListDisplayName() == null
                                        ? Component.literal("null")
                                        : entry.getTabListDisplayName());
                        List<Component> texts = new ArrayList<>();
                        texts.add(Component.literal("Latency: " + entry.getLatency()));
                        texts.add(Component.literal("MessageVerifier: " + entry.getMessageValidator()));
                        texts.add(Component.literal("SkinTextures: " + entry.getSkin()));
                        texts.add(Component.literal("Session: " + entry.getChatSession()));
                        Debug.chat(
                                Component.literal("More: ").withStyle(ChatFormatting.GRAY),
                                ChatUtils.getHoverShowText("[点击查看具体数据]", texts));
                    } else {
                        Debug.chat("该玩家没有PlayerEntry");
                    }
                }
                case "server" -> {
                    Debug.chat("当前服务器:");
                    String ip = CommonUtils.getServerName();
                    Debug.chat(
                            ChatUtils.getClickCopyTargetText(ip).withStyle(ChatFormatting.GREEN),
                            "|",
                            mc.level.dimension().identifier());
                }
                case "waypoint" -> {
                    Debug.chat("查询中");
                    PlayerInfo entry;
                    Predicate<WorldUtils.Waypoint> filter;
                    if ((entry = mc.getConnection().getPlayerInfo(user)) != null) {
                        final String lookup;
                        lookup = VRecord.getId(entry.getProfile()).toString();
                        filter = s -> lookup.equalsIgnoreCase(s.getSource().map(UUID::toString, Function.identity()));
                    } else {
                        filter = Predicates.alwaysTrue();
                    }
                    WorldUtils.getWaypoints().filter(filter).forEach(s -> {
                        Debug.chat(
                                "Information about waypoint:", s.getSource().map(UUID::toString, Function.identity()));
                        Optional<PlayerInfo> optionalEntry = s.getSource()
                                .map(
                                        t -> Optional.ofNullable(
                                                mc.getConnection().getPlayerInfo(t)),
                                        t -> Optional.ofNullable(
                                                mc.getConnection().getPlayerInfo(t)));
                        optionalEntry.ifPresent(playerListEntry ->
                                Debug.chat("Potential Owner: " + VRecord.getName(playerListEntry.getProfile())));

                        Debug.chat("config: ");

                        Debug.chat(new TextComponentTagVisitor("").visit(s.getConfig()));

                        Debug.chat("type: " + s.getData().getTypeName());
                        switch (s.getData().getTypeName()) {
                            case "Pos" -> {
                                Vec3 vec3d = ((WorldUtils.WaypointData.Pos) s.getData()).pos();
                                Debug.chat("Pos :", vec3d.x, vec3d.y, vec3d.z);
                            }
                            case "Chunk" -> {
                                ChunkPos vec3d = ((WorldUtils.WaypointData.Chunk) s.getData()).pos();
                                Debug.chat("Chunk :", vec3d.x, vec3d.z);
                            }
                            case "Direction" -> {
                                float dr = ((WorldUtils.WaypointData.Direction) s.getData()).azimuth();
                                Debug.chat("Azimuth :", dr);
                            }
                        }
                    });
                }
            }
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("preset")
                    .helper("message.command.sfh.preset.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("preset")
                            .enumValue(ModulePreset.class)
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onPreset)))
                    .complete();
        }

        public void onPreset(ArgumentInputStream re) {
            ModulePreset preset1 = re.nextEnum(ModulePreset.class);
            Listener.getCustomListener()
                    .handleValue(new Event<>(new EventContainer<>(ModulePreset.class, preset1), false, false));
            //
            Debug.info("已经加载", preset1.name(), "配置预设");
            Config.launchSaveTasks();
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("runtask")
                    .helper("message.command.sfh.runtask.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("delay")
                            .intValue()
                            .build())
                    .post(e -> e.executor(new CommandContext() {
                        @Override
                        public boolean execute(
                                CommandExecution var1, ArgumentInputStream streamArgs, ArgumentReader argsReader) {
                            int delay = streamArgs.nextInt();
                            String[] args = argsReader.getRemainingArgs();
                            Tasks.scheduleDelayed(
                                    () -> {
                                        MainCommand.dispatchCommand(args);
                                    },
                                    delay);
                            return true;
                        }

                        @Override
                        public List<String> supplyTab(
                                CommandExecution var1, ArgumentInputStream streamArgs, ArgumentReader argsReader) {
                            ArgumentReader reader = new ArgumentReader(argsReader.getRemainingArgs());
                            reader.stepAll();
                            return onCustomTabComplete(var1, argsReader);
                        }
                    }))
                    .complete();
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("runrepeat")
                    .helper("message.command.sfh.runrepeat.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("period")
                            .intValue()
                            .build())
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("time")
                            .intValue()
                            .build())
                    .post(e -> e.executor(new CommandContext() {
                        @Override
                        public boolean execute(
                                CommandExecution var1, ArgumentInputStream streamArgs, ArgumentReader argsReader) {
                            int delay = streamArgs.nextInt();
                            int time = streamArgs.nextInt();
                            String[] args = argsReader.getRemainingArgs();
                            MutableInt counter = new MutableInt(0);
                            Tasks.scheduleRepeatedPre(
                                    () -> {
                                        if (mc.level == null || mc.player == null) return true;
                                        MainCommand.dispatchCommand(args);
                                        if (counter.incrementAndGet() >= time) {
                                            return true;
                                        }
                                        return false;
                                    },
                                    0,
                                    delay);
                            return true;
                        }

                        @Override
                        public List<String> supplyTab(
                                CommandExecution var1, ArgumentInputStream streamArgs, ArgumentReader argsReader) {
                            ArgumentReader reader = new ArgumentReader(argsReader.getRemainingArgs());
                            reader.stepAll();
                            return onCustomTabComplete(var1, argsReader);
                        }
                    }))
                    .complete();
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("say")
                    .helper("message.command.sfh.say.help")
                    .post(e -> e.executor((a, b, c) -> {
                        ChatTasks.sayMessage(c.getRemainingArgStr(), false);
                        return true;
                    }))
                    .complete();
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("show")
                    .helper("message.command.sfh.show.help")
                    .post(e -> e.executor((a, b, c) -> {
                        Debug.chat(ChatUtils.stringToText(c.getRemainingArgStr()));
                        return true;
                    }))
                    .complete();
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("logout")
                    .helper("message.command.sfh.exit.help")
                    .post(e -> e.executor(CommandContext.run(MainTasks::scheduleDisconnect)))
                    .complete();
        }

        {
            main.subBuilder(SubCommand.taskBuilder())
                    .name("toggle")
                    .helper("message.command.sfh.toggle.help")
                    .arg(SimpleCommandArgs.argumentBuilder()
                            .name("module")
                            .tabSupplier(this::supplyModule)
                            .build())
                    .post(e -> e.executor(CommandContext.run(this::onToggle)))
                    .complete();
        }

        public Stream<String> supplyModule() {
            return HackModules.getModuleGroups().stream()
                    .flatMap(s -> s.getModules().stream())
                    .flatMap(b -> {
                        return b.getModuleEntries()
                                .map(ModuleEntry::getTranslationKey)
                                .map((ChatUtils::parseTranslation));
                    });
        }

        public void onToggle(ArgumentInputStream re) {
            String moduleName = re.nextNonnullString();
            for (var moduleGroup : HackModules.getModuleGroups()) {
                for (var module : moduleGroup.getModules()) {
                    for (var moduleEntry : module.getModuleEntries().toList()) {
                        String translation = ChatUtils.parseTranslation(moduleEntry.getTranslationKey());
                        if (Objects.equals(moduleName, translation)) {
                            HotKeyUtils.wrapFlagAsToggle(moduleEntry.getPath(), moduleEntry.getFlagRef())
                                    .run();
                            return;
                        }
                    }
                }
            }
            Debug.chat(ChatUtils.stringToText("&e找不到模块项: " + moduleName));
        }

        // todo not complete

        // todo more command
        // todo add facing/ targeting command

    }

    static {
        MainCommand.registerCommands(SlimefunHelperCommand::new);
    }
}
