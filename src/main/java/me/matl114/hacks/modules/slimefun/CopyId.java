package me.matl114.hacks.modules.slimefun;

import me.matl114.hacks.utils.ClientUtils;

import static me.matl114.utils.ItemStackUtils.getSfId;

import java.util.Locale;
import me.matl114.accessors.access.HandledScreenAccess;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.managers.Configs;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.input.KeyCode;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import me.matl114.utils.ScreenUtils;
import me.matl114.utils.collections.Point;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class CopyId extends BaseModule {
    public final ModulePath slimefunSettings = makePath(Configs.SLIMEFUN_CONFIG, "slimefun-settings");

    public CopyId() {
        super("CopyId");
    }

    public KeyBindRef keyBind = hotkey(
                    Configs.SLIMEFUN_CONFIG,
                    slimefunSettings.add("slimefunid-copy").toPath())
            .defaultValue(new MultiKeyBind(KeyCode.KEY_LEFT_CONTROL, KeyCode.KEY_C, KeyCode.MOUSE_BUTTON_1))
            .registerHotkey(HotKeyUtils.asHandler(this::copySfIdInHand))
            .build();

    public boolean copySfIdInHand() {
        var client = Minecraft.getInstance();
        var player = Minecraft.getInstance().player;
        if (player == null || client == null) return false;
        ItemStack heldItem = null;
        if (ClientUtils.getScreen(client) instanceof AbstractContainerScreen<?> s) {
            Point mouseCoord = ScreenUtils.getMouseCoord(client);
            Slot slot = HandledScreenAccess.of(s).reallyGetSlotAt(mouseCoord.x, mouseCoord.y);
            if (slot != null) {
                heldItem = slot.getItem();
            }
        } else {
            heldItem = player.getItemInHand(InteractionHand.MAIN_HAND);
        }
        if (heldItem != null) {
            String sfid = getSfId(heldItem);

            if (sfid != null) {
                client.keyboardHandler.setClipboard(sfid);
                Debug.chat(Component.literal("成功将Slimefun ID拷贝至你的剪切板和公共参数! 值: ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(sfid).withStyle(ChatFormatting.WHITE)));

                return true;
            } else {
                String id = BuiltInRegistries.ITEM
                        .getKey(heldItem.getItem())
                        .getPath()
                        .toUpperCase(Locale.ROOT);
                client.keyboardHandler.setClipboard(id);
                Debug.chat(Component.literal("该物品不是Slimefun物品,拷贝原版ID!")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(id).withStyle(ChatFormatting.WHITE)));
                return true;
            }
        }
        return false;
    }
}
