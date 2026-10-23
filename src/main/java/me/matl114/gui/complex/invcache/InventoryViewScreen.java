package me.matl114.gui.complex.invcache;

import com.google.common.collect.Streams;
import java.util.List;
import java.util.stream.IntStream;
import me.matl114.accessors.interfaces.TileInventory;
import me.matl114.gui.GenericBackGroundScreen;
import me.matl114.gui.GridSubScreen;
import me.matl114.gui.basic.*;
import me.matl114.gui.elements.LabelElement;
import me.matl114.gui.elements.SlotElement;
import me.matl114.hacks.InvTasks;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.ScreenUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.commons.lang3.function.Consumers;

public class InventoryViewScreen extends GenericBackGroundScreen {
    protected BlockPos blockPos;
    protected ClientLevel blockWorld;
    //    protected HandledScreen<?> handledScreen;
    protected final GridSubScreen<DrawableWidget> grid;
    protected final GridSubScreen<DrawableWidget> modifyPlayerInventoryGrid;
    //    protected ContentDelegateWidget<GridSubScreen<DrawableWidget>> gridDelegate;
    protected final int DATA_OCCUPIED = 20;
    protected SlotElement iconStack;
    protected boolean modifiable = false;

    public InventoryViewScreen(AbstractContainerScreen<?> handledScreen) {
        this(
                handledScreen.getMenu().slots.stream()
                        .filter(i -> !(i.container instanceof Inventory))
                        .toList(),
                handledScreen.getTitle(),
                InvTasks.generateIconForScreen(handledScreen));
        if (handledScreen instanceof TileInventory tile && !tile.isVirtual()) {
            blockPos = tile.getPos();
            this.blockWorld = tile.getWorld();
        }
    }

    private static List<Slot> streamInventoryToSlot(Container inventory, int size) {
        return IntStream.range(0, size)
                .mapToObj((i) -> new Slot(inventory, i, 0, 0))
                .toList();
    }

    public InventoryViewScreen(Container inventory, Component title, ItemStack icon) {
        this(inventory, title, icon, false);
    }

    public InventoryViewScreen(Container inventory, Component title, ItemStack icon, boolean modifiable) {
        this(streamInventoryToSlot(inventory, inventory.getContainerSize()), title, icon, modifiable);
    }

    public InventoryViewScreen(List<Slot> list, Component title, ItemStack icon) {
        this(list, title, icon, false);
    }

    public InventoryViewScreen(List<Slot> list, Component title, ItemStack icon, boolean modifiable) {
        super(title, 240, 320);
        this.grid = new GridSubScreen<>(40, TITLE_OCCUPIED + DATA_OCCUPIED, 160, 120, 16, 16);

        this.grid.refreshPage(list, this::makeIcon, 1);
        this.iconStack = SlotElement.instance(icon);
        this.modifiable = modifiable;
        if (mc.player != null) {
            this.modifyPlayerInventoryGrid =
                    new GridSubScreen<>(40, TITLE_OCCUPIED + DATA_OCCUPIED + 120 + DATA_OCCUPIED, 160, 120, 16, 16);
            this.modifyPlayerInventoryGrid.refreshPage(
                    streamInventoryToSlot(mc.player.getInventory(), InventoryUtils.getPlayerInvSize()),
                    this::makeSelfInventoryView,
                    1);
        } else {
            this.modifyPlayerInventoryGrid = null;
        }
    }

