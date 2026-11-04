package me.matl114.hacks.utils.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import me.matl114.gui.Constants;
import me.matl114.gui.McWidgetHelpers;
import me.matl114.gui.basic.ButtonAction;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.basic.SubScreenWidget;
import me.matl114.gui.elements.IconElement;
import me.matl114.gui.elements.SlotElement;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.hacks.InvTasks;
import me.matl114.managers.config.NBTParsable;
import me.matl114.managers.config.NBTType;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.config.AttrKeyValue;
import me.matl114.versioned.api.VItem;
import me.matl114.versioned.api.VNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ItemStackTemplate implements NBTParsable<ItemStackTemplate> {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Codec<CompoundTag> NBT_COMPOUND_CODEC = VNbt.CODEC.comapFlatMap(
            element -> element instanceof CompoundTag compound
                    ? DataResult.success(compound)
                    : DataResult.error(() -> "ItemStackTemplate source must be an NBT compound"),
            compound -> compound);

    public static final NBTType<ItemStackTemplate> TYPE = new NBTType<>(
            "itemstacktemplate",
            NBT_COMPOUND_CODEC.xmap(ItemStackTemplate::new, ItemStackTemplate::nbt),
            ItemStackTemplate::createWidget,
            new ItemStackTemplate(new CompoundTag()));

    private CompoundTag nbt;

    @Nullable
    private RegistryAccess cachedRegistry;

    @Nullable
    private ItemStack cachedItemStack;

    public ItemStackTemplate(CompoundTag nbt) {
        this.nbt = nbt.copy();
    }

    /**
     * 26.2 起 {@code new ItemStack(Item)} 会读数据组件注册表，而本类是在模块构造期（配置默认值）
     * 被 new 的，那时 Components 还没绑定，会抛 {@code NullPointerException: Components not bound yet}。
     * 这里只写 {@code id} 字段：{@code ItemStack.MAP_CODEC} 的 {@code count} 缺省为 1、
     * {@code components} 缺省为空 patch，因此二者解码结果等价。
     */
    public ItemStackTemplate(Item item) {
        this(withItemId(item));
    }

    private static CompoundTag withItemId(Item item) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", BuiltInRegistries.ITEM.getKey(item).toString());
        return tag;
    }

    private ItemStackTemplate(ItemStack itemStack) {
        this(VItem.getInstance().toNbt(itemStack, ItemStackUtils.registry()));
    }

    public CompoundTag nbt() {
        return nbt.copy();
    }

    public Identifier itemId() {
        Identifier id = Identifier.tryParse(nbt.getStringOr("id", "minecraft:air"));
        return id != null && BuiltInRegistries.ITEM.containsKey(id) ? id : BuiltInRegistries.ITEM.getKey(Items.AIR);
    }

    public void setType(Identifier id) {
        CompoundTag updated = new CompoundTag();
        updated.putString("id", id.toString());
        updated.putInt("count", 1);
        nbt = updated;
        cachedRegistry = null;
        cachedItemStack = null;
    }

    @Nullable
    private static RegistryAccess getDynamicRegistryManager() {
        return MC.getConnection() == null ? null : MC.getConnection().registryAccess();
    }

    /**
     * 配置界面里的图标预览。
     *
     * <p>没有连接（主菜单开 ClickGui）时解不了 NBT：模板解码要并入数据包的注册表，那时手上只有
     * 内置注册表。于是改拿物品本身建栈 —— 与本类 NBT 只写 {@code id} 的表示等价（{@code count}
     * 缺省 1、{@code components} 缺省空 patch）。
     *
     * <p>但 26.2 起 {@code ItemStack(Holder)} 会读 {@code Holder.Reference#components()}，物品的数据
     * 组件由 {@code DataComponentInitializers} 在加载数据包时统一绑定（集成服务端
     * {@code ReloadableServerResources#updateComponentsAndStaticRegistryTags}、客户端
     * {@code RegistryDataCollector#updateComponents}）——本次启动还没进过世界时它仍是 null，
     * 构造会抛 {@code NullPointerException: Components not bound yet}。未绑定时只给空槽；组件绑定后
     * 不再清空，进过一次世界再回主菜单图标就回来了。
     */
    private ItemStack previewStack() {
        if (getDynamicRegistryManager() != null) {
            return itemStack();
        }
        Item item = BuiltInRegistries.ITEM.getValue(itemId());
        return BuiltInRegistries.ITEM.wrapAsHolder(item).areComponentsBound() ? new ItemStack(item) : ItemStack.EMPTY;
    }

    public ItemStack itemStack() {
        RegistryAccess registry = ItemStackUtils.registry();
        if (cachedItemStack == null || cachedRegistry != registry) {
            ItemStack decoded = null;
            try {
                decoded = VItem.getInstance().fromNbt(nbt, registry);
            } catch (Throwable ignored) {
                // 组件未绑定时解码必然失败（构造 ItemStack 要读 holder.components()）。
                // 解码不出来只能算空槽，不能退回 new ItemStack(Items.AIR)：那同样会抛。
            }
            cachedItemStack = decoded == null || decoded.isEmpty() ? ItemStack.EMPTY : decoded;
            cachedRegistry = registry;
        }
        return cachedItemStack;
    }

    public void setItemStack(ItemStack itemStack) {
        RegistryAccess registry = ItemStackUtils.registry();
        nbt = VItem.getInstance().toNbt(itemStack, registry);
        cachedRegistry = registry;
        cachedItemStack = itemStack.copy();
    }

    @Override
    public NBTType<ItemStackTemplate> type() {
        return TYPE;
    }

    private static DrawableWidget createWidget(
            ElementFactory factory, AttrKeyValue<ItemStackTemplate> value, int x, int y, int dx, int dy) {
        boolean hasPlayer = MC.player != null;
        int iconSize = Math.min(dy, dx / (hasPlayer ? 4 : 2));
        int inputWidth = Math.max(0, dx - (hasPlayer ? 4 : 2) * iconSize);
        BooleanSupplier canEdit = () -> getDynamicRegistryManager() != null;
        var input = McWidgetHelpers.createTextFieldEditBox(
                iconSize,
                0,
                inputWidth,
                dy,
                raw -> {
                    Identifier id = Identifier.tryParse(raw);
                    ItemStackTemplate template = value.get();
                    if (template != null
                            && id != null
                            && BuiltInRegistries.ITEM.containsKey(id)
                            && !id.equals(template.itemId())) {
                        template.setType(id);
                        value.accept(template);
                    }
                },
                value.get() == null ? "minecraft:air" : value.get().itemId().toString());
        ButtonAction openEditor = ButtonAction.run(() -> {
            if (!canEdit.getAsBoolean()) {
                return;
            }
            ItemStackTemplate template = value.get();
            if (template == null) {
                return;
            }
            InvTasks.openEditScreen(template.itemStack(), edited -> {
                template.setItemStack(edited);
                value.accept(template);
                input.getDelegate().setValue(template.itemId().toString());
            });
        });

        SubScreenWidget widget = new SubScreenWidget(x, y, dx, dy);
        if (iconSize > 0) {
            widget.addDrawableChild(factory.createElement(
                    new SlotElement(() -> {
                                ItemStackTemplate template = value.get();
                                return template == null ? ItemStack.EMPTY : template.previewStack();
                            })
                            .setSlotFrame(false),
                    null,
                    null,
                    0,
                    (dy - iconSize) / 2,
                    iconSize,
                    iconSize));
        }
        if (inputWidth > 0) {
            widget.addDrawableChild(input);
        }
        if (iconSize > 0) {
            widget.addDrawableChild(factory.createElement(
                    IconElement.fixedGui(Constants.EDITOR_SPRITE, openEditor)
                            .withActiveActionCondition(icon -> canEdit.getAsBoolean()),
                    null,
                    null,
                    iconSize + inputWidth,
                    (dy - iconSize) / 2,
                    iconSize,
                    iconSize));
        }
        if (hasPlayer && iconSize > 0) {
            widget.addDrawableChild(factory.createExecuteButton(
                    () -> Component.translatableWithFallback("widget.nbt-parsable.item-stack-template.import", "导入"),
                    List::of,
                    ButtonAction.run(() -> {
                        if (MC.player == null) {
                            return;
                        }
                        ItemStackTemplate imported = new ItemStackTemplate(
                                MC.player.getMainHandItem().copy());
                        value.accept(imported);
                        input.getDelegate().setValue(imported.itemId().toString());
                    }),
                    2 * iconSize + inputWidth,
                    (dy - iconSize) / 2,
                    2 * iconSize,
                    iconSize));
        }
        return widget;
    }
}
