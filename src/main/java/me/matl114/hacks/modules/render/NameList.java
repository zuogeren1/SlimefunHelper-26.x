package me.matl114.hacks.modules.render;

import java.util.List;
import java.util.Map;
import me.matl114.events.Event;
import me.matl114.events.impl.Render2D;
import me.matl114.gui.Constants;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.WidgetPos;
import me.matl114.hacks.utils.render.ItemStackDisplayUtils;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class NameList extends INameTag {
    public NameList() {
        super("NameList");
    }

    @Override
    protected ModulePath createRoot() {
        return makePath(Configs.RENDER_CONFIG, "player-info.name-list");
    }

    @Override
    protected void initializeModuleSettings() {
        playerListMaxLength = builder(nameTag.add("player-list-max-length"), IntRef.TYPE)
                .defaultValue(20)
                .build();
        right = flagBuilder(nameTag.add("list-right")).build();
        scale = doubleBuilder(nameTag.add("scale")).defaultValue(1.0D).build();
        pos = builder(nameTag.add("list-pos"), WidgetPos.class)
                .defaultValue(new WidgetPos(0, 0.02D, 0.02D, 5, 5))
                .build();
    }

    public IntRef playerListMaxLength;

    public FlagRef right;
    public DoubleRef scale;
    public NBTRef<WidgetPos> pos;

    @Override
    public void onRender(Event<Render2D> event) {
        if (checkNull()) {
            return;
        }
        if (enable.get() && !event.context.hudHidden() && nameTagInfos != null) {
            var stack = event.context.drawContext();
            float textHeight = stack.getTextDrawer().getTextHeight() + 1;
            stack.getMatrices().pushMatrix();
            handleRenderPosition(stack);
            int count = 0;
            for (var entry : nameTagInfos) {
                if (hideSelf.get() && entry.player == mc.getCameraEntity()) {
                    continue;
                }
                if (count >= playerListMaxLength.get()) {
                    handleTooManyPlayerList(stack);
                    break;
                }
                onRenderList(entry, stack, (event.context.partialTicks()));
                stack.getMatrices().translate(0, textHeight);
                count += 1;
            }
            stack.getMatrices().popMatrix();
        }
    }

    public void handleRenderPosition(VDrawContext vdraw) {
        //        vdraw.pushMatrix();
        //        vdraw.drawTexturedQuad(Identifier.tryParse("slimefunhelper:textures/custom/genshin_impact.png"), sizeX
        // - 30,sizeX, sizeY - 20, sizeY, 0, 0,1,0 , 1);
        //        vdraw.popMatrix();
        var pp = pos.get();
        double xPer = pp.getWindowX(mc.getWindow());
        double yPer = pp.getWindowY(mc.getWindow());
        vdraw.getMatrices().translate((float) xPer, (float) yPer);
        vdraw.getMatrices().scale((float) scale.get(), (float) scale.get());
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        appendExtraRows();
    }

    /**
     * 把插件追加的行（定位栏反推等）拼到真实玩家之后。
     *
     * <p>行由 {@link INameTag#collectRows} 统一收集：插件只提供名字/详情两个 {@link Component}，
     * 行高与排版仍由本模块负责，插件不必知道列表是怎么画的。
     */
    public void appendExtraRows() {
        if (nameTagInfos == null) {
            return;
        }
        for (INameTag.ExtraRow row : INameTag.collectRows(this)) {
            nameTagInfos.add(new PlayerNameTagInfo(null, row.name(), null, row.detail(), null));
        }
    }

    public void onRenderList(PlayerNameTagInfo player, VDrawContext vdraw, float tick) {
        vdraw.pushMatrix();
        try {
            handleNameLineList(vdraw, player);
            handleEquipmentList(vdraw, player);
            handleOtherInfo(vdraw, player);
            handleEffectDisplayList(vdraw, player);
        } finally {
            vdraw.popMatrix();
        }
    }

    public void handleNameLineList(VDrawContext vdraw, PlayerNameTagInfo player) {
        if (player.nameDisplay != null) {
            float length = player.nameLength;
            if (right.get()) {
                vdraw.getMatrices().translate(-length, 0);
            }
            vdraw.drawText(
                    vdraw.getTextDrawer(),
                    player.nameDisplay.getVisualOrderText(),
                    (int) 0,
                    (int) ((9 - vdraw.getTextDrawer().getTextHeight()) / 2.0F),
                    -1,
                    false);
            if (!right.get()) {
                vdraw.getMatrices().translate(length, 0);
            }
        }
    }

    public void handleEquipmentList(VDrawContext vdraw, PlayerNameTagInfo player) {
        if (player.equipments != null) {
            ItemStack[] stacks = player.equipments;
            if (right.get()) {
                vdraw.getMatrices().translate(-(stacks.length * 9), 0);
            }
            vdraw.getMatrices().pushMatrix();
            vdraw.getMatrices().scale(9 / 16.0F, 9 / 16.0F);
            for (var i = 0; i < stacks.length; ++i) {
                if (stacks[i].isEmpty()) {
                    EquipmentSlot slot = SLOTS[i];
                    vdraw.drawGuiTexture(Constants.EMPTY_SLOT_TO_SPRITE.get(slot), i * 16, 0, 16, 16);
                } else {
                    vdraw.drawItem(stacks[i], i * 16, 0, 999, 0);
                    String countOverride = null;
                    ItemStack stackOverride = stacks[i];
                    if (equipmentPercentage.get().isNotIn(ItemStackDisplayUtils.DamageDisplay.NONE)
                            && stackOverride.getCount() == 1
                            && stackOverride.isDamageableItem()) {

                        countOverride = ItemStackDisplayUtils.getDamageShowText(
                                        stackOverride, equipmentPercentage.get())
                                .getString();
                        stackOverride = stackOverride.copy();
                        stackOverride.setDamageValue(0);
                    }
                    vdraw.drawItemInSlot(vdraw.getTextDrawer(), stackOverride, i * 16, 0, countOverride);
                }
            }
            vdraw.getMatrices().popMatrix();
            if (!right.get()) {
                vdraw.getMatrices().translate((stacks.length * 9), 0);
            }
        }
    }

    public void handleOtherInfo(VDrawContext vdraw, PlayerNameTagInfo player) {
        if (player.otherInfoDisplay != null) {
            float length = player.otherInfoLength;
            if (right.get()) {
                vdraw.getMatrices().translate(-length, 0);
            }
            vdraw.drawText(
                    vdraw.getTextDrawer(),
                    player.otherInfoDisplay.getVisualOrderText(),
                    (int) 0,
                    (int) ((9 - vdraw.getTextDrawer().getTextHeight()) / 2.0F),
                    -1,
                    false);
            if (!right.get()) {
                vdraw.getMatrices().translate(length, 0);
            }
        }
    }

    public void handleEffectDisplayList(VDrawContext vdraw, PlayerNameTagInfo player) {
        if (player.visibleEffects != null) {
            List<Map.Entry<Holder<MobEffect>, Component>> line =
                    player.visibleEffects.entrySet().stream().toList();
            int size = line.size();
            for (var i = 0; i < size; i++) {
                var entry = line.get(i);
                float len = vdraw.getTextDrawer().getWidth(entry.getValue());
                if (right.get()) {
                    vdraw.getMatrices().translate(-9 - len, 0);
                }
                vdraw.getMatrices().pushMatrix();
                vdraw.getMatrices().scale(9 / 16.0F, 9 / 16.0F);
                statusEffectRenderer.render(0, 0, vdraw, entry.getKey().value());
                vdraw.getMatrices().popMatrix();
                vdraw.drawText(
                        vdraw.getTextDrawer(),
                        entry.getValue().getVisualOrderText(),
                        9,
                        (int) ((9 - vdraw.getTextDrawer().getTextHeight()) / 2.0F),
                        potionColor.get().withAlpha(255),
                        false);

                if (!right.get()) {
                    vdraw.getMatrices().translate(9 + len, 0);
                }
            }
        }
    }

    public void handleTooManyPlayerList(VDrawContext vdraw) {
        vdraw.pushMatrix();
        FormattedCharSequence display = Component.literal("......(" + (nameTagInfos.size() - playerListMaxLength.get()) + " more)")
                .getVisualOrderText();
        float length = vdraw.getTextDrawer().getWidth(display);
        if (right.get()) {
            vdraw.getMatrices().translate(-length, 0);
        }
        vdraw.drawText(
                vdraw.getTextDrawer(),
                display,
                (int) 0,
                (int) ((9 - vdraw.getTextDrawer().getTextHeight()) / 2.0F),
                -1,
                false);
        vdraw.popMatrix();
    }
}
