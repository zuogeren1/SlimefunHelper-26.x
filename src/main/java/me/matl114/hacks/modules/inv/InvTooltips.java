package me.matl114.hacks.modules.inv;

import java.util.List;
import me.matl114.events.Event;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
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
    // todo: nbt tooltips
    public final ModulePath itemEditor = makePath(Configs.INV_CONFIG, "item-editor");
    public final ModulePath nbtTooltips = itemEditor.add("nbt-tooltips");

    public InvTooltips() {
        super("InvTooltips");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(nbtTooltips.add("enable")).build();

    public final KeyBindRef keyBind = hotkey(nbtTooltips.add("show-hotkey"))
            .defaultValue(new MultiKeyBind(KeyCode.KEY_LEFT_ALT))
            .build();

    public final IntRef width = intBuilder(nbtTooltips.add("width"))
            .defaultValue(360)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef formatedWidth = intBuilder(nbtTooltips.add("format-indent"))
            .defaultValue(0)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(RenderListener.getTooltipShow(), this::onTooltipsAppend);
    }

    public void onTooltipsAppend(Event<List<Component>> renderEvent) {
        if (enable.get() && keyBind.get().isAllPressed()) {
            ItemStack stack = renderEvent.getArgs(0);
            renderEvent.context.addAll(getTooltipLines(stack));
        }
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
