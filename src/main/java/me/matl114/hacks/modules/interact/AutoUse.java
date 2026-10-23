package me.matl114.hacks.modules.interact;

import me.matl114.accessors.access.ClientAccess;
import me.matl114.accessors.hacks.KeyBindAccess;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import me.matl114.versioned.api.VItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;

public class AutoUse extends BaseModule {
    public AutoUse() {
        super("AutoUse");
        bindFlag(enable);
    }

    public ModulePath path = makePath(Configs.INTERACT_CONFIG, "interaction-tweaks.auto-use");
    public final FlagRef enable = flagBuilder(path.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(path.addHotkey(), new MultiKeyBind(), path.addEnable()).build();

    public final FlagRef log = flagBuilder(path.add("log")).build();

    public final FlagRef onlyWhenEnemyNear =
            flagBuilder(path.add("only-when-enemy-near")).build();

    public final DoubleRef nearDistance = doubleBuilder(path.add("enemy-near-distance"))
            .defaultValue(12.0D)
            .validator(Configs.doubleRange(0.0D, 20.0D))
            .build();

    public final FlagRef useFood = flagBuilder(path.add("use-food")).build();

    public final NBTRef<EntrySet<Item>> whiteList = builder(path.add("use-item-white-list"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(.*spear|.*sword|shield)$"), BuiltInRegistries.ITEM))
            .build();

    public boolean isUsableNotFood(ItemStack stack) {
        var block = stack.get(DataComponents.BLOCKS_ATTACKS);
        if (block != null) return true;
        if (VItem.getInstance().isSpear(stack)) return true;
        var item = stack.getItem();
        if (item instanceof BowItem || item instanceof TridentItem || item instanceof CrossbowItem) return true;
        return false;
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onInputEvent);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        if (!checkNull() && lastAutoUsingSpear) {
            lastAutoUsingSpear = false;
            KeyBindAccess.of(mc.options.keyUse).resetKeyState();
        }
    }

    private boolean pass() {
        if (onlyWhenEnemyNear.get()
                && TargetSelector.INSTANCE.searchAttackEntity(nearDistance.get(), true, pl -> pl instanceof Player)
                        == null) {
            return false;
        }
        return true;
    }

    public boolean isWorkingAcceptable(ItemStack stack) {
        return whiteList.get().test(stack.getItem())
                && (isUsableNotFood(stack)
                        || (useFood.get() && VItem.getInstance().isEatable(stack)));
    }

    boolean lastAutoUsingSpear = false;

    public void onInputEvent() {
        if (enable.get() && pass()) {
            if (!mc.player.isUsingItem()) {
                ItemStack mainHand = mc.player.getMainHandItem();
                ItemStack offHand = mc.player.getOffhandItem();
                InteractionHand useHand;
                if (isWorkingAcceptable(mainHand)) {
                    useHand = InteractionHand.MAIN_HAND;
                } else if (isWorkingAcceptable(offHand)) {
                    useHand = InteractionHand.OFF_HAND;
                } else {
                    useHand = null;
                }
                if (useHand != null) {
                    ClientAccess.of(mc).simulateUseItem(useHand);
                    if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == useHand) {
                        mc.options.keyUse.setDown(true);
                        if (log.get()) {
                            Debug.chat(
                                    ChatUtils.stringToText("&c[Use] &fStart to use"),
                                    VItem.getInstance().getFormattedName(mc.player.getUseItem()));
                        }
                    } else {
                        KeyBindAccess.of(mc.options.keyUse).resetKeyState();
                    }
                } else {
                    if (lastAutoUsingSpear) {
                        lastAutoUsingSpear = false;
                        KeyBindAccess.of(mc.options.keyUse).resetKeyState();
                    }
                }
            } else {
                if (isWorkingAcceptable(mc.player.getUseItem())) {
                    mc.options.keyUse.setDown(true);
                    lastAutoUsingSpear = true;
                } else {
                    if (lastAutoUsingSpear) {
                        lastAutoUsingSpear = false;
                        KeyBindAccess.of(mc.options.keyUse).resetKeyState();
                    }
                }
            }
        } else {
            if (lastAutoUsingSpear) {
                lastAutoUsingSpear = false;
                KeyBindAccess.of(mc.options.keyUse).resetKeyState();
            }
        }
    }
}
