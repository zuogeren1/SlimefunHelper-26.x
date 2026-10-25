package me.matl114.managers;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.File;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import me.matl114.managers.config.Config;
import me.matl114.managers.config.ConfigLoader;
import me.matl114.plugins.api.SlimefunHelperPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

public class Configs {

    public static void loadConfigs(SlimefunHelperPlugin plugin) {
        plugin.registerConfig(MINE_CONFIG);
        plugin.registerConfig(CHAT_CONFIG);
        plugin.registerConfig(RENDER_CONFIG);
        plugin.registerConfig(EXTRA_CONFIG);
        plugin.registerConfig(COMBAT_CONFIG);
        plugin.registerConfig(INV_CONFIG);
        plugin.registerConfig(MOV_CONFIG);
        plugin.registerConfig(INTERACT_CONFIG);
        plugin.registerConfig(SURVIVAL_CONFIG);
        plugin.registerConfig(SLIMEFUN_CONFIG);
        plugin.registerConfig(MODEL_CONFIG);
        plugin.registerConfig(MISC_CONFIG);
        plugin.registerConfig(TOGGLE_CONFIG);
    }

    public static final Predicate<String> REGEX_VALIDATOR = x -> {
        try {
            Pattern.compile(x);
            return true;
        } catch (PatternSyntaxException | NullPointerException pse) {
            return false;
        }
    };

    public static final Predicate<String> JSON_VALIDATOR = x -> {
        try {
            JsonParser.parseString(x);
            return true;
        } catch (JsonParseException | NullPointerException pse) {
            return false;
        }
    };

    public static final Predicate<String> IDENTIFIER_VALIDATOR = x -> {
        try {
            Objects.requireNonNull(Identifier.tryParse(x));
            return true;
        } catch (Throwable e) {
            return false;
        }
    };

    public static Predicate<Integer> intRange(int min, int max) {
        return x -> x >= min && x <= max;
    }

    public static Predicate<Integer> intHigher(int min) {
        return x -> x >= min;
    }

    public static Predicate<Integer> intLower(int mAX) {
        return x -> x <= mAX;
    }

    public static Predicate<Double> doubleRange(double min, double max) {
        return x -> x >= min && x <= max;
    }

    public static final Predicate<Integer> INT_NONNEGATIVE = intHigher(0);
    public static final Predicate<Integer> INT_POSITIVE = intHigher(1);

    public static final Config MINE_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/mine.yml", "mine settings")
            .markForSave();

    public static final Config CHAT_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/chat.yml", "chat settings")
            .markForSave();

    public static final Config RENDER_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/render.yml", "render settings")
            .markForSave();

    public static final Config EXTRA_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/test.yml", "test settings")
            .markForSave();

    public static final Config COMBAT_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/combat.yml", "combat settings")
            .markForSave();

    public static final Config INV_CONFIG = ConfigLoader.loadExternalConfig("sfhelper-configs/inv.yml", "inv settings")
            .markForSave();

    public static final Config MOV_CONFIG = ConfigLoader.loadExternalConfig("sfhelper-configs/mov.yml", "mov settings")
            .markForSave();

    public static final Config INTERACT_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/interact.yml", "interact settings")
            .markForSave();

    public static final Config SURVIVAL_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/survival.yml", "survival settings")
            .markForSave();

    public static final Config SLIMEFUN_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/slimefun.yml", "slimefun settings")
            .markForSave();

    public static final Config MODEL_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/models.yml", "model settings")
            .markForSave();

    static {
        final File cfgFile = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("sfhelper-configs/hotkeys.yml")
                .toFile();
        File tgtFile = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("sfhelper-configs/misc.yml")
                .toFile();
        if (cfgFile.exists() && !tgtFile.exists()) {
            cfgFile.renameTo(tgtFile);
        }
        File internalFile = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("sfhelper-configs/internal.yml")
                .toFile();
        if (internalFile.exists()) {
            internalFile.delete();
        }
        internalFile = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("sfhelper-configs/http.yml")
                .toFile();
        if (internalFile.exists()) {
            internalFile.delete();
        }
    }

    public static final Config MISC_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/misc.yml", "misc settings")
            .markForSave();

    public static final Config TOGGLE_CONFIG = ConfigLoader.loadExternalConfig(
                    "sfhelper-configs/toggles.yml", "toggle settings")
            .markForSave();
}
