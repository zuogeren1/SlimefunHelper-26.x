package me.matl114.utils.structures;

import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.config.ConfigEnum;
import me.matl114.versioned.SupportVersion;

/** Minecraft versions whose supported chest loot tables are known to this predictor. */
public enum LootVersion implements ConfigEnum {
    /** 跟随当前连接版本：Via 代理时取服务器版本，否则使用本构建的默认版本。 */
    AUTO("auto", false, true, true),
    V1_21_1("1.21.1", true, false, false),
    V1_21_4("1.21.4", true, false, false),
    V1_21_11("1.21.11", true, true, true),
    V26_1("26.1", false, true, true),
    V26_2("26.2", false, true, true),
    V26_3("26.3", false, true, true);

    private final String gameVersion;
    private final boolean ancientCityHasNameTag;
    private final boolean ancientCityUsesLeatherInsteadOfSaddle;
    private final boolean hasCopperHorseArmor;

    LootVersion(
            String gameVersion,
            boolean ancientCityHasNameTag,
            boolean ancientCityUsesLeatherInsteadOfSaddle,
            boolean hasCopperHorseArmor) {
        this.gameVersion = gameVersion;
        this.ancientCityHasNameTag = ancientCityHasNameTag;
        this.ancientCityUsesLeatherInsteadOfSaddle = ancientCityUsesLeatherInsteadOfSaddle;
        this.hasCopperHorseArmor = hasCopperHorseArmor;
    }

    public String gameVersion() {
        return gameVersion;
    }

    public boolean ancientCityHasNameTag() {
        return ancientCityHasNameTag;
    }

    public boolean ancientCityUsesLeatherInsteadOfSaddle() {
        return ancientCityUsesLeatherInsteadOfSaddle;
    }

    public boolean hasCopperHorseArmor() {
        return hasCopperHorseArmor;
    }

    public boolean hasLodestonePortalPool() {
        LootVersion version = this.resolved();
        return version == V1_21_11 || version == V26_1 || version == V26_2 || version == V26_3;
    }

    /** 本构建的默认战利品版本，{@link #AUTO} 在无法判断服务器版本时回退到它。 */
    public static LootVersion defaultForBuild() {
        //#if MC >= 260300
        //$$ return V26_3;
        //#elseif MC >= 260200
        return V26_2;
        //#else
        //$$ return V26_1;
        //#endif
    }

    /**
     * 把 {@link #AUTO} 解析成具体版本：
     * ViaFabricPlus 连接时取服务器版本（26.3 服务器就给 26.3 的掉落），
     * 否则退回本构建的默认值。
     */
    public LootVersion resolved() {
        if (this != AUTO) {
            return this;
        }
        ViaFabricPlusHooks hooks = ViaFabricPlusHooks.getInstance();
        if (hooks == null || !hooks.isViaEnabled()) {
            return defaultForBuild();
        }
        SupportVersion version = hooks.getCurrentVersion();
        if (version == null) {
            return defaultForBuild();
        }
        if (version.isHigherOrEqualTo(26, 3)) return V26_3;
        if (version.isHigherOrEqualTo(26, 2)) return V26_2;
        if (version.isHigherOrEqualTo(26, 1)) return V26_1;
        if (version.isHigherOrEqualTo(21, 11)) return V1_21_11;
        if (version.isHigherOrEqualTo(21, 4)) return V1_21_4;
        return V1_21_1;
    }

    @Override
    public String getConfigEnumType() {
        return "loot_version";
    }
}
