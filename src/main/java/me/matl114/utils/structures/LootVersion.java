package me.matl114.utils.structures;

import me.matl114.managers.config.ConfigEnum;

/** Minecraft versions whose supported chest loot tables are known to this predictor. */
public enum LootVersion implements ConfigEnum {
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
        return this == V1_21_11 || this == V26_1 || this == V26_2 || this == V26_3;
    }

    @Override
    public String getConfigEnumType() {
        return "loot_version";
    }
}
