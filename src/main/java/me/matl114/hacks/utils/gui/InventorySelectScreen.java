package me.matl114.hacks.utils.gui;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import me.matl114.accessors.gui.ScreenAccess;
import me.matl114.gui.FilterService;
import me.matl114.gui.GenericBackGroundScreen;
import me.matl114.gui.basic.*;
import me.matl114.gui.elements.SlotElement;
import me.matl114.gui.presets.grids.GridSelectSubScreen;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.RenderTasks;
import me.matl114.hacks.modules.inv.ChestHistory;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.config.ValueAccessor;
import me.matl114.utils.world.ContainerPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class InventorySelectScreen extends GenericBackGroundScreen {
    private static final Component TITLE = Component.translatable("widget.gui.inventory-select-screen.title");

    private final GridSelectSubScreen<ChestHistory.Entry> grid;

    private static final int PAGE_LABEL_HEIGHT = 12;
    public static String currentInputFilter = "";

    public InventorySelectScreen(Supplier<List<ChestHistory.Entry>> handledScreens) {
        super(TITLE, 240, 320);
        this.grid = new GridSelectSubScreen<>(
                0,
                TITLE_OCCUPIED,
                this.backgroundWidth,
                PAGE_LABEL_HEIGHT,
                0,
                this.backgroundHeight - LABEL_OCCUPIED,
                -4,
                16,
                16,
                16,
                handledScreens,
                this::filterInventory,
                ValueAccessor.of(() -> currentInputFilter, (s) -> currentInputFilter = s),
                this::makeIcon);
    }

    private boolean filterVirtual = true;

    protected List<Component> provideTitleTooltips(DrawableWidget widget) {
        return List.of(
                Component.translatable("widget.gui.inventory-select-screen.filter-rule.toggle"),
                Component.empty(),
                Component.translatable(
                        filterVirtual
                                ? "widget.gui.inventory-select-screen.filter-rule.reject-virtual"
                                : "widget.gui.inventory-select-screen.filter-rule.accept-virtual"));
    }

    protected void runClickTitle(boolean isLeft) {
        filterVirtual = !filterVirtual;
        this.grid.refresh();
    }

    protected DrawableWidget makeIcon(ChestHistory.Entry screen) {
        List<Component> description = new ArrayList<>();
        description.add(Component.translatable(
                "widget.gui.inventory-select-screen.container-title",
                screen.getTitle().orElse(Component.empty())));
        description.add(Component.translatable("widget.gui.inventory-select-screen.preview"));
        description.add(Component.translatable("widget.gui.inventory-select-screen.render-position"));
        description.add(Component.empty());
        final ItemStack icon = screen.getChestType().map(ItemStack::new).orElse(InvTasks.invIconUnknown());

        return ExecutableWidget.instance(0, 0, 16, 16)
                .setElementHandler(SlotElement.instance(icon == null ? InvTasks.invIconUnknown() : icon)
                        .setSlotFrame(false)
                        .withInputHandler(InputHandler.isLeft((l) -> {
                            if (l) {
                                openInventoryViewScreen(screen);
                            } else if (screen.getContainerPosition().isPresent()
                                    && Objects.equals(
                                            Minecraft.getInstance().level.dimension(),
                                            screen.getContainerPosition().get().world())) {
                                ContainerPosition pos =
                                        screen.getContainerPosition().get();
                                RenderTasks.registerVirtualRenderTask(new RenderTasks.RenderTask(
                                        120,
                                        new RenderTasks.BoxObject(pos.getBoundingBox(), Color.GREEN),
                                        new RenderTasks.LineToTargetObject(pos.getCenterPosition(), Color.RED)));
                                this.onClose();
                            }
                        }))
                        .withTooltips(TooltipHandler.of(() -> {
                            List<Component> tooltips = new ArrayList<>(description);
                            if (screen.getContainerPosition().isPresent()) {
                                ContainerPosition containerPosition =
                                        screen.getContainerPosition().get();
                                BlockPos pos = containerPosition.getFirst().getPos();
                                Component position = ScreenUtils.hasCtrlDown()
                                        ? Component.literal("[%d, %d, %d]".formatted(pos.getX(), pos.getY(), pos.getZ()))
                                                .withStyle(ChatFormatting.GREEN)
                                        : Component.translatable("widget.gui.inventory-view-screen.press-ctrl-to-show");
                                tooltips.add(Component.translatable(
                                        "widget.gui.inventory-select-screen.recorded-position", position));
                                tooltips.add(Component.translatable(
                                        "widget.gui.inventory-select-screen.recorded-world",
                                        containerPosition.world().identifier().toString()));
                            } else {
                                tooltips.add(Component.translatable("widget.gui.inventory-select-screen.virtual-container")
                                        .withStyle(ChatFormatting.YELLOW));
                            }
                            return tooltips;
                        })));
    }

    protected void openInventoryViewScreen(ChestHistory.Entry screen) {
        InventoryViewScreen viewScreen;
        if (screen.getOptionalScreen() != null) {
            viewScreen = new InventoryViewScreen(screen.getOptionalScreen());
        } else {
            viewScreen = new InventoryViewScreen(
                    screen.getInventory(),
                    screen.getTitle().orElse(Component.empty()),
                    screen.getChestType().map(ItemStack::new).orElse(InvTasks.invIconUnknown()));
        }
        screen.getContainerPosition().ifPresent(viewScreen::withContainerPosition);
        ScreenAccess.of(viewScreen).openFromCurrent();
    }

    protected boolean filterInventory(String value, ChestHistory.Entry screen) {
        return (!filterVirtual || (screen.getContainerPosition().isPresent()))
                && (FilterService.nameMatch(
                                screen.getTitle()
                                        .orElse(Component.empty())
                                        .getString()
                                        .replace("§.", ""),
                                value)
                        || InventoryUtils.streamInventory(screen.getInventory())
                                .filter(i -> !i.isEmpty())
                                .map(i -> i.getHoverName().getString().replace("§.", ""))
                                .anyMatch(i -> FilterService.nameMatch(i, value)));
    }

    protected void refreshWidgets() {
        super.refreshWidgets();
        int availableRenderSpace = this.backgroundHeight - LABEL_OCCUPIED;
        this.grid.resetGridHeightAndRefresh(availableRenderSpace);
        new ContentDelegateWidget<>(this.x, this.y, 0, 0)
                .setContentDelegate(this.grid)
                .addTo(this);
    }
}
