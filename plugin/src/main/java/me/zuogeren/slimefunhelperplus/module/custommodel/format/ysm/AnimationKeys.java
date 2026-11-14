// Source: https://github.com/sdf123098/Sparkle-Morpher (common/src/main/java/com/micaftic/morpher/resource/YsmAnimationParsing.java)
// Branch/commit: fa26.2 @ 816235f2
// Copyright (c) 2026 Marciken / MIT
package me.zuogeren.slimefunhelperplus.module.custommodel.format.ysm;

/**
 * 动画类型 id 与动画文件 key 的对应关系。
 *
 * <p>YSM 包内动画文件按 {@code animation-<key>} 命名，反序列化时只拿到数字 id，
 * 需要在这里换回 key 才能挂到 {@code animationFiles} 上。
 */
public final class AnimationKeys {

    private AnimationKeys() {}

    public static String getAnimKeyFromType(int type) {
        return switch (type) {
            case 1 -> "main";
            case 2 -> "arm";
            case 3 -> "extra";
            case 4 -> "tac";
            case 5 -> "arrow";
            case 6 -> "carryon";
            case 7 -> "parcool";
            case 8 -> "swem";
            case 9 -> "slashblade";
            case 10 -> "tlm";
            case 11 -> "fp_arm";
            case 12 -> "immersive_melodies";
            case 13 -> "irons_spell_books";
            default -> "unk_" + type;
        };
    }
}
