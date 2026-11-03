package me.matl114.hacks.modules.task;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.gui.*;
import me.matl114.gui.Constants;
import me.matl114.gui.FilterService;
import me.matl114.gui.McWidgetHelpers;
import me.matl114.gui.WidgetUtils;
import me.matl114.gui.basic.*;
import me.matl114.gui.complex.config.ConfigMultiLevelListScreen;
import me.matl114.gui.elements.ButtonElement;
import me.matl114.gui.elements.IconElement;
import me.matl114.gui.interfaces.CombinedElementFactory;
import me.matl114.gui.interfaces.CustomElementFactory;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.gui.interfaces.VanillaElementFactory;
import me.matl114.gui.presets.single.CenterScreen;
import me.matl114.gui.presets.single.SimpleScreen;
import me.matl114.hacks.MainTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.config.GuiSetting;
import me.matl114.hacks.utils.config.StringEnum;
import me.matl114.hacks.utils.config.Vec2;
import me.matl114.hacks.utils.gui.ClassicClickGuiScreen;
import me.matl114.hacks.utils.gui.ClickGuiStyles;
import me.matl114.hacks.utils.gui.SliderClickGuiFactory;
import me.matl114.hacks.utils.gui.SubScreenClickGuiFactory;
import me.matl114.hooks.BaritoneHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.FileManager;
import me.matl114.managers.config.*;
import me.matl114.managers.file.FileStorage;
import me.matl114.managers.input.*;
import me.matl114.plugins.internal.PluginManager;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.ClientUtils;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.algorithms.SerialExecutor;
import me.matl114.utils.config.AttrKeyValue;
import me.matl114.utils.config.Value;
import me.matl114.utils.config.ValueAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

public class ClickGui extends BaseModule {
    public static ClickGui INSTANCE;

    public final NBTType<GuiSetting> configInputWidget = GuiSetting.TYPE;

    public ClickGui() {
        super("ClickGui");
        INSTANCE = this;
    }

    public final ModulePath hotkeys = makePath(Configs.MISC_CONFIG, "hotkeys");
    public final ModulePath config = makePath(Configs.MISC_CONFIG, "hotkeys");
    public ModulePath clickGui = makePath(Configs.MISC_CONFIG, "click-gui");
    public final KeyBindRef keyBindConfigScreen = hotkey(hotkeys.add("open-menu"))
            .defaultValue(new MultiKeyBind(KeyCode.KEY_LEFT_CONTROL, KeyCode.KEY_G))
            .registerHotkey(HotKeyUtils.wrapAsHandler((Runnable) this::openConfigScreen))
            .build();

    public final KeyBindRef optionsKeyBind = hotkey(hotkeys.add("open-options-menu"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::openGameOptionsMenu))
            .build();

    public KeyBindRef keyBindClickGui = hotkey(clickGui.add("hotkey"))
            .defaultValue(new MultiKeyBind(KeyCode.KEY_RIGHT_ALT))
            .registerHotkey(this::onHotkey)
            .build();

    public final NBTRef<StringEnum> guiStyle = builder(clickGui.add("click-gui-style"), StringEnum.class)
            .defaultValue(new StringEnum(defaultGuiStyleValue(), ClickGuiStyles.configValues()))
            .build();

    public NBTRef<Vec2> widgetSize = builder(clickGui.add("widget-size"), Vec2.class)
            .defaultValue(new Vec2(55, 12))
            .show(() -> isGuiStyle(ClickGuiStyles.SUB_SCREEN))
            .build();

    public NBTRef<Vec2> sliderWidgetSize = builder(clickGui.add("slider-widget-size"), Vec2.class)
            .defaultValue(new Vec2(70, 12))
            .show(() -> isGuiStyle(ClickGuiStyles.SLIDER))
            .build();

    public FileStorage internalGuiData = FileManager.getInstance().getInternalStorage("click-gui-data.nbt");

    public final FlagRef enableConfigSubGroup = builder(clickGui.add("enable-config-subgroup"), Boolean.class)
            .defaultValue(true)
            .build();

    public final ModulePath widgetType = clickGui.add("widget-style");

    public final NBTRef<GuiSetting> guiSetting = builder(widgetType.add("setting"), GuiSetting.class)
            .defaultValue(GuiSetting.DEFAULT)
            .show(() -> isGuiStyle(ClickGuiStyles.SUB_SCREEN))
            .build();

    public final ModulePath sliderWidgetType = clickGui.add("slider-widget-style");

    public final NBTRef<GuiSetting> sliderWidgetGuiConfig = builder(sliderWidgetType.add("setting"), GuiSetting.class)
            .defaultValue(GuiSetting.SLIDER_DEFAULT)
            .show(() -> isGuiStyle(ClickGuiStyles.SLIDER))
            .build();

    public GuiSetting getGuiSettings() {
        return isGuiStyle(ClickGuiStyles.SLIDER) ? sliderWidgetGuiConfig.get() : guiSetting.get();
    }

    public final ModulePath configType = clickGui.add("config-editor-style");

