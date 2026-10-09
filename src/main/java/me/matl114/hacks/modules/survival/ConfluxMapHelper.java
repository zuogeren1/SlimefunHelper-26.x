package me.matl114.hacks.modules.survival;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import me.matl114.events.Event;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.ChatTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.*;
import me.matl114.hooks.ConfluxMapHooks;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuContext;
import me.matl114.hooks.impl.confluxmap.ConfluxMenuTarget;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.ScreenUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 与 conflux-map 联动：在他们的全屏地图右键位置菜单正下方追加一块面板，
 * 提供与 {@link XaeroHelper} 右键菜单等价的“自定义指令 / 自定义补全 / 复制坐标”。
 *
 * <p>模块本身只负责把条目收集出来交给 {@link ConfluxMapHooks}，绘制与命中判定在
 * {@code ConfluxMapScreenMixin} + {@code ConfluxMenuOverlay} 里完成。
 */
public class ConfluxMapHelper extends BaseModule {
    public static ConfluxMapHelper INSTANCE;

    public ConfluxMapHelper() {
        super("ConfluxMapHelper");
        INSTANCE = this;
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "conflux-map-extra.conflux-map-helper");

    public final FlagRef enableRightClickCommand =
            flagBuilder(root.add("enable-right-click-command")).build();

    private static final List<String> LIST_FORMATS = List.of("world", "pos", "pos_str", "x", "y", "z");

    public final NBTRef<PrimitiveList<StringFormat>> rightClickCommand = builder(
                    root.add("right-click-command-list"), PrimitiveList.type(StringFormat.class))
            .defaultValue(new PrimitiveList<>(
                    NBTTypes.STRING_FORMAT_TYPE,
                    List.of(new StringFormat(LIST_FORMATS, "/tp {pos}")),
                    new StringFormat(LIST_FORMATS, "")))
            .build();

    public final NBTRef<PrimitiveList<StringFormat>> rightClickSuggest = builder(
                    root.add("right-click-suggest-list"), PrimitiveList.type(StringFormat.class))
            .defaultValue(
                    new PrimitiveList<>(NBTTypes.STRING_FORMAT_TYPE, List.of(), new StringFormat(LIST_FORMATS, "")))
            .build();

    public final FlagRef enableCopyCoords = flagBuilder(root.add("enable-copy-coords"))
            .defaultValue(true)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(ConfluxMapHooks.getLocationMenuOption(), this::onConfluxMenuCollect);
    }

    /** 与 {@link XaeroHelper} 的右键菜单完全一致的占位符说明 */
    private static final Map<String, Object> formatMap = ImmutableMap.<String, Object>builder()
            .put("pos", Component.translatable("message.module.conflux-map-helper.right-click-command.pos"))
            .put("pos_str", Component.translatable("message.module.conflux-map-helper.right-click-command.pos_str"))
            .put("x", Component.translatable("message.module.conflux-map-helper.right-click-command.pos_x"))
            .put("y", Component.translatable("message.module.conflux-map-helper.right-click-command.pos_y"))
            .put("z", Component.translatable("message.module.conflux-map-helper.right-click-command.pos_z"))
            .build();

    private static String formatPosition(ConfluxMenuTarget target) {
        return "%d %d %d".formatted(target.x(), target.y(), target.z());
    }

    public void onConfluxMenuCollect(Event<ArrayList<ConfluxMenuContext>> event) {
        if (!ConfluxMapHooks.getInstance().isEnabled()) {
            return;
        }
        ConfluxMenuTarget target = event.getArgs(0);
        if (target == null) {
            return;
        }
        Map<String, String> map = ImmutableMap.<String, String>builder()
                .put("world", target.worldPath())
                .put("pos", formatPosition(target))
                .put("pos_str", "%d,%d,%d".formatted(target.x(), target.y(), target.z()))
                .put("x", String.valueOf(target.x()))
                .put("y", String.valueOf(target.y()))
                .put("z", String.valueOf(target.z()))
                .build();
        if (enableRightClickCommand.get()) {
            for (var format : rightClickCommand.get().list()) {
                String name = ChatUtils.textToPlainString(Component.translatable(
                        "message.module.conflux-map-helper.right-click-command.command", format.formatText(formatMap)));
                event.context.add(new ConfluxMenuContext(name, (clicked) -> {
                    String formatted = format.format(map);
                    ChatTasks.sayMessage(formatted, false);
                }));
            }
            for (var format : rightClickSuggest.get().list()) {
                String name = ChatUtils.textToPlainString(Component.translatable(
                        "message.module.conflux-map-helper.right-click-command.suggest", format.formatText(formatMap)));
                event.context.add(new ConfluxMenuContext(name, (clicked) -> {
                    String formatted = format.format(map);
                    ScreenUtils.openChatScreen(formatted);
                }));
            }
        }
        if (enableCopyCoords.get()) {
            String name = ChatUtils.textToPlainString(
                    Component.translatable("message.module.conflux-map-helper.right-click-command.copy-coords"));
            event.context.add(new ConfluxMenuContext(name, (clicked) -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.keyboardHandler != null) {
                    mc.keyboardHandler.setClipboard(formatPosition(clicked));
                }
            }));
        }
    }

    @Override
    public void addCustomWidgets(Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank) {
        super.addCustomWidgets(acceptor, dx, dy, dblank);
        acceptor.accept(createTitle(
                ConfluxMapHooks.getInstance().isEnabled()
                        ? "widget.conflux-map-helper.conflux-map-enable"
                        : "widget.conflux-map-helper.conflux-map-not-support",
                0,
                dblank,
                dx,
                dy));
    }
}
