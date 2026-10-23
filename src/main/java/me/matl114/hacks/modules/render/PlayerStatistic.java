package me.matl114.hacks.modules.render;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.matl114.events.Event;
import me.matl114.gui.presets.single.IIcon;
import me.matl114.gui.presets.single.RegistryDisplays;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

public class PlayerStatistic extends IRender2DColoredModule {
    public PlayerStatistic() {
        super("Statistic");
    }

    @Override
    protected ModulePath createRoot() {
        return makePath(Configs.RENDER_CONFIG, "in-game-hud").add("player-statistic");
    }

    public final ModulePath hudRoot = makePath(Configs.RENDER_CONFIG, "in-game-hud");
    public final ModulePath hud = hudRoot.add("player-statistic");

    public final FlagRef enableItems = flagBuilder(hud.add("enable-items")).build();

    public final NBTRef<EntrySet<Item>> itemTypes = builder(hud.add("item-types"), EntrySet.<Item>parameter())
            .defaultValue(
                    new EntrySet<>(BuiltInRegistries.ITEM, List.of(Items.TOTEM_OF_UNDYING, Items.FIREWORK_ROCKET)))
            .build();

    public final FlagRef enablePotions = flagBuilder(hud.add("enable-potions")).build();

    public final NBTRef<EntrySet<Potion>> potionTypes = builder(hud.add("potion-types"), EntrySet.<Potion>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.POTION, List.of(Potions.TURTLE_MASTER.value())))
            .build();

    public final FlagRef enableEffect = flagBuilder(hud.add("enable-effect")).build();

    @Override
    public void registerAll() {
        super.registerAll();
    }

    @Override
    public void onUpdate(Event<Void> event) {}

    @Override
    public void render2D(VDrawContext vdraw, float partialTicks) {
        if (enableItems.get()) {
            for (var re : itemTypes.get().set()) {
                handleItem(vdraw, re);
            }
        }
        if (enablePotions.get()) {
            for (var re : potionTypes.get().set()) {
                handleTurtle(vdraw, re);
            }
        }

        if (enableEffect.get()) {
            handleEffects(vdraw);
        }
    }

    private void drawItemStatistic(VDrawContext vdraw, ItemStack stack, int count) {
        FormattedCharSequence text = Component.literal(String.valueOf(count)).getVisualOrderText();

        vdraw.pushMatrix();
        {
            if (right.get()) {
                vdraw.getMatrices().translate(-9, 0);
            }
            vdraw.getMatrices().pushMatrix();
            vdraw.getMatrices().scale(0.5F, 0.5F);
            vdraw.drawItem(stack, 0, 0, 0, 0);
            vdraw.getMatrices().popMatrix();
            if (!right.get()) {
                vdraw.getMatrices().translate(9, 0);
            } else {
                int textWidth = vdraw.getTextDrawer().getWidth(text);
                vdraw.getMatrices().translate(-textWidth, 0);
            }
        }
        vdraw.drawText(
                vdraw.getTextDrawer(),
                text,
                0,
                (int) ((9 - vdraw.getTextDrawer().getTextHeight()) / 2.0F),
                color.get().withAlpha(255),
                false);
        vdraw.popMatrix();
        vdraw.getMatrices().translate(0, 9);
    }

    public void handleItem(VDrawContext vdraw, Item itemType) {
        var map = PlayerStateManager.INSTANCE.inventorySummary;
        int cnt;
        if (map != null) {
            cnt = map.entrySet().stream()
                    .filter(s -> s.getKey().sample().is(itemType))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
        } else {
            cnt = 0;
        }
        drawItemStatistic(vdraw, new ItemStack(itemType), cnt);
    }

    private boolean isTurtle(ItemStack stack, Potion potionType) {
        var potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion != null) {
            var po = potion.potion().orElse(null);
            if (po == null) return false;
            if (Objects.equals(po, potionType)) {
                return true;
            }
            var re = potionType.getEffects().stream()
                    .map(MobEffectInstance::getEffect)
                    .toList();
            var re2 = po.value().getEffects().stream()
                    .map(MobEffectInstance::getEffect)
                    .toList();
            return Objects.equals(re, re2);
        }
        return false;
    }

    public void handleTurtle(VDrawContext vdraw, Potion potionType) {
        var map = PlayerStateManager.INSTANCE.inventorySummary;
        int cnt;
        if (map != null) {
            cnt = map.entrySet().stream()
                    .filter(s -> isTurtle(s.getKey().sample(), potionType))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
        } else {
            cnt = 0;
        }
        drawItemStatistic(
                vdraw,
                PotionContents.createItemStack(Items.POTION, BuiltInRegistries.POTION.wrapAsHolder(potionType)),
                cnt);
    }

    private static final IIcon<MobEffect> statusEffectRenderer =
            RegistryDisplays.getIcon(MobEffect.class);

    public void handleEffects(VDrawContext vdraw) {
        for (var re : mc.player.getActiveEffects()) {
            FormattedCharSequence timeText = MobEffectUtil.formatDuration(
                            re, 1.0F, mc.level.tickRateManager().tickrate())
                    .getVisualOrderText();
            float length = vdraw.getTextDrawer().getWidth(timeText);
            vdraw.pushMatrix();
            if (right.get()) {
                vdraw.getMatrices().translate(-length - 9, 0);
            }
            {
                vdraw.pushMatrix();
                {
                    vdraw.getMatrices().scale(0.5F, 0.5F);
                    statusEffectRenderer.render(1, 1, vdraw, re.getEffect().value());
                    // render level
                    int level = re.getAmplifier();
                    if (level > 0) {
                        String lv = String.valueOf(level + 1);
                        vdraw.drawText(
                                vdraw.getTextDrawer(),
                                lv,
                                17 - vdraw.getTextDrawer().getWidth(lv),
                                (int) (9 + (9 - vdraw.getTextDrawer().getTextHeight()) / 2.0F),
                                -1,
                                false);
                    }
                }

                vdraw.popMatrix();
                vdraw.drawText(
                        vdraw.getTextDrawer(),
                        timeText,
                        9,
                        (int) ((9 - vdraw.getTextDrawer().getTextHeight()) / 2.0F),
                        color.get().withAlpha(255),
                        false);
            }
            vdraw.popMatrix();
            vdraw.getMatrices().translate(0, 9);
        }
    }
}