    public final NBTRef<GuiSetting> configGuiConfig = builder(configType.add("setting"), GuiSetting.class)
            .defaultValue(GuiSetting.DEFAULT)
            .build();

    private final ElementFactory configElementFactory = new CombinedElementFactory(configGuiConfig::get);
    private final CustomElementFactory guiElementFactory = new CustomElementFactory(this::getGuiSettings);

    public ElementFactory configElementFactory() {
        return configElementFactory;
    }

    private boolean isGuiStyle(net.minecraft.resources.Identifier id) {
        return ClickGuiStyles.getOrDefault(guiStyle.get().value()).id().equals(id);
    }

    private static String defaultGuiStyleValue() {
        return ClickGuiStyles.configValue(ClickGuiStyles.SUB_SCREEN);
    }

    private void refreshGuiStyleChoices() {
        StringEnum current = guiStyle.get();
        List<String> choices = ClickGuiStyles.configValues();
        String currentValue = ClickGuiStyles.getOrDefault(current.value()).id().toString();
        if (!current.values().equals(choices) || !current.value().equals(currentValue)) {
            guiStyle.set(new StringEnum(currentValue, choices));
        }
    }

    private boolean onHotkey(IHotKey iHotKey, IInputManager manager) {
        if (HotKeyUtils.isValidState()) {
            openClickGui();
            return true;
        } else if (ClientUtils.getScreen(mc) instanceof ClassicClickGuiScreen gui) {
            gui.onClose();
            return true;
        } else return false;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPostInitializeScreen().getChannel(JoinMultiplayerScreen.class), this::onScreenInitialize);
    }

    public void openGameOptionsMenu() {
        Options options = mc.options;
        List<OptionInstance<?>> options1 = new ArrayList<>();
        for (var field : Options.class.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType().isAssignableFrom(OptionInstance.class)) {
                try {
                    OptionInstance<?> option = (OptionInstance<?>) field.get(options);
                    if (option != null) {
                        options1.add(option);
                    }
                } catch (Throwable e) {

                }
            }
        }
        ScrollableListWidget widget = new ScrollableListWidget(20, 20, 360, 280);
        int yLevel = 0;
        for (var sim : options1) {
            var re = sim.createButton(mc.options);
            widget.addScrollingWidget(new ContentDelegateWidget<>(20, yLevel, 320, 40).setContentDelegate(re));
            //                SubScreenWidget.instance(20, 0 , 320, 40)
            //                    .addDrawableChild(
            ////                        DisplayWidget.instance(0,0, 150, 40)
            ////                            .setRenderHandler(
            ////                                new ButtonElement(TextProvider.of(sim.))
            ////                            )
            //                    )
            //            )
            yLevel += re.getHeight();
        }
        SimpleScreen screen = new SimpleScreen(Component.literal("Options Screen"), 400, 320, widget);

        ScreenAccess.of(screen).openFromCurrent();
    }

    private WeakReference<ContentDelegateWidget<ExecutableWidget>> delegateWidget = null;

    public void onScreenInitialize(Event<JoinMultiplayerScreen> screenEvent) {
        if (screenEvent.context() instanceof JoinMultiplayerScreen mp) {
            // todo: add
            ExecutableWidget executableWidget = ExecutableWidget.instance(0, 0, 100, 20)
                    .setElementHandler(VanillaElementFactory.INSTANCE.createButton(
                            TextProvider.of(Component.literal("SlimefunHelper")),
                            TooltipHandler.of(List.of()),
                            ButtonAction.run(this::openClickGui),
                            () -> true,
                            () -> false));
            if (delegateWidget != null && delegateWidget.get() != null) {
                ScreenAccess.of(mp).removeChildFrom(delegateWidget.get());
            }
            delegateWidget = null;
            ContentDelegateWidget<ExecutableWidget> dynamicWidget =
                    new ContentDelegateWidget<>(mp.width - 100, 5, 0, 0);
            dynamicWidget.setContentDelegate(executableWidget);
            dynamicWidget.addTo(mp);
            this.delegateWidget = new WeakReference<>(dynamicWidget);
        }
    }

    public void resetGui() {
        if (ClientUtils.getScreen(mc) instanceof ClassicClickGuiScreen guiMain) {
            guiMain.onClose();
        }
        internalGuiData.write(new CompoundTag(), NbtOps.INSTANCE);
    }

    public List<String> getModules() {
        List<String> names = new ArrayList<>(HackModules.main.getModuleGroups().keySet());
        // View groups are rendered like real groups but never registered; keep them last,
        // right before Search, so plugin modules stay grouped together.
        HackModules.getViewGroups().forEach(group -> names.add(group.getName()));
        return names;
    }

    private static final String SEARCH_MODULE = "Search";

    /**
     * 自动排版时一个模块组占的高度：表头 + （展开时）模块列表。
     * 搜索框那列的结果是动态的，按经验值给个上界即可，只影响换行位置。
     */
    private int moduleGroupHeight(ClickGuiMetaData meta, String name) {
        int unit = (int) widgetSize.get().y();
        ModuleSlideMeta slide = meta.getModuleMetaMap().get(name);
        if (slide == null || !slide.isSlidingDown()) {
            return unit;
        }
        if (SEARCH_MODULE.equals(name)) {
            return unit * 4;
        }
        ModuleGroup group = HackModules.getModuleGroup(name);
        return unit + (group == null ? 0 : (int) getShowModuleList(group).count() * unit);
    }

    public ClickGuiMetaData getClickGuiMetadata() {
        refreshGuiStyleChoices();
        CompoundTag data = internalGuiData.asReadOnly(NbtOps.INSTANCE);
        var result = ClickGuiMetaData.CODEC.decode(NbtOps.INSTANCE, data);
        ClickGuiMetaData meta;
        if (result.isSuccess()) {
            meta = result.getOrThrow().getFirst();
        } else {
            meta = new ClickGuiMetaData();
        }
        List<String> modules = getModules();
        modules.add(SEARCH_MODULE);
        meta.checkDefault(
                modules,
                name -> moduleGroupHeight(meta, name),
                (int) widgetSize.get().x(),
                (int) widgetSize.get().y());
        meta.checkSliderGroupDefault(modules, (int) sliderWidgetSize.get().x(), (int)
                sliderWidgetSize.get().y());

        setClickGuiMeta(meta);
        return meta;
    }

    public void setClickGuiMeta(ClickGuiMetaData meta) {
        CompoundTag encoded = (CompoundTag)
                ClickGuiMetaData.CODEC.encodeStart(NbtOps.INSTANCE, meta).getOrThrow();
        CompoundTag data = internalGuiData.asReadOnly(NbtOps.INSTANCE);
        data = data.copy();
        for (String key : List.of(
                "module_list_metas",
                "slider_group_metas",
                "sub_group_metas",
                "slider_metas",
                "searching",
                "extra_datas")) {
            data.remove(key);
        }
        for (String key : encoded.keySet()) {
            data.put(key, encoded.get(key).copy());
        }
        internalGuiData.write(data, NbtOps.INSTANCE);
    }

    public static final int DEFAULT_GAP = 5;
    public static final int DEFAULT_Y = 40;

    public void openClickGui() {
        refreshGuiStyleChoices();
        ClickGuiMetaData meta = getClickGuiMetadata();
        ClickGuiStyles.getOrDefault(guiStyle.get().value()).opener().accept(this, meta);
    }

    public void openSubScreenStyle(ClickGuiMetaData meta) {
        new SubScreenClickGuiFactory(this::getGuiSettings).openClickGui(this, meta);
    }

    public void openSliderStyle(ClickGuiMetaData meta) {
        new SliderClickGuiFactory(this::getGuiSettings).openClickGui(this, meta);
    }

    public Stream<BaseModule> getShowModuleList(ModuleGroup group) {
        return group.getModules().stream().filter(BaseModule::shouldShowInGui);
    }

    public Component getModuleName(BaseModule baseModule) {
        return Component.translatableWithFallback(
                "widget.click-gui.module-name." + baseModule.getModuleManager().getName() + "." + baseModule.getName(),
                baseModule.getName());
    }

    private static final List<Component> TOOLTIP_HAS_BIND =
            List.of(Component.literal("左键切换模块是否启用"), Component.literal("右键打开模块配置界面"));
    private static final List<Component> TOOLTIPS_NO_BIND = List.of(Component.literal("点击打开模块配置界面"));

    public List<Component> getModuleButtonTooltips(BaseModule baseModule) {
        List<Component> texts = new ArrayList<>(ChatUtils.parseTooltipsTranslation(
                "widget.click-gui.module-name." + baseModule.getModuleManager().getName() + "." + baseModule.getName()
                        + ".tooltips",
                ""));
        if (!texts.isEmpty()) {
            texts.add(Component.empty());
        }
        if (baseModule.getBindFlag() != null) {
            texts.addAll(TOOLTIP_HAS_BIND);
        } else {
            texts.addAll(TOOLTIPS_NO_BIND);
        }
        String pluginId = PluginManager.getInstance().ownerOf(baseModule);
        if (pluginId != null) {
            texts.add(Component.empty());
            texts.add(Component.translatableWithFallback(
                    "widget.click-gui.module-plugin-origin", "来自插件 %s", pluginId));
        }
        return texts;
    }

    public List<Component> getModuleDescriptionTooltips(BaseModule baseModule) {
        return ChatUtils.parseTooltipsTranslation(
                "widget.click-gui.module-name." + baseModule.getModuleManager().getName() + "." + baseModule.getName()
                        + ".tooltips",
                "暂无介绍");
    }

    private static final int indexWidth = 140;
    private static final int blankWidth = 10;
    private static final int buttonWidth = 180;
    private static final int buttonHeight = 18;
    private static final int buttonBlank = 2;
    private static final WidgetUtils.ConfigScreenLayout MODULE_CONFIG_LAYOUT =
            new WidgetUtils.ConfigScreenLayout(indexWidth, blankWidth, buttonWidth, buttonHeight, buttonBlank);

    public DrawableWidget createBaseModuleConfigurateScreen(BaseModule baseModule, ClickGuiMetaData metaData) {
        return createModuleSettingsList(baseModule, metaData, MODULE_CONFIG_LAYOUT, true, guiElementFactory);
    }

    public DynamicListWidget createModuleSettingsList(
            BaseModule baseModule,
            ClickGuiMetaData metaData,
            WidgetUtils.ConfigScreenLayout layout,
            boolean withTitle) {
        return createModuleSettingsList(baseModule, metaData, layout, withTitle, configElementFactory);
    }

    private DynamicListWidget createModuleSettingsList(
            BaseModule baseModule,
            ClickGuiMetaData metaData,
            WidgetUtils.ConfigScreenLayout layout,
            boolean withTitle,
            ElementFactory displayFactory) {
        int width = layout.totalWidth();
        DynamicListWidget listWidget = new DynamicListWidget(0, 0, width);

        if (withTitle) {
            listWidget.addDrawableChild(ExecutableWidget.instance(0, 0, width, layout.buttonHeight())
                    .setElementHandler(displayFactory.createTitle(
                            TextProvider.of(getModuleName(baseModule)),
                            TooltipHandler.of(getModuleDescriptionTooltips(baseModule)))));
        }

        List<WrapperConfigRef<?>> editableConfigs = baseModule.getEditableConfig();
        Map<String, List<WrapperConfigRef<?>>> groupedConfigs = collectConfigSubGroups(editableConfigs);
        if (shouldUseConfigSubGroups(groupedConfigs)) {
            for (var entry : groupedConfigs.entrySet()) {
                String prefix = entry.getKey();
                if (shouldShowConfigSubGroupHead(prefix)) {
                    WidgetUtils.addConditionalWidgets(
                            listWidget,
                            List.of(createConfigSubGroupHead(
                                    baseModule, prefix, metaData, entry.getValue(), layout, displayFactory)));
                } else {
                    for (var configWidget : entry.getValue()) {
                        WidgetUtils.addConditionalWidgets(
                                listWidget, List.of(createBaseModuleConfigurateRow(configWidget, layout)));
                    }
                }
            }
        } else {
            for (var configWidget : editableConfigs) {
                WidgetUtils.addConditionalWidgets(
                        listWidget, List.of(createBaseModuleConfigurateRow(configWidget, layout)));
            }
        }
        baseModule.addCustomWidgets(
                listWidget::addDrawableChild, width, layout.buttonHeight(), layout.buttonBlank(), CONFIG_FACTORY);
        return listWidget;
    }

    public Map<String, List<WrapperConfigRef<?>>> collectConfigSubGroups(List<WrapperConfigRef<?>> editableConfigs) {
        Map<String, List<WrapperConfigRef<?>>> groupedConfigs = new LinkedHashMap<>();
        for (var configWidget : editableConfigs) {
            String prefix = getConfigSubGroupPrefix(configWidget);
            groupedConfigs.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(configWidget);
        }
        return groupedConfigs;
    }

    public boolean shouldUseConfigSubGroups(Map<String, List<WrapperConfigRef<?>>> groupedConfigs) {
        return enableConfigSubGroup.get() && groupedConfigs.size() > 1;
    }

    public boolean shouldShowConfigSubGroupHead(String prefix) {
        return prefix != null && !prefix.isEmpty();
    }

    private String getConfigSubGroupPrefix(WrapperConfigRef<?> configWidget) {
        String[] path = configWidget.path();
        if (path == null || path.length <= 1) {
            return "";
        }
        return String.join(".", Arrays.copyOf(path, path.length - 1));
    }

    private ConditionalWidget createConfigSubGroupHead(
            BaseModule baseModule,
            String prefix,
            ClickGuiMetaData metaData,
            List<WrapperConfigRef<?>> subs,
            WidgetUtils.ConfigScreenLayout layout,
            ElementFactory displayFactory) {
        int width = layout.totalWidth();
        int height = 12;
        int blank = layout.buttonBlank();
        String metaKey = getConfigSubGroupMetaKey(baseModule, prefix);
        metaData.checkSubGroupDefault(metaKey);
        DrawableWidget header = displayFactory.createLabel(
                TextProvider.of(getConfigSubGroupTitle(prefix)),
                TooltipHandler.of(List.of()),
                0,
                blank,
                width - height,
                height,
                true);
        DynamicListWidget group = new DynamicListWidget(0, 0, width);
        List<ConditionalWidget> widgets = subs.stream()
                .map(sub -> createBaseModuleConfigurateRow(sub, layout))
                .toList();
        WidgetUtils.addCollapsibleGroup(
                group,
                header,
                ValueAccessor.<Boolean>of(
                        () -> metaData.isSubGroupExpanded(metaKey),
                        value -> metaData.setSubGroupExpanded(metaKey, value)),
                widgets,
                displayFactory,
                width - height,
                blank,
                height,
                height);
        return new ConditionalWidget(() -> subs.stream().anyMatch(WrapperConfigRef::shouldShow), group);
    }

    public Component getConfigSubGroupTitle(String prefix) {
        return Component.translatable("config.index." + prefix);
    }

    public String getConfigSubGroupMetaKey(BaseModule baseModule, String prefix) {
        return baseModule.getModuleManager().getName() + "." + baseModule.getName() + ":" + prefix;
    }

    public String getSliderMetaKey(BaseModule baseModule) {
        return baseModule.getModuleManager().getName() + "." + baseModule.getName();
    }

    private ConditionalWidget createBaseModuleConfigurateRow(
            WrapperConfigRef<?> configWidget, WidgetUtils.ConfigScreenLayout layout) {
        int width = layout.totalWidth();
        int height = layout.buttonHeight();
        int blank = layout.buttonBlank();
        SubScreenWidget keyValue = new SubScreenWidget(0, 0, width, height + blank);
        keyValue.addDrawableChild(DisplayWidget.instance(0, 0, width, blank + height));
        DrawableWidget kvInputWidget = getKeyValueWidget(configWidget.ref(), configWidget.keyName(), layout);
        keyValue.addDrawableChild(kvInputWidget);
        BooleanSupplier showCondition = configWidget.showPredicate();
        return new ConditionalWidget(showCondition, keyValue);
    }

    public void openConfigurateScreen(BaseModule baseModule) {
        openConfigurateScreen(baseModule, getClickGuiMetadata());
    }

    public void openConfigurateScreen(BaseModule baseModule, ClickGuiMetaData metaData) {
        var listWidget = createBaseModuleConfigurateScreen(baseModule, metaData);
        Screen screen = new CenterScreen(listWidget);
        ScreenAccess.of(screen).addCloseFuture(() -> setClickGuiMeta(metaData));
        ScreenAccess.of(screen).openFromCurrent();
        // SubScreenWidget levelSubScreen = new SubScreenWidget(0, 0, 0,0);
    }

    {
        CONFIG_FACTORY = configElementFactory;
        ELEMENT_FACTORY = configElementFactory;
    }

    private @NotNull DrawableWidget getKeyValueWidget(Ref<?> wrapper, String keyName) {
        return getKeyValueWidget(wrapper, keyName, MODULE_CONFIG_LAYOUT);
    }

    private @NotNull DrawableWidget getKeyValueWidget(
            Ref<?> wrapper, String keyName, WidgetUtils.ConfigScreenLayout layout) {
        ElementFactory factory = CONFIG_FACTORY;
        return WidgetUtils.createKeyValueWidget(wrapper, keyName, layout, factory);
    }

    public void openConfigScreen() {
        ConfigMultiLevelListScreen screen =
                new ConfigMultiLevelListScreen(Config.REGISTRY.stream().toList(), CONFIG_FACTORY);
        ScreenAccess.of(screen).openFromCurrent();
    }

    public void openConfigScreen(Config config) {
        ConfigMultiLevelListScreen screen =
                new ConfigMultiLevelListScreen(Config.REGISTRY.stream().toList(), CONFIG_FACTORY);
        screen.setGlobal(config);
        ScreenAccess.of(screen).openFromCurrent();
    }

    public Map<String, Function<Screen, DrawableWidget>> getExtraSelections(ClickGuiMetaData meta) {
        Map<String, Function<Screen, DrawableWidget>> selections = new LinkedHashMap<>();
        selections.put("Friends", screen -> createFriendSettings(screen, meta));
        selections.put("CmdMacros", screen -> createCmdMacrosSettings(screen, meta));
        selections.put("Hotkeys", screen -> createKeyBindListSettings(screen, meta));
        selections.put("BaseSettings", screen -> createBaseSettings(screen, meta));
        selections.put("GuiSettings", screen -> createGuiSettings(screen, meta));
        selections.put("Font", screen -> createFontSettings(screen, meta));
        selections.put("Config", screen -> createConfig(meta));
        if (BaritoneHooks.getInstance().isBaritoneAPISupported()) {
            selections.put("Baritone", screen -> createBaritoneScreen(screen, meta));
        }
        return selections;
    }

    public DrawableWidget createBaseSettings(Screen screen, ClickGuiMetaData meta) {
        DrawableWidget widget = createBaseModuleConfigurateScreen(ModuleSettings.INSTANCE, meta);
        return WidgetUtils.createCenterScreenWidget(
                widget, screen.width, screen.height - 2 * ClassicClickGuiScreen.BUTTON_HEIGHT);
    }

    public DrawableWidget createGuiSettings(Screen screen, ClickGuiMetaData metaData) {
        DrawableWidget widget = createBaseModuleConfigurateScreen(ClickGui.INSTANCE, metaData);
        return WidgetUtils.createCenterScreenWidget(
                widget, screen.width, screen.height - 2 * ClassicClickGuiScreen.BUTTON_HEIGHT);
    }

    public DrawableWidget createFontSettings(Screen screen, ClickGuiMetaData metaData) {
        DrawableWidget widget = createBaseModuleConfigurateScreen(MainTasks.getTextFont(), metaData);
        return WidgetUtils.createCenterScreenWidget(
                widget, screen.width, screen.height - 2 * ClassicClickGuiScreen.BUTTON_HEIGHT);
    }

    public DrawableWidget createFriendSettings(Screen screen, ClickGuiMetaData meta) {
        DrawableWidget widget = createBaseModuleConfigurateScreen(TargetSelector.INSTANCE, meta);
        return WidgetUtils.createCenterScreenWidget(
                widget, screen.width, screen.height - 2 * ClassicClickGuiScreen.BUTTON_HEIGHT);
    }

    public DrawableWidget createCmdMacrosSettings(Screen screen, ClickGuiMetaData meta) {
        List<WrapperConfigRef<?>> configRefs = Stream.of(BindCommand.INSTANCE, EventCommand.INSTANCE)
                .flatMap(s -> s.getEditableConfig().stream())
                .toList();
        DrawableWidget widget = createConfigScreen(
                Component.translatable("widget.click-gui.selection.CmdMacros"),
                List::of,
                configRefs,
                WidgetUtils.DEFAULT_CONFIG_SCREEN_LAYOUT,
                CONFIG_FACTORY);
        return WidgetUtils.createCenterScreenWidget(
                widget, screen.width, screen.height - 2 * ClassicClickGuiScreen.BUTTON_HEIGHT);
    }

    public DrawableWidget createKeyBindListSettings(Screen screen, ClickGuiMetaData meta) {
        List<WrapperConfigRef<?>> allKeyBinds = HackModules.getModuleGroups().stream()
                .flatMap(s -> s.getModules().stream())
                .flatMap(s -> s.getEditableConfig().stream())
                .filter(s -> s.ref() instanceof KeyBindRef)
                .toList();
        DrawableWidget widget = createConfigScreen(
                Component.translatable("widget.click-gui.selection.Hotkeys"),
                List::of,
                allKeyBinds,
                WidgetUtils.DEFAULT_CONFIG_SCREEN_LAYOUT,
                CONFIG_FACTORY);
        return WidgetUtils.createCenterScreenWidget(
                widget, screen.width, screen.height - 2 * ClassicClickGuiScreen.BUTTON_HEIGHT);
    }

    private DrawableWidget createTest(ClickGuiMetaData meta) {
        return new SubScreenWidget(0, 0, 0, 0);
    }

    public DrawableWidget createConfig(ClickGuiMetaData meta) {
        var screen = new ConfigMultiLevelListScreen(Config.REGISTRY.stream().toList(), CONFIG_FACTORY);
        screen.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        return new ContentDelegateWidget<>(0, 0, 0, 0).setContentDelegate(screen);
    }

    public DrawableWidget createBaritoneScreen(Screen screen, ClickGuiMetaData metaData) {
        List<Pair<String, ValueAccessor>> baritones = BaritoneHooks.getInstance().getAllSettings().entrySet().stream()
                .map(entry -> Pair.of("widget.click-gui.baritone." + entry.getKey(), (ValueAccessor) entry.getValue()))
                .toList();
        var widget = WidgetUtils.createValueAccessorsEditScreen(
                Component.translatable("widget.click-gui.selection.Baritone"),
                List::of,
                (List) baritones,
                WidgetUtils.DEFAULT_CONFIG_SCREEN_LAYOUT,
                CONFIG_FACTORY,
                true);
        return WidgetUtils.createCenterScreenWidget(
                widget, screen.width, screen.height - 2 * ClassicClickGuiScreen.BUTTON_HEIGHT);
    }

    public DynamicListWidget createConfigScreen(
            Component title,
            Supplier<List<Component>> titleTooltips,
            List<BaseModule.WrapperConfigRef<?>> configs,
            WidgetUtils.ConfigScreenLayout layout,
            ElementFactory factory) {
        int width = layout.totalWidth();
        DynamicListWidget listWidget = new DynamicListWidget(0, 0, width);

        if (title != null) {
            listWidget.addDrawableChild(ExecutableWidget.instance(0, 0, width, layout.buttonHeight())
                    .setElementHandler(factory.createTitle(TextProvider.of(title), TooltipHandler.of(titleTooltips))));
        }

        for (var configWidget : configs) {
            SubScreenWidget keyValueRow =
                    new SubScreenWidget(0, 0, width, layout.buttonHeight() + layout.buttonBlank());
            keyValueRow.addDrawableChild(
                    DisplayWidget.instance(0, 0, width, layout.buttonBlank() + layout.buttonHeight()));
            keyValueRow.addDrawableChild(createKeyValueWidget(configWidget, layout, factory));
            WidgetUtils.addConditionalWidgets(
                    listWidget, List.of(new ConditionalWidget(configWidget.showPredicate(), keyValueRow)));
        }

        return listWidget;
    }

    private static SubScreenWidget createKeyValueWidget(
            BaseModule.WrapperConfigRef<?> wrapper, WidgetUtils.ConfigScreenLayout layout, ElementFactory factory) {
        return WidgetUtils.createKeyValueWidget(wrapper.ref(), wrapper.keyName(), layout, factory);
    }

    @Getter
    @AllArgsConstructor
    public static class SliderGroupMeta {
        private int x;
        private int y;

        public void setX(int x) {
            this.x = x;
        }

        public void setY(int y) {
            this.y = y;
        }

        public static Codec<SliderGroupMeta> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("x_coord").forGetter(SliderGroupMeta::getX),
                        Codec.INT.fieldOf("y_coord").forGetter(SliderGroupMeta::getY))
                .apply(instance, SliderGroupMeta::new));
    }

    @Getter
    public static class ClickGuiMetaData {
        Map<String, ModuleSlideMeta> moduleMetaMap;
        Map<String, SliderGroupMeta> sliderGroupMetaMap;
        Map<String, Boolean> subGroupMetaMap;
        Map<String, Boolean> sliderMetaMap;
        Map<Identifier, CompoundTag> extraDatas;

        @Setter
        String searching;

        public ClickGuiMetaData() {
            this.moduleMetaMap = new LinkedHashMap<>();
            this.sliderGroupMetaMap = new LinkedHashMap<>();
            this.subGroupMetaMap = new LinkedHashMap<>();
            this.sliderMetaMap = new LinkedHashMap<>();
            this.extraDatas = new LinkedHashMap<>();
            this.searching = "";
        }

        public ClickGuiMetaData(
                Map<String, ModuleSlideMeta> moduleCoordinates,
                Map<String, SliderGroupMeta> sliderGroupMetaMap,
                Map<String, Boolean> subGroupMetaMap,
                Map<String, Boolean> sliderMetaMap,
                String searching) {
            this(moduleCoordinates, sliderGroupMetaMap, subGroupMetaMap, sliderMetaMap, searching, Map.of());
        }

        private ClickGuiMetaData(
                Map<String, ModuleSlideMeta> moduleCoordinates,
                Map<String, SliderGroupMeta> sliderGroupMetaMap,
                Map<String, Boolean> subGroupMetaMap,
                Map<String, Boolean> sliderMetaMap,
                String searching,
                Map<Identifier, CompoundTag> extraDatas) {
            this.moduleMetaMap = new LinkedHashMap<>(moduleCoordinates);
            this.sliderGroupMetaMap = new LinkedHashMap<>(sliderGroupMetaMap);
            this.subGroupMetaMap = new LinkedHashMap<>(subGroupMetaMap);
            this.sliderMetaMap = new LinkedHashMap<>(sliderMetaMap);
            this.extraDatas = new LinkedHashMap<>(extraDatas);
            this.searching = searching;
        }

        private static ClickGuiMetaData fromCodec(
                Map<String, ModuleSlideMeta> moduleCoordinates,
                Map<String, SliderGroupMeta> sliderGroupMetaMap,
                Map<String, Boolean> subGroupMetaMap,
                Map<String, Boolean> sliderMetaMap,
                String searching,
                Map<Identifier, CompoundTag> extraDatas) {
            return new ClickGuiMetaData(
                    moduleCoordinates, sliderGroupMetaMap, subGroupMetaMap, sliderMetaMap, searching, extraDatas);
        }

        public CompoundTag getExtraData(Identifier id) {
            return extraDatas.computeIfAbsent(id, key -> new CompoundTag());
        }

        public void checkDefault(String moduleName, int x, int y) {
            if (!moduleMetaMap.containsKey(moduleName)) {
                ModuleSlideMeta newMeta = new ModuleSlideMeta(x, y, false, false);
                moduleMetaMap.put(moduleName, newMeta);
            }
        }

        public void checkSliderGroupDefault(String moduleName, int x, int y) {
            sliderGroupMetaMap.putIfAbsent(moduleName, new SliderGroupMeta(x, y));
        }

        public void checkSubGroupDefault(String key) {
            subGroupMetaMap.putIfAbsent(key, true);
        }

        public boolean isSubGroupExpanded(String key) {
            checkSubGroupDefault(key);
            return subGroupMetaMap.get(key);
        }

        public void setSubGroupExpanded(String key, boolean expanded) {
            subGroupMetaMap.put(key, expanded);
        }

        public void checkSliderDefault(String key) {
            sliderMetaMap.putIfAbsent(key, false);
        }

        public boolean isSliderExpanded(String key) {
            checkSliderDefault(key);
            return sliderMetaMap.get(key);
        }

        public void setSliderExpanded(String key, boolean expanded) {
            sliderMetaMap.put(key, expanded);
        }

        /**
         * 给还没有坐标的模块组排默认位置。
         *
         * <p>SUB_SCREEN 下每个模块组是<b>一整列</b>（表头 + 展开后的模块列表），高度不再固定，
         * 所以换行必须按「本行最高的一列」推进：旧实现固定走 {@code wY * 2}，只要上一行有一列是展开的，
         * 下一行就会排进那一列的内部（插件视图组就被压进 Extra 列里）。
         *
         * <p>旧实现留下的坐标（x 落在列步长上、y 落在旧行距上）视为自动排版的产物，一并重排；
         * 用户拖过的坐标不是这个形状，一律不动。
         */
        public void checkDefault(List<String> moduleNames, ToIntFunction<String> heightOf, int wX, int wY) {
            int screenWidth = mc.getWindow().getGuiScaledWidth();
            int x = 0;
            int y = DEFAULT_Y;
            int rowHeight = 0;
            for (String name : moduleNames) {
                if (x > 0 && x + wX > screenWidth) {
                    y += rowHeight + DEFAULT_GAP;
                    x = 0;
                    rowHeight = 0;
                }
                ModuleSlideMeta saved = moduleMetaMap.get(name);
                if (saved == null) {
                    moduleMetaMap.put(name, new ModuleSlideMeta(x, y, false, false));
                } else if (!saved.isManual() && isLegacyAutoPlacement(saved, wX, wY)) {
                    saved.setX(x);
                    saved.setY(y);
                } else if (!saved.isManual()) {
                    // 老数据里「不是旧自动排版」的位置就是用户自己放的，当场冻结，之后不再参与重排
                    saved.setManual(true);
                }
                rowHeight = Math.max(rowHeight, heightOf.applyAsInt(name));
                x += wX + DEFAULT_GAP;
            }
        }

        /** 坐标正好落在旧算法的格子点上 = 当初自动排的，可以重排。 */
        private static boolean isLegacyAutoPlacement(ModuleSlideMeta meta, int wX, int wY) {
            int columnStep = wX + DEFAULT_GAP;
            int rowStep = Math.max(1, wY * 2);
            if (meta.getX() < 0 || meta.getX() % columnStep != 0) {
                return false;
            }
            int offset = meta.getY() - DEFAULT_Y;
            return offset >= 0 && offset % rowStep == 0;
        }

        public ModuleSlideMeta getModuleMeta(String moduleName) {
            return Objects.requireNonNull(moduleMetaMap.get(moduleName));
        }

        public SliderGroupMeta getSliderGroupMeta(String moduleName) {
            return Objects.requireNonNull(sliderGroupMetaMap.get(moduleName));
        }

        public void checkSliderGroupDefault(List<String> moduleNames, int wX, int wY) {
            int cntX = 0;
            int yLevel = 0;
            for (String moduleName : moduleNames) {
                int x = cntX * (wX + DEFAULT_GAP);
                if (x + wX > mc.getWindow().getGuiScaledWidth()) {
                    cntX = 0;
                    x = 0;
                    yLevel += 1;
                }
                checkSliderGroupDefault(moduleName, x, DEFAULT_Y + yLevel * (wY * 2));
                cntX++;
            }
        }

        public static Codec<ClickGuiMetaData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.unboundedMap(Codec.STRING, ModuleSlideMeta.CODEC)
                                .fieldOf("module_list_metas")
                                .forGetter(ClickGuiMetaData::getModuleMetaMap),
                        Codec.unboundedMap(Codec.STRING, SliderGroupMeta.CODEC)
                                .optionalFieldOf("slider_group_metas", Map.of())
                                .forGetter(ClickGuiMetaData::getSliderGroupMetaMap),
                        Codec.unboundedMap(Codec.STRING, Codec.BOOL)
                                .optionalFieldOf("sub_group_metas", Map.of())
                                .forGetter(ClickGuiMetaData::getSubGroupMetaMap),
                        Codec.unboundedMap(Codec.STRING, Codec.BOOL)
                                .optionalFieldOf("slider_metas", Map.of())
                                .forGetter(ClickGuiMetaData::getSliderMetaMap),
                        Codec.STRING.optionalFieldOf("searching", "").forGetter(ClickGuiMetaData::getSearching),
                        Codec.unboundedMap(Identifier.CODEC, CompoundTag.CODEC)
                                .optionalFieldOf("extra_datas", Map.of())
                                .forGetter(ClickGuiMetaData::getExtraDatas))
                .apply(instance, ClickGuiMetaData::fromCodec));
    }

    @Getter
    @AllArgsConstructor
    public static class ModuleSlideMeta {
        private int x;
        private int y;
        // limit, do not move out of bound
        public void setX(int x) {
            this.x = x;
        }

        public void setY(int y) {
            this.y = y;
        }

        boolean slidingDown;

        public void setSlidingDown(boolean slidingDown) {
            this.slidingDown = slidingDown;
        }

        /** 用户拖过（或旧数据首次判定为手工布局）后为 true，自动排布不再重排这一组。 */
        boolean manual;

        public void setManual(boolean manual) {
            this.manual = manual;
        }

        public static Codec<ModuleSlideMeta> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("x_coord").forGetter(ModuleSlideMeta::getX),
                        Codec.INT.fieldOf("y_coord").forGetter(ModuleSlideMeta::getY),
                        Codec.BOOL.fieldOf("sliding_down").forGetter(ModuleSlideMeta::isSlidingDown),
                        Codec.BOOL.optionalFieldOf("manual", false).forGetter(ModuleSlideMeta::isManual))
                .apply(instance, ModuleSlideMeta::new));
    }
}
