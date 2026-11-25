package me.matl114.hacks.modules.inv;

import java.util.List;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.MouseScrollAction;
import me.matl114.events.impl.TooltipsRender;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.*;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.ItemStackUtils;
import me.matl114.versioned.api.VItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TextComponentTagVisitor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class InvTooltips extends BaseModule {
    public final ModulePath invTooltips = makePath(Configs.INV_CONFIG, "item-editor.inv-tooltips");
    public final ModulePath nbtTooltips = invTooltips.add("nbt-tooltips");
    public final ModulePath tooltipsScroller = invTooltips.add("tooltips-scroller");

    public InvTooltips() {
        super("InvTooltips");
    }

    public final FlagRef enable = flagBuilder(nbtTooltips.add("enable")).build();

    public final KeyBindRef keyBind = hotkey(nbtTooltips.add("show-hotkey"))
            .defaultValue(new MultiKeyBind(KeyCode.KEY_LEFT_ALT))
            .build();

    public final FlagRef scrollingTooltips =
            flagBuilder(tooltipsScroller.add("enable")).build();

    public final IntRef width = intBuilder(nbtTooltips.add("width"))
            .defaultValue(360)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef formatedWidth = intBuilder(nbtTooltips.add("format-indent"))
            .defaultValue(0)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    private int lastTooltipRenderTick = -1;
    private double tooltipScrollAmount;

    private static final int SCROLL_PIXELS_PER_NOTCH = 10;

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(RenderListener.getTooltipShow(), this::onTooltipsAppend);
        registerListener(RenderListener.getRenderTooltipEvent(), this::onTooltipRender);
        registerListener(Listener.getMouseScroll(), this::onTooltipScroll);
    }

    public void onTooltipsAppend(Event<List<Component>> renderEvent) {
        if (enable.get() && keyBind.get().isAllPressed()) {
            ItemStack stack = renderEvent.getArgs(0);
            renderEvent.context.addAll(getTooltipLines(stack));
        }
    }

    private void onTooltipScroll(Event<MouseScrollAction> event) {
        if (!isScrollingActive()) {
            tooltipScrollAmount = 0;
            return;
        }

        double vertical = event.context.vertical();
        if (vertical != 0) {
            tooltipScrollAmount -= vertical * SCROLL_PIXELS_PER_NOTCH;
            event.cancel();
        }
    }

    private void onTooltipRender(Event<TooltipsRender> event) {
        if (isScrollingActive()) {
            event.context.y += (int) tooltipScrollAmount;
        } else {
            tooltipScrollAmount = 0;
        }
        lastTooltipRenderTick = Tasks.getTick();
    }

    private boolean isScrollingActive() {
        long ticksSinceRender = (long) Tasks.getTick() - lastTooltipRenderTick;
        return scrollingTooltips.get() && lastTooltipRenderTick >= 0 && ticksSinceRender >= 0 && ticksSinceRender <= 1;
    }

    public List<Component> getTooltipLines(ItemStack stack) {
        CompoundTag nbtCompound = getSimplifiedNbt(stack);
        Component text = new TextComponentTagVisitor(" ".repeat(formatedWidth.get())).visit(nbtCompound);
        return ChatUtils.splitToMultiLineText(text, width.get());
    }

    public CompoundTag getSimplifiedNbt(ItemStack stack) {
        CompoundTag nbtCompound = VItem.getInstance().toNbt(stack, ItemStackUtils.registry());
        nbtCompound = (CompoundTag) nbtCompound.get("components");
        nbtCompound = nbtCompound == null ? new CompoundTag() : nbtCompound;
        nbtCompound = replaceMcKey(nbtCompound);
        return nbtCompound;
    }

    private <T extends Tag> T replaceMcKey(T nbt) {
        if (nbt instanceof CompoundTag cpd) {
            CompoundTag nbtCompound = new CompoundTag();
            for (String key : cpd.keySet()) {
                Tag element = cpd.get(key);
                nbtCompound.put(replaceMcStr(key), replaceMcKey(element));
            }
            return (T) nbtCompound;
        } else if (nbt instanceof ListTag nbtList) {
            ListTag list = new ListTag();
            for (Tag element : nbtList) {
                list.add(replaceMcKey(element));
            }
            return (T) list;
        } else if (nbt instanceof StringTag nbtString) {
            String str = nbtString.value();
            return (T) StringTag.valueOf(replaceMcStr(str));
        } else return nbt;
    }

    private String replaceMcStr(String key) {
        return key.startsWith("minecraft:") ? "mc:" + key.substring("minecraft:".length()) : key;
    }
}
