package me.matl114.gui.complex.other;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.List;
import javax.annotation.Nullable;
import me.matl114.accessors.access.MerchantScreenAccess;
import me.matl114.gui.basic.*;
import me.matl114.gui.elements.ButtonElement;
import me.matl114.gui.elements.IconElement;
import me.matl114.gui.elements.LabelElement;
import me.matl114.gui.elements.SlotElement;
import me.matl114.hacks.InvTasks;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.ScreenUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

public class TradeInformationSubScreen extends SubScreenWidget {
    private static final int SLOT_WIDTH = 18;
    private static final int BUTTON_WIDTH = 18;
    private static final int BUTTON_HEIGHT = 8;
    private static final int TRADE_ICON_WIDTH = 10;
    private static final int TRADE_ICON_HEIGHT = 9;
    private static final Identifier TRADE_ARROW_OUT_OF_STOCK_TEXTURE =
            Identifier.withDefaultNamespace("container/villager/out_of_stock");
    private static final Identifier TRADE_ARROW_TEXTURE_SPRITE = new Identifier("slimefunhelper", "gui/trade_arrow");
    private static final Minecraft mc = Minecraft.getInstance();
    private MerchantScreen screen;

    public TradeInformationSubScreen(int x, int y, MerchantScreen screen) {
        super(
                x + 258 - (3 * (SLOT_WIDTH + 2) + TRADE_ICON_WIDTH + 2 + BUTTON_WIDTH),
                y + 60,
                3 * (SLOT_WIDTH + 2) + TRADE_ICON_WIDTH + 2 + BUTTON_WIDTH,
                20);
        this.screen = Preconditions.checkNotNull(screen);
        this.init();
    }

    @Nullable
    public MerchantOffer getCurrentTrade() {
        MerchantScreenAccess access = MerchantScreenAccess.of(this.screen);
        int index = access.getSelectedIndex();
        MerchantOffers list = this.screen.getMenu().getOffers();
        if (index < 0 || index >= list.size()) {
            return null;
        } else {
            return list.get(index);
        }
    }

    private boolean active(ElementHandler ignored) {
        return getCurrentTrade() != null;
    }

    private static final Component LABEL_TRADE = Component.translatable("widget.fast-trade.trade");
    private static final List<Component> TOOLTIPS_TRADE =
            List.of(Component.translatable("widget.fast-trade.trade.tooltips"));
    private static final Component LABEL_DROP_CRAFT = Component.translatable("widget.fast-trade.toggle-drop");
    private static final List<Component> TOOLTIPS_DROPCRAFT =
            List.of(Component.translatable("widget.fast-trade.toggle-drop.tooltips"));

