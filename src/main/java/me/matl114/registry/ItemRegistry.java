package me.matl114.registry;

import net.minecraft.world.item.Item;

public class ItemRegistry {
    public static void init() {}

    public static Item TESTITEM;

    static {
        // 26.2: Items.registerItem 已改为 private，外部无法注册测试物品。
        // 该 TESTITEM 仅用于开发期占位，此处置空（不影响运行期逻辑）。
        TESTITEM = null;
    }
}