    @Override
    protected void init() {
        super.init();
        ExecutableWidget.instance(this.x + 40, this.y + TITLE_OCCUPIED + 2, 16, 16)
                .setElementHandler(this.iconStack)
                .addTo(this);
        ElementHandler labelElement;
        if (blockPos != null) {
            labelElement = LabelElement.instance(Component.literal("%s [%d, %d, %d] "
                                    .formatted(
                                            this.blockWorld
                                                    .dimension()
                                                    .identifier()
                                                    .toString(),
                                            blockPos.getX(),
                                            blockPos.getY(),
                                            blockPos.getZ()))
                            .append(Component.translatable("widget.gui.inventory-view-screen.click-slot")))
                    .withInputHandler(InputHandler.run(() -> {
                        Minecraft.getInstance()
                                .keyboardHandler
                                .setClipboard("%d %d %d".formatted(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
                    }))
                    .withTooltips(TooltipHandler.of(Streams.concat(
                                    ChatUtils.parseTooltipsTranslation(
                                            "widget.gui.inventory-view-screen.click-slot.tooltips", "")
                                            .stream(),
                                    ChatUtils.parseTooltipsTranslation(
                                            "widget.gui.inventory-view-screen.copy-coordinate.tooltips", "")
                                            .stream())
                            .toList()));
        } else {
            labelElement = LabelElement.instance(
                            Component.translatable("widget.gui.inventory-view-screen.virtual-screen")
                                    .append(Component.translatable("widget.gui.inventory-view-screen.click-slot")))
                    .withTooltips(TooltipHandler.of(ChatUtils.parseTooltipsTranslation(
                            "widget.gui.inventory-view-screen.click-slot.tooltips", "")));
        }

        ExecutableWidget.instance(this.x + 60, this.y + TITLE_OCCUPIED, 140, DATA_OCCUPIED)
                .setElementHandler(labelElement)
                .addTo(this);
        new ContentDelegateWidget<>(this.x, this.y, 0, 0)
                .setContentDelegate(this.grid)
                .addTo(this);
        ExecutableWidget.instance(this.x + 40, this.y + TITLE_OCCUPIED + DATA_OCCUPIED + 120, 160, DATA_OCCUPIED)
                .setElementHandler(LabelElement.instance(
                                Component.translatable("widget.gui.inventory-view-screen.player-inventory-view")
                                        .append(Component.translatable("widget.gui.inventory-view-screen.click-slot")))
                        .withTooltips(TooltipHandler.of(ChatUtils.parseTooltipsTranslation(
                                "widget.gui.inventory-view-screen.player-inventory-view.tooltips", ""))))
                .addTo(this);

        new ContentDelegateWidget<>(this.x, this.y, 0, 0)
                .setContentDelegate(this.modifyPlayerInventoryGrid)
                .addTo(this);
        // cursor render
        DisplayWidget.instance(0, 0, this.width, this.height)
                .setRenderHandler(((element, context, mouseX, mouseY, delta, alpha, shouldHighlight) -> {
                    if (cursorStack != null && !cursorStack.isEmpty()) {
                        context.drawItem(cursorStack, mouseX - 8, mouseY - 8, 999, 0);
                        context.drawItemInSlot(context.getTextDrawer(), cursorStack, mouseX - 8, mouseY - 8, null);
                    }
                }))
                .addTo(this);
        ;

        ExecutableWidget.instance(0, 0, this.width, this.height)
                .setInputHandler(((element, mouseX, mouseY, button) -> {
                    cursorStack = ItemStack.EMPTY;
                    return false;
                }))
                .setPriority(-1)
                .addTo(this);
        ;
    }

    // 26.2: ItemStack 必须在组件绑定之后才能构造，改为首次访问时创建
    private static ItemStack iconUnknownCache = null;

    protected static ItemStack iconUnknown() {
        if (iconUnknownCache == null) {
            iconUnknownCache = new ItemStack(Items.BARRIER);
        }
        return iconUnknownCache;
    }

    protected ItemStack cursorStack = ItemStack.EMPTY;

    protected DrawableWidget makeIcon(Slot screen) {
        return ExecutableWidget.instance(0, 0, 16, 16)
                .setElementHandler(new SlotElement(screen.container, screen.getContainerSlot(), (stack, i) -> {
                            if (ScreenUtils.hasShiftDown()) {
                                shiftClickItem(stack, screen);
                                return true;
                            } else {
                                if (cursorStack != null && !cursorStack.isEmpty()) {
                                    if (modifiable) {
                                        placeItem(screen, cursorStack);
                                        return true;
                                    }
                                    return false;
                                }
                                if (i == 1) {
                                    return rightClickItem(stack, screen);
                                } else if (i == 0) {
                                    return leftClickItem(stack, screen);
                                }
                            }

                            return false;
                        })
                        .setShowItemTooltips(() -> cursorStack == null || cursorStack.isEmpty()));
    }

    protected DrawableWidget makeSelfInventoryView(Slot screen) {
        return ExecutableWidget.instance(0, 0, 16, 16)
                .setElementHandler(new SlotElement(screen.container, screen.getContainerSlot(), (stack, i) -> {
                            return pickupItem(screen);
                        })
                        .setShowItemTooltips(() -> cursorStack == null || cursorStack.isEmpty()));
    }

    protected void shiftClickItem(ItemStack stack, Slot slot) {
        if (modifiable && !stack.isEmpty()) {
            placeItem(slot, ItemStack.EMPTY);
        }
    }

    protected boolean leftClickItem(ItemStack stack, Slot slot) {
        return pickupItem(slot);
    }

    protected boolean rightClickItem(ItemStack stack, Slot slot) {
        if (stack.isEmpty()) {
            if (modifiable) {
                stack = new ItemStack(Items.STONE);
            } else {
                return true;
            }
        }
        InvTasks.openEditScreen(
                stack,
                modifiable
                        ? (newStack) -> {
                            placeItem(slot, newStack);
                        }
                        : Consumers.nop());
        return true;
    }

    protected boolean pickupItem(Slot slot) {
        if (cursorStack == null || cursorStack.isEmpty()) {
            if (!slot.getItem().isEmpty()) {
                cursorStack = slot.getItem().copy();
                return true;
            }
        }
        return false;
    }

    protected void placeItem(Slot slot, ItemStack stack) {
        if (modifiable) {
            try {
                slot.setByPlayer(stack);
            } catch (Throwable e) {
            }
        }
    }
}
