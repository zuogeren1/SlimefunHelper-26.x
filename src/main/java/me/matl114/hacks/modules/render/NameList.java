package me.matl114.hacks.modules.render;

import com.mojang.datafixers.util.Either;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.Render2D;
import me.matl114.gui.Constants;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.LocatorTracker;
import me.matl114.hacks.utils.config.WidgetPos;
import me.matl114.hacks.utils.render.ItemStackDisplayUtils;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.NBTRef;
import me.matl114.utils.Debug;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

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
        locatorTrack = builder(nameTag.add("locator-track"), Boolean.class)
                .defaultValue(true)
                .build();
        locatorMinSpan = doubleBuilder(nameTag.add("locator-track-min-span"))
                .defaultValue(1.0D)
                .validator(Configs.doubleRange(0.2D, 30.0D))
                .build();
        locatorMinBaseline = doubleBuilder(nameTag.add("locator-track-min-baseline"))
                .defaultValue(16.0D)
                .validator(Configs.doubleRange(1.0D, 1000.0D))
                .build();
        locatorMaxAge = builder(nameTag.add("locator-track-max-age"), IntRef.TYPE)
                .defaultValue(1200)
                .validator(Configs.intRange(40, 12000))
                .build();
        locatorDebug = flagBuilder(nameTag.add("locator-track-debug")).build();
    }

    public IntRef playerListMaxLength;

    public FlagRef right;
    public DoubleRef scale;
    public NBTRef<WidgetPos> pos;

    /** 用定位栏方位角反推看不见的玩家坐标，并显示成额外列表行。 */
    public FlagRef locatorTrack;

    /** 参与解算所需的最小方位跨度（度）。 */
    public DoubleRef locatorMinSpan;

    /** 参与解算所需的最小基线（格）。 */
    public DoubleRef locatorMinBaseline;

    /** 观测与反推结果的保留时长（刻）。 */
    public IntRef locatorMaxAge;

    /** 调试：HUD 加一行状态、聊天按 2 秒节流打印每个点的样本/跨度/基线。 */
    public FlagRef locatorDebug;

    private int lastLocatorDebugTick = -1000;

    @Override
    public void onRender(Event<Render2D> event) {
        if (checkNull()) {
            return;
        }
        if (enable.get() && !event.context.hudHidden() && nameTagInfos != null) {
            var stack = event.context.drawContext();
            stack.getMatrices().pushMatrix();
            handleRenderPosition(stack);
            int count = 0;
            for (var entry : nameTagInfos) {
                if (count >= playerListMaxLength.get()) {
                    handleTooManyPlayerList(stack);
                    break;
                }
                onRenderList(entry, stack, (event.context.partialTicks()));
                stack.getMatrices().translate(0, HEIGHT);
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
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getWorldSwitchPoint(), this::onWorldSwitch);
    }

    /** 换世界（含换维度）时坐标系整体改变，旧观测必须作废。 */
    public void onWorldSwitch(Event<Level> event) {
        LocatorTracker.INSTANCE.clear();
    }

    @Override
    public void onUpdate(Event<Void> event) {
        super.onUpdate(event);
        if (checkNull() || !enable.get() || !locatorTrack.get()) {
            LocatorTracker.INSTANCE.clear();
            return;
        }
        LocatorTracker.INSTANCE.tick(mc, locatorMinSpan.get(), locatorMinBaseline.get(), locatorMaxAge.get());
        appendLocatorEntries();
        if (locatorDebug.get()) {
            debugLocator();
        }
    }

    /**
     * 把“只有定位栏、本地没有实体”的玩家反推结果追加到列表末尾。
     *
     * <p>坐标是估计值：定位栏只有水平信息，所以显示的是 XZ 与水平距离，且用 ~ 前缀标注。
     */
    public void appendLocatorEntries() {
        if (nameTagInfos == null || mc.level == null || mc.player == null) {
            return;
        }
        List<PlayerNameTagInfo> extra = new ArrayList<>();
        for (var entry : LocatorTracker.INSTANCE.snapshot()) {
            var id = entry.getKey();
            var estimate = entry.getValue();
            UUID uuid = id.left().orElse(null);
            // 本地已有实体的玩家不重复显示（他们有真实坐标）
            if (uuid != null && isPlayerLoaded(uuid)) {
                continue;
            }
            double distance = estimate.distanceTo(mc.player.getX(), mc.player.getZ());
            MutableComponent line = Component.literal(
                            "~[%d, %d] ".formatted(Mth.floor(estimate.x), Mth.floor(estimate.z)))
                    .withColor(infoColor.get().asRGB());
            line.append(Component.literal("d:%dm".formatted((int) Math.round(distance)))
                    .withColor(distColor.get().asRGB()));
            if (!Double.isNaN(estimate.sigma)) {
                line.append(Component.literal(" ±%dm".formatted((int) Math.round(estimate.sigma)))
                        .withColor(distColor.get().asRGB()));
            }
            line.append(Component.literal(" n=%d".formatted(estimate.samples))
                    .withColor(infoColor.get().asRGB()));
            int ageTicks = Tasks.getTick() - estimate.tick;
            if (ageTicks > 60) {
                // 超过 3 秒没刷新（对方潜行/下线/解算被门槛挡住）→ 标出这是旧值
                line.append(Component.literal(" +%ds".formatted(ageTicks / 20))
                        .withColor(infoColor.get().asRGB()));
            }
            extra.add(new PlayerNameTagInfo(
                    null,
                    Component.literal(displayName(id)).withColor(nameColor.get().asRGB()),
                    null,
                    line,
                    null));
        }
        if (!extra.isEmpty()) {
            nameTagInfos.addAll(extra);
        }
        if (locatorDebug.get()) {
            nameTagInfos.add(debugLine());
        }
    }

    /** 名字解析兜底：tab 列表 → 命名 waypoint → 短 UUID，保证“有数据就一定有行”。 */
    public String displayName(Either<UUID, String> id) {
        UUID uuid = id.left().orElse(null);
        if (uuid == null) {
            return id.right().orElse("waypoint");
        }
        String name = resolvePlayerName(uuid);
        return name == null ? "?" + uuid.toString().substring(0, 8) : name;
    }

    /** 调试行：一眼看出“有没有 waypoint / 攒了几条观测 / 跨度与基线够不够”。 */
    public PlayerNameTagInfo debugLine() {
        var statuses = LocatorTracker.INSTANCE.statuses();
        String text;
        if (statuses.isEmpty()) {
            text = "定位栏调试: waypoint=" + LocatorTracker.INSTANCE.getWaypointCount() + " 追踪=0";
        } else {
            var best = statuses.get(0);
            text = String.format(
                    "定位栏调试: wp=%d 追踪=%d 最佳 %s n=%d 跨=%.2f° 基=%.0f",
                    LocatorTracker.INSTANCE.getWaypointCount(),
                    statuses.size(),
                    displayName(best.id),
                    best.samples,
                    best.span,
                    best.baseline);
        }
        return new PlayerNameTagInfo(
                null, Component.literal(text).withColor(infoColor.get().asRGB()), null, null, null);
    }

    /** 聊天日志（2 秒节流）：逐点给出样本数/跨度/基线/距离/解，便于把日志贴回来定位。 */
    public void debugLocator() {
        int now = Tasks.getTick();
        if (now - this.lastLocatorDebugTick < 40) {
            return;
        }
        this.lastLocatorDebugTick = now;
        var statuses = LocatorTracker.INSTANCE.statuses();
        Debug.chat("[Locator] waypoint=" + LocatorTracker.INSTANCE.getWaypointCount() + " tracked=" + statuses.size());
        for (var status : statuses) {
            Debug.chat(String.format(
                    "[Locator] %s %s n=%d span=%.2f base=%.1f dist=%s est=%s",
                    displayName(status.id),
                    status.finiteDistance ? "coord" : "azimuth",
                    status.samples,
                    status.span,
                    status.baseline,
                    Double.isNaN(status.distance) ? "-" : String.format("%.0f", status.distance),
                    status.estimate == null
                            ? "none"
                            : String.format(
                                    "%.0f,%.0f sigma=%.0f",
                                    status.estimate.x,
                                    status.estimate.z,
                                    status.estimate.sigma)));
        }
    }

    public boolean isPlayerLoaded(UUID uuid) {
        for (Player player : mc.level.players()) {
            if (player.getUUID().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    /** 反推结果的可读名字：取自 tab 列表；拿不到就放弃显示（避免刷出一堆短 UUID）。 */
    public String resolvePlayerName(UUID uuid) {
        if (mc.getConnection() == null) {
            return null;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
        return info == null ? null : info.getProfile().name();
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
            vdraw.drawText(mc.font, player.nameDisplay.getVisualOrderText(), (int) 0, 0, -1, true);
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
                    vdraw.drawItemInSlot(mc.font, stackOverride, i * 16, 0, countOverride);
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
            vdraw.drawText(mc.font, player.otherInfoDisplay.getVisualOrderText(), (int) 0, 0, -1, true);
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
                float len = mc.font.getSplitter().stringWidth(entry.getValue());
                if (right.get()) {
                    vdraw.getMatrices().translate(-9 - len, 0);
                }
                vdraw.getMatrices().pushMatrix();
                vdraw.getMatrices().scale(9 / 16.0F, 9 / 16.0F);
                statusEffectRenderer.render(0, 0, vdraw, entry.getKey().value());
                vdraw.getMatrices().popMatrix();
                vdraw.drawText(
                        mc.font,
                        entry.getValue().getVisualOrderText(),
                        9,
                        0,
                        potionColor.get().withAlpha(255),
                        true);

                if (!right.get()) {
                    vdraw.getMatrices().translate(9 + len, 0);
                }
            }
        }
    }

    public void handleTooManyPlayerList(VDrawContext vdraw) {
        vdraw.pushMatrix();
        FormattedCharSequence display = Component.literal(
                        "......(" + (nameTagInfos.size() - playerListMaxLength.get()) + " more)")
                .getVisualOrderText();
        float length = mc.font.getSplitter().stringWidth(display);
        if (right.get()) {
            vdraw.getMatrices().translate(-length, 0);
        }
        vdraw.drawText(mc.font, display, (int) 0, 0, -1, true);
        vdraw.popMatrix();
    }
}
