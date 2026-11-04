package me.matl114.gui.presets.single;

import java.util.function.Function;
import me.matl114.utils.ItemStackUtils;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.Identifier;

public interface IIcon<T> {
    /**
     * 26.2：ItemStack 必须在物品组件绑定之后才能构造。
     * 原先这里是 public static ItemStack DEFAULT_NULL_ICON = new ItemStack(Items.BARRIER);，
     * 会在接口初始化时就 new —— 而 IIcon 是在 SlimefunHelper.onInitialize 的
     * RenderTasks → INameTag → RegistryDisplays → IIcon 这条链上被加载的，那时组件还没绑定，
     * 直接抛 NullPointerException: Components not bound yet（实机冒烟抓到）。
     * 改成惰性持有：内部类只在首次访问时才初始化。
     */
    final class NullIconHolder {
        private static ItemStack instance;

        public static ItemStack get() {
            if (instance == null) {
                if (!ItemStackUtils.itemComponentsBound()) {
                    return ItemStack.EMPTY;
                }
                instance = new ItemStack(Items.BARRIER);
            }
            return instance;
        }
    }

    public static IIcon<?> EMPTY = ((x, y, context, registerValue) -> {
        context.drawItem(NullIconHolder.get(), x, y, 999, 0);
    });

    default void render(int startIndexX, int startIndexY, VDrawContext context, T registerValue) {
        if (registerValue == null) {
            context.drawItem(NullIconHolder.get(), startIndexX, startIndexY, 114514, 0);
        } else {
            renderNonnull(startIndexX, startIndexY, context, registerValue);
        }
    }

    public void renderNonnull(int width, int height, VDrawContext context, T registerValue);

    public static <T> IIcon<T> renderItem(Function<T, ItemStack> function) {
        return ((startIndexX, startIndexY, context, registerValue) -> {
            ItemStack stack = ItemStackUtils.itemComponentsBound() ? function.apply(registerValue) : ItemStack.EMPTY;
            context.drawItem(stack, startIndexX, startIndexY, 114514, 0);
        });
    }

    public static <T> IIcon<T> renderSprite(Function<T, ?> function) {
        return ((startIndexX, startIndexY, context, registerValue) -> {
            var re = function.apply(registerValue);
            if (re instanceof Identifier identifier) {
                context.drawGuiTexture(identifier, startIndexX, startIndexY, 16, 16);
            } else if (re instanceof TextureAtlasSprite sprite) {
                context.drawSprite(sprite, startIndexX, startIndexY, 0, 16, 16);
            }
        });
    }
}
