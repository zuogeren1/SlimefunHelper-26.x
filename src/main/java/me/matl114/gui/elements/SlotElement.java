package me.matl114.gui.elements;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.matl114.gui.basic.*;
import me.matl114.plugins.MainPlugin;
import me.matl114.utils.InventoryUtils;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public class SlotElement extends AbstractElement {
    final Container inventory;
    final int index;
    final SlotClickCallback callback;
    protected final ColorSampler color;
    protected static final Identifier SLOT_RESOURCE = MainPlugin.identifier("textures/custom/recipecontainer.png");
    protected static final int u0 = 0;
    protected static final int v0 = 222;
    protected static final int vheight = 18;
    protected static final int uheight = 18;

    public SlotElement(ItemStack itemStack) {
        this(new SimpleContainer(itemStack), 0, ColorSampler.WHITE);
    }

    public SlotElement(ItemStack itemStack, ColorSampler sampler) {
        this(new SimpleContainer(itemStack), 0, sampler);
    }

    public static SlotElement instance(ItemStack itemStack) {
        return new SlotElement(itemStack);
    }

    public static SlotElement instance(ItemStack itemStack, SlotClickCallback handle) {
        return new SlotElement(itemStack, handle);
    }

    public SlotElement(ItemStack itemStack, SlotClickCallback callback) {
        this(new SimpleContainer(itemStack), 0, ColorSampler.WHITE, callback);
    }

    public SlotElement(Supplier<ItemStack> sup) {
        this(InventoryUtils.createReadOnlyOneItemInventory(sup));
    }

    public SlotElement(Container inventory) {
        this(inventory, 0, ColorSampler.WHITE, SlotClickCallback.DEFAULT);
    }

    public SlotElement(Container inventory, int index) {
        this(inventory, index, ColorSampler.WHITE, SlotClickCallback.DEFAULT);
    }

    public SlotElement(Container inventory, int index, ColorSampler color) {
        this(inventory, index, color, SlotClickCallback.DEFAULT);
    }

    public SlotElement(Container inventory, int index, SlotClickCallback callback) {
        this(inventory, index, ColorSampler.WHITE, callback);
    }

    public SlotElement(Container inventory, int index, ColorSampler color, SlotClickCallback callback) {
        this.inventory = inventory;
        this.index = index;
        this.callback = callback;
        this.color = color;
    }

    protected void renderSlotFrame(VDrawContext context) {
        context.setShaderColor(this.color.getColorInt());
        try {
            context.drawTexture(SLOT_RESOURCE, 0, 0, u0, v0, uheight, vheight);
        } finally {
            context.setShaderColor(-1);
        }
    }

    @Override
    public void renderCentered0(
            DrawableWidget element,
            VDrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            float alpha,
            boolean shouldHighlight) {
        // render slot here
        context.setShaderAlpha(alpha);
        float scalerX = (float) element.getTextureWidth() / uheight;
        float scalerY = (float) element.getTextureHeight() / vheight;
        boolean shouldPush = scalerX != 1.0F || scalerY != 1.0F;
        if (shouldPush) {
            context.getMatrices().pushMatrix();
            context.getMatrices().scale(scalerX, scalerY);
        }

        // use matrices because item will be rendered later
        if (slotFrame) {
            renderSlotFrame(context);
        }
        context.setShaderAlpha(1.0F);
        ItemStack stack = inventory.getItem(index);
        RenderHandler.drawSingleItem(context, stack, 1, 1, isInSlot);
        if (shouldHighlight) {
            context.fillGuiGradient(1, 1, 1 + 16, 1 + 16, -2130706433, -2130706433, 0);
        }
        if (shouldPush) {
            context.getMatrices().popMatrix();
        }
    }

    private boolean slotFrame = true;
    private boolean isInSlot = true;

    public SlotElement setSlotFrame(boolean slot) {
        this.slotFrame = slot;
        return this;
    }

    public SlotElement setInSlot(boolean slot) {
        this.isInSlot = slot;
        return this;
    }

    private BooleanSupplier tooltips = () -> true;

    public SlotElement setShowItemTooltips(boolean tooltips) {
        return setShowItemTooltips(() -> tooltips);
    }

    public SlotElement setShowItemTooltips(BooleanSupplier tooltips) {
        this.tooltips = tooltips;
        return this;
    }

    public AbstractElement withTooltips(TooltipHandler handler) {
        if (handler == null) return this;
        tooltips = () -> false;
        return super.withTooltips(handler);
    }

    @Override
    public void renderExtra0(
            DrawableWidget element,
            VDrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            float alpha,
            boolean shouldHighlight) {
        // draw tooltips here;
        super.renderExtra0(element, context, mouseX, mouseY, delta, alpha, shouldHighlight);
        if (shouldHighlight && tooltips != null && tooltips.getAsBoolean()) {
            ItemStack stack = inventory.getItem(index);
            if (!stack.isEmpty()) {
                context.drawTooltip(
                        mc.font,
                        stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.ADVANCED),
                        stack.getTooltipImage(),
                        mouseX,
                        mouseY);
            }
        }
    }

    @Override
    public boolean onClick(ExecutableWidget element, double mouseX, double mouseY, int button) {
        return callback.handle(inventory.getItem(index), button);
    }

    public static interface SlotClickCallback {
        SlotClickCallback DEFAULT = (i, b) -> false;

        public boolean handle(ItemStack item, int button);
    }
}