    protected void init() {

        ExecutableWidget.instance(1, 1, SLOT_WIDTH, SLOT_WIDTH)
                .setElementHandler(new SlotElement(
                                InventoryUtils.createReadOnlyOneItemInventory(() -> {
                                    var trade = getCurrentTrade();
                                    return trade == null ? ItemStack.EMPTY : trade.getCostA();
                                }),
                                0,
                                InvTasks.getRightClickOpenEditScreenCallback())
                        .withPresentCondition(this::active))
                .addToSub(this);
        ExecutableWidget.instance(SLOT_WIDTH + 2 + 1, 1, SLOT_WIDTH, SLOT_WIDTH)
                .setElementHandler(new SlotElement(
                                InventoryUtils.createReadOnlyOneItemInventory(() -> {
                                    var trade = getCurrentTrade();
                                    return trade == null ? ItemStack.EMPTY : (trade.getCostB());
                                }),
                                0,
                                InvTasks.getRightClickOpenEditScreenCallback())
                        .withPresentCondition(this::active))
                .addToSub(this);
        DisplayWidget.instance(2 * (SLOT_WIDTH + 2), 5, TRADE_ICON_WIDTH, TRADE_ICON_HEIGHT)
                .setRenderHandler(IconElement.statedGuiPredicate(
                                TRADE_ARROW_TEXTURE_SPRITE,
                                TRADE_ARROW_OUT_OF_STOCK_TEXTURE,
                                ButtonAction.empty(),
                                (el) -> {
                                    var trade = getCurrentTrade();
                                    return trade != null && !trade.isOutOfStock();
                                })
                        //                    .setShaderColor(Constants.SLOT_COLOR)
                        .withTooltips(TooltipHandler.of(() -> {
                            var trade = getCurrentTrade();
                            if (trade == null) return List.of();
                            var builder = ImmutableList.<Component>builder();
                            builder.add(Component.translatable("widget.fast-trade.trade-info.0")
                                    .withStyle(ChatFormatting.AQUA));
                            builder.add(Component.translatable("widget.fast-trade.trade-info.1")
                                    .withStyle(ChatFormatting.GREEN));
                            builder.add(Component.literal("------------------").withStyle(ChatFormatting.GREEN));
                            builder.add(Component.translatable(
                                    "widget.fast-trade.trade-info.max-trade", String.valueOf(trade.getMaxUses())));
                            builder.add(Component.translatable(
                                    "widget.fast-trade.trade-info.current-trade", String.valueOf(trade.getUses())));
                            builder.add(Component.translatable(
                                    "widget.fast-trade.trade-info.default-count",
                                    String.valueOf(trade.getItemCostA().count())));
                            builder.add(Component.translatable(
                                    "widget.fast-trade.trade-info.price-multiplier",
                                    "%.2f".formatted(trade.getPriceMultiplier())));
                            builder.add(Component.translatable(
                                    "widget.fast-trade.trade-info.demand-bonus", String.valueOf(trade.getDemand())));
                            builder.add(Component.translatable(
                                    "widget.fast-trade.trade-info.special-price",
                                    String.valueOf(trade.getSpecialPriceDiff())));
                            return builder.build();
                        }))
                        .withPresentCondition(this::active))
                .addToSub(this);
        ExecutableWidget.instance(2 * (SLOT_WIDTH + 2) + TRADE_ICON_WIDTH + 1, 0, SLOT_WIDTH, SLOT_WIDTH)
                .setElementHandler(new SlotElement(
                                InventoryUtils.createReadOnlyOneItemInventory(() -> {
                                    var trade = getCurrentTrade();
                                    return trade == null ? ItemStack.EMPTY : (trade.getResult());
                                }),
                                0,
                                InvTasks.getRightClickOpenEditScreenCallback())
                        .withPresentCondition(this::active))
                .addToSub(this);
        ExecutableWidget.instance(3 * (SLOT_WIDTH + 2) + TRADE_ICON_WIDTH + 1, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
                .setElementHandler(new ButtonElement(TextProvider.of(LABEL_TRADE), ButtonAction.run(() -> {
                            if (ScreenUtils.hasShiftDown()) {
                                craft();
                            } else {
                                place();
                            }
                        }))
                        .withTooltips(TooltipHandler.of(TOOLTIPS_TRADE)))
                .addToSub(this);
        ExecutableWidget.instance(
                        3 * (SLOT_WIDTH + 2) + TRADE_ICON_WIDTH + 1, 4 + BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT)
                .setElementHandler(new ButtonElement(
                                TextProvider.of(LABEL_DROP_CRAFT),
                                ButtonAction.run(HotKeyUtils.wrapFlagAsToggle(
                                        "fast-craft.drop-craft", InvTasks.getFastCraft().dropCraft)))
                        .withTooltips(TooltipHandler.of(TOOLTIPS_DROPCRAFT)))
                .addToSub(this);
        DisplayWidget.instance(10, 0, 3 * (SLOT_WIDTH + 2) + TRADE_ICON_WIDTH - 20, 20)
                .setRenderHandler(LabelElement.instance(Component.translatable("widget.fast-trade.no-select")
                                .withStyle(ChatFormatting.RED))
                        .withPresentCondition((v) -> !this.active(v)))
                .addToSub(this);
    }

    private void place() {
        if (mc.player != null) {
            var access = MerchantScreenAccess.of(this.screen);
            access.setSelectedIndex(access.getSelectedIndex());
        }
    }

    private void craft() {
        if (mc.player != null) {
            place();
            MerchantOffer offer = getCurrentTrade();
            if (offer != null) {
                ItemStack output = offer.getResult();
                if (!output.isEmpty()) {
                    int maxCraft = (int) Math.ceil((float) output.getMaxStackSize() / (float) output.getCount());
                    maxCraft = Math.min(maxCraft, offer.getMaxUses() - offer.getUses());
                    InvTasks.getFastCraft().craftAtSlotIndex(this.screen, output, maxCraft, 2);
                }
            }
        }
    }
}
