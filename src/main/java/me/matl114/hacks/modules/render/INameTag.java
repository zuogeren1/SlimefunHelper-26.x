package me.matl114.hacks.modules.render;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import me.matl114.SlimefunHelper;
import me.matl114.events.Event;
import me.matl114.events.impl.Render2D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.gui.presets.single.IIcon;
import me.matl114.gui.presets.single.RegistryDisplays;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.render.ItemStackDisplayUtils;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.renderer.TextRenderers;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.Debug;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public abstract class INameTag extends BaseModule {
    public INameTag() {
        this("INameTag");
    }

    public INameTag(String name) {
        super(name);
        bindFlag(enable);
    }

    public final ModulePath nameTag = createRoot();

    protected abstract ModulePath createRoot();

    public final FlagRef enable = flagBuilder(nameTag.addEnable()).build();

    protected abstract void initializeModuleSettings();

    {
        initializeModuleSettings();
    }

    public final FlagRef hideSelf = flagBuilder(nameTag.add("hide-self")).build();

    public final FlagRef showHealth = flagBuilder(nameTag.add("health")).build();

    public final FlagRef showPing = flagBuilder(nameTag.add("ping")).build();

    public final FlagRef dist = flagBuilder(nameTag.add("distance")).build();

    public final FlagRef equipment = flagBuilder(nameTag.add("equipment")).build();

    public final EnumRef<ItemStackDisplayUtils.DamageDisplay> equipmentPercentage = builder(
                    nameTag.add("equipment-damage"), ItemStackDisplayUtils.DamageDisplay.class)
            .defaultValue(ItemStackDisplayUtils.DamageDisplay.NONE)
            .build();

    public final FlagRef pop = flagBuilder(nameTag.add("pop")).build();

    public final FlagRef enchantmentSum =
            flagBuilder(nameTag.add("enchant-protection-sum")).build();

    public final FlagRef potion = flagBuilder(nameTag.add("potion")).build();

    public final NBTRef<WrapColor> nameColor = builder(nameTag.add("name-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.WHITE)))
            .build();

    public final NBTRef<WrapColor> friendNameColor = builder(nameTag.add("friend-name-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.WHITE)))
            .build();

    public final NBTRef<WrapColor> healthColor = builder(nameTag.add("health-color"), WrapColor.class)
            .defaultValue(new WrapColor((new Color(20, 170, 170))))
            .build();

    public final NBTRef<WrapColor> pingColor = builder(nameTag.add("ping-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.GREEN)))
            .build();

    public final NBTRef<WrapColor> distColor = builder(nameTag.add("distance-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.RED)))
            .build();

    public final NBTRef<WrapColor> popColor = builder(nameTag.add("pop-color"), WrapColor.class)
            .defaultValue(new WrapColor((Color.ORANGE)))
            .build();

    public final NBTRef<WrapColor> infoColor = builder(nameTag.add("other-info-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.YELLOW)))
            .build();

    public final NBTRef<WrapColor> potionColor = builder(nameTag.add("potion-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.WHITE)))
            .build();
    List<PlayerNameTagInfo> nameTagInfos;
    protected static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.MAINHAND,
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET,
        EquipmentSlot.OFFHAND
    };

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(RenderListener.getRender2DEvent(), this::onRender);
        registerListener(Listener.getPostTick(), this::onUpdate);
    }

    public static final Component DEV_PREFIX = ChatUtils.stringToText(
            "§x§e§b§3§3§e§b§l[§x§d§6§2§6§d§6§lD§x§c§1§1§a§c§1§le§x§a§c§0§d§a§c§lv§x§9§7§0§0§9§7§l]");

    private static final Map<String, Component> INTERNAL_PREFIX = Map.of(
            "||juhaoniubi666",
                    ChatUtils.stringToText(
                            "§x§a§f§b§1§d§9[§x§a§9§b§b§d§b大§x§a§3§c§5§d§d啥§x§9§d§c§f§d§f比§x§9§7§d§9§e§1]"),
            "_juhao_",
                    ChatUtils.stringToText(
                            "§x§a§f§b§1§d§9[§x§a§9§b§b§d§b大§x§a§3§c§5§d§d啥§x§9§d§c§f§d§f比§x§9§7§d§9§e§1]"));

    private static Component getDurationText(int duration) {
        if (duration > Integer.MAX_VALUE - 1) {
            return Component.translatable("effect.duration.infinite");
        } else {
            int i = Mth.floor((float) duration);
            return Component.literal(
                    StringUtil.formatTickDuration(i, mc.level.tickRateManager().tickrate()));
        }
    }

    public void onUpdate() {
        if (!checkNull() && enable.get()) {
            nameTagInfos = new ArrayList<>();
            for (var player : mc.level.players()) {
                if (!(player instanceof LocalPlayer) && !(player instanceof RemotePlayer)) {
                    continue;
                }
                MutableComponent text = Component.empty();
                String name = player.getScoreboardName();
                if (name == null) continue;
                if (SlimefunHelper.DEV_NAME.contains(name)) {
                    text.append(DEV_PREFIX);
                } else if (INTERNAL_PREFIX.containsKey(name)) {
                    Component prefix = INTERNAL_PREFIX.get(name);
                    text.append(prefix);
                }
                boolean isFriend = TargetSelector.INSTANCE.isInFriendList(player);
                if (isFriend) {
                    text.append(ChatUtils.stringToText("&x&F&F&A&C&0&0["
                            + TargetSelector.INSTANCE.getPlayerList().getFriendAlias(player.getScoreboardName())
                            + "&x&F&F&A&C&0&0]"));
                }
                if (player.isCreative()) {
                    text.append(Component.literal("[C]").withColor(Color.RED.getRGB()));
                }

                text.append(player.getDisplayName()
                        .copy()
                        .withColor(
                                isFriend
                                        ? friendNameColor.get().asRGB()
                                        : nameColor.get().asRGB()));
                if (showHealth.get()) {
                    float absorption = player.getAbsorptionAmount();
                    String healthText = absorption > 0.0F
                            ? " %d(+%d)♥".formatted((int) player.getHealth(), (int) absorption)
                            : " %d♥".formatted((int) player.getHealth());
                    text.append(
                            Component.literal(healthText).withColor(healthColor.get().asRGB()));
                }
                if (showPing.get()) {
                    int latency = 0;
                    PlayerInfo entry = mc.getConnection().getPlayerInfo(player.getUUID());
                    if (entry != null) {
                        latency = entry.getLatency();
                    }
                    text.append(Component.literal(" %dms".formatted(latency))
                            .withColor(pingColor.get().asRGB()));
                }
                if (dist.get()) {
                    double len = mc.player.position().distanceTo(player.position());
                    text.append(Component.literal(" d:%.1fm".formatted(len))
                            .withColor(distColor.get().asRGB()));
                }
                if (pop.get()) {
                    int popCnt = PlayerStateManager.INSTANCE.getPlayerPopCount(player);
                    if (popCnt > 0) {
                        text.append(Component.literal(" -%d".formatted(popCnt))
                                .withColor(popColor.get().asRGB()));
                    }
                }

                ItemStack[] stack5 = null;
                if (equipment.get()) {
                    stack5 = new ItemStack[6];
                    boolean hasNoEmpty = false;
                    for (int i = 0; i < 6; ++i) {
                        var re = player.getItemBySlot(SLOTS[i]);
                        stack5[i] = re;
                        if (!re.isEmpty()) {
                            hasNoEmpty = true;
                        }
                    }
                    if (!hasNoEmpty) {
                        stack5 = null;
                    }
                }

                List<Component> subTexts = new ArrayList<>();
                if (enchantmentSum.get()) {
                    PlayerStateManager.PlayerStatus status = PlayerStateManager.INSTANCE.getPlayerStatus(player);
                    if (status != null) {
                        if (status.protection > 0) {
                            subTexts.add(Component.literal("保护%d".formatted(status.protection))
                                    .withColor(infoColor.get().asRGB()));
                        }
                        if (status.blastProtection > 0) {
                            subTexts.add(Component.literal("爆炸%d".formatted(status.blastProtection))
                                    .withColor(infoColor.get().asRGB()));
                        }
                    }
                }
                MutableComponent text2;
                if (subTexts.isEmpty()) {
                    text2 = null;
                } else {
                    text2 = Component.empty();
                    boolean first = true;
                    for (var re : subTexts) {
                        if (first) {
                            first = false;
                        } else {
                            text2.append(Component.literal(" "));
                        }
                        text2.append(re);
                    }
                }
                Map<Holder<MobEffect>, Component> visible = null;
                if (potion.get()) {
                    PlayerStateManager.PlayerStatus status = PlayerStateManager.INSTANCE.getPlayerStatus(player);
                    if (status != null) {
                        var map = status.visibleStatusEffects;
                        visible = new LinkedHashMap<>(map.size());
                        for (var entry : map.entrySet()) {
                            if (entry.getValue().visible) {
                                visible.put(
                                        entry.getKey(),
                                        getDurationText(entry.getValue().getRemainDurations()));
                            }
                        }
                    }
                }
                nameTagInfos.add(new PlayerNameTagInfo(player, text, stack5, text2, visible));
            }
        } else {
            nameTagInfos = null;
        }
    }

    public abstract void onRender(Event<Render2D> event);

    protected static final IIcon<MobEffect> statusEffectRenderer =
            RegistryDisplays.getIcon(MobEffect.class);

    /**
     * 插件往玩家列表里追加行的扩展点。
     *
     * <p>NameList 每次重建列表后调用一次 —— 列表本身只在模块启用时重建，所以实现方
     * 可以放心在这里做「每 tick 一次」的采集，天然跟着开关走。返回的行追加在真实玩家之后。
     */
    public interface RowProvider {
        /**
         * @param owner 当前渲染的列表模块，可用来取 nameColor / infoColor / distColor 这类配色
         * @return 要追加的行；返回 null 或空表表示这一 tick 没有内容
         */
        List<ExtraRow> provideRows(INameTag owner);
    }

    /** 一行额外信息：左侧名字 + 右侧详情，两者都可以为 null。 */
    public record ExtraRow(Component name, Component detail) {}

    private static final List<RowProvider> ROW_PROVIDERS = new CopyOnWriteArrayList<>();

    public static void registerRowProvider(RowProvider provider) {
        if (provider != null && !ROW_PROVIDERS.contains(provider)) {
            ROW_PROVIDERS.add(provider);
        }
    }

    public static void unregisterRowProvider(RowProvider provider) {
        ROW_PROVIDERS.remove(provider);
    }

    /** 收集所有插件追加的行；某个 provider 抛异常只丢它自己那一批，不影响整个列表。 */
    public static List<ExtraRow> collectRows(INameTag owner) {
        if (ROW_PROVIDERS.isEmpty()) {
            return List.of();
        }
        List<ExtraRow> rows = new ArrayList<>();
        for (RowProvider provider : ROW_PROVIDERS) {
            try {
                List<ExtraRow> extra = provider.provideRows(owner);
                if (extra != null && !extra.isEmpty()) {
                    rows.addAll(extra);
                }
            } catch (Throwable e) {
                Debug.info("[NameList] 行提供者 " + provider.getClass().getName() + " 出错: " + e);
            }
        }
        return rows;
    }

    public static class PlayerNameTagInfo {
        Player player;
        Component nameDisplay;
        float nameLength;
        ItemStack[] equipments;
        Component otherInfoDisplay;
        float otherInfoLength;
        Map<Holder<MobEffect>, Component> visibleEffects;

        public PlayerNameTagInfo(
                Player player,
                Component display,
                ItemStack[] equipments,
                Component otherInfo,
                Map<Holder<MobEffect>, Component> effects) {
            this.player = player;
            this.nameDisplay = display;
            this.nameLength = display == null ? 0.0F : TextRenderers.get().getWidth(display);
            if (this.nameLength <= 0.0F) {
                this.nameDisplay = null;
            }
            this.equipments = equipments;
            this.otherInfoDisplay = otherInfo;
            this.otherInfoLength =
                    otherInfo == null ? 0.0F : TextRenderers.get().getWidth(otherInfo);
            if (this.otherInfoLength <= 0.0F) {
                this.otherInfoDisplay = null;
            }
            visibleEffects = effects;
        }
    }
}
