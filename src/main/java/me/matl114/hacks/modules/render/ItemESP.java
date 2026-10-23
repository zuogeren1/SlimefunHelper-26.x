package me.matl114.hacks.modules.render;

import me.matl114.utils.ClientUtils;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import me.matl114.accessors.events.EntityAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.MetadataUpdate;
import me.matl114.events.impl.Render2D;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.*;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.ItemStackUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import me.matl114.versioned.api.VDataFlag;
import me.matl114.versioned.api.VItem;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class ItemESP extends BaseModule {
    public final ModulePath detectEntity = makePath(Configs.RENDER_CONFIG, "detect-entity");
    public final ModulePath itemEsp = detectEntity.add("item-esp");

    public ItemESP() {
        super("ItemESP");
        bindFlag(enable);
    }

    // 启用开关

    boolean pendingUpdateEntities = false;
    public List<NbtPredicate> predicate;
    public Set<Item> itemSet = new HashSet<>();

    public void updatePredicate(List<CompoundTag> compound) {
        if (compound == null || compound.isEmpty()) {
            predicate = null;
        } else {
            predicate = compound.stream().map(NbtPredicate::new).toList();
        }
        launchDelayUpdateTask();
    }

    public void updateSet(EntrySet<Item> reg) {
        Set<Item> set = reg.set();
        if (!Objects.equals(set, itemSet)) {
            itemSet = set;
            launchDelayUpdateTask();
        }
    }

    public FlagRef enable = flagBuilder(itemEsp.addEnable()).build();

    public FlagRef enableSimple = flagBuilder(itemEsp.add("enable-simple")).build();

    public FlagRef enableSpecial = flagBuilder(itemEsp.add("enable-item"))
            .updateListener(s -> launchDelayUpdateTask())
            .build();
    public FlagRef enableFrame = flagBuilder(itemEsp.add("enable-frame"))
            .updateListener(s -> launchDelayUpdateTask())
            .build();

    public FlagRef drawName = flagBuilder(itemEsp.add("draw-name")).build();

    public NBTRef<TracingOption> option = builder(itemEsp.add("options"), TracingOption.class)
            .defaultValue(new TracingOption(true, false))
            .build();

    public FlagRef nameSimple = flagBuilder(itemEsp.add("name-display")).build();

    // NBT 谓词（字符串格式，默认为空）
    public NBTRef<PrimitiveList<CompoundTag>> nbtPredicate = builder(
                    itemEsp.add("nbt-predicate"), PrimitiveList.<CompoundTag>parameter())
            .defaultValue(new PrimitiveList<>(NBTTypes.NBT_COMPOUND_TYPE, List.of()))
            .updateListener(s -> updatePredicate(s.list()))
            .build();

    // 物品类型过滤器（默认识别所有物品）
    public NBTRef<EntrySet<Item>> itemType = builder(itemEsp.add("item-type"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(
                    new Regex(
                            "^(.*ton_skull|netherite.*|.*_star|.*_apple|.*potion|tot.*|end_c.*l|obsi.*|.*anchor|expe.*|mace|ely.*|.*shulker.*|trident)$"),
                    BuiltInRegistries.ITEM))
            .updateListener(this::updateSet)
            .build();

    public NBTRef<TracingOption> specialOptions = builder(itemEsp.add("special-options"), TracingOption.class)
            .defaultValue(new TracingOption(true, true))
            .build();

    public FlagRef nameSpecial = builder(itemEsp.add("name-display-special"), Boolean.class)
            .defaultValue(true)
            .build();
    // 颜色（使用 WrapColor，默认绿色）
    public NBTRef<WrapColor> color = builder(itemEsp.add("color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.YELLOW)))
            .build();

    // 颜色（使用 WrapColor，默认绿色）
    public NBTRef<WrapColor> specialColor = builder(itemEsp.add("special-color"), WrapColor.class)
            .defaultValue(new WrapColor(("#ED0355")))
            .build();

    public NBTRef<WrapColor> nameColor = builder(itemEsp.add("name-display-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.WHITE)))
            .build();

    public NBTRef<WrapColor> nameSpecialColor = builder(itemEsp.add("special-name-display-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.WHITE)))
            .build();

    public DoubleRef nameScale =
            doubleBuilder(itemEsp.add("name-scale")).defaultValue(0.75D).build();
    // 可选：热键（若需要可取消注释，并实现对应的 KeyBindRef）
    // public KeyBindRef hotkey = keyBindBuilder(Configs.RENDER_CONFIG, HOTKEY).build();

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        launchDelayUpdateTask();
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.ITEM), this::handleItemEntityItemData);
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.ITEM_FRAME), this::handleItemFrameItemData);
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.GLOW_ITEM_FRAME),
                this::handleItemFrameItemData);
        registerListener(Listener.getPostTick(), this::onUpdate);
        registerListener(RenderListener.getRender3DEvent(), this::onRenderEntity3D);
        registerListener(RenderListener.getRender2DEvent(), this::onRenderEntity2D);
    }

    public boolean testItem(ItemStack stack) {
        return itemType.get().test(stack.getItem()) || (predicate != null && testItemData(stack));
    }

    private boolean testItemData(ItemStack stack) {
        DataComponentPatch changes = stack.getComponentsPatch();
        try {
            CompoundTag nbtCompound = changes.isEmpty()
                    ? new CompoundTag()
                    : (CompoundTag) DataComponentPatch.CODEC
                            .encodeStart(ItemStackUtils.registry().createSerializationContext(NbtOps.INSTANCE), changes)
                            .getOrThrow();
            for (var re : predicate) {
                if (re.matches(nbtCompound)) return true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private static final String ITEM_ESP_METADATA_KEY = "slimefunhelper:item_esp_show_key";

    public void markItemToRender(Entity entity) {
        markItemToRender(entity, Component.empty());
    }

    public void markItemToRender(Entity entity, Component text) {
        EntityAccess.of(entity).getMetadata().put(this, ITEM_ESP_METADATA_KEY, text);
    }

    public void removeItemFromRender(Entity entity) {
        var access = EntityAccess.of(entity);
        if (!access.isMetaEmpty()) {
            access.getMetadata().put(this, ITEM_ESP_METADATA_KEY, null);
        }
    }

    public void launchDelayUpdateTask() {
        if (!pendingUpdateEntities) {
            pendingUpdateEntities = true;
            Tasks.scheduleRepeated(
                    () -> {
                        if (pendingUpdateEntities) {
                            if (!checkNull()
                                    && (ClientUtils.getScreen(mc) == null
                                            || ClientUtils.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
                                pendingUpdateEntities = false;
                                if (enableSpecial.get()) {
                                    for (var entity : mc.level.entitiesForRendering()) {
                                        if (entity instanceof ItemEntity item) {
                                            onItemEntity(item, item.getItem());
                                        } else if (entity instanceof ItemFrame frame && enableFrame.get()) {
                                            onItemEntity(frame, frame.getItem());
                                        }
                                    }
                                }

                                return true;
                            }
                            return false;
                        }
                        return true;
                    },
                    1,
                    1);
        }
    }

    public void onItemEntity(Entity itemEntity, ItemStack stack) {
        if (!stack.isEmpty() && testItem(stack)) {
            markItemToRender(
                    itemEntity,
                    VItem.getInstance()
                            .getFormattedName(stack)
                            .append(ChatUtils.stringToText("&ex%d".formatted(stack.getCount()))));
        } else {
            removeItemFromRender(itemEntity);
        }
    }

    public void handleItemEntityItemData(Event<MetadataUpdate> entryUpdateEvent) {
        if (enableSpecial.get()) {
            var entry = entryUpdateEvent.context().metadata();
            if (entry.id() == VDataFlag.ID_ITEM_ITEMSTACK
                    && (entry.value()) instanceof ItemStack stack
                    && entryUpdateEvent.context.entity() instanceof ItemEntity item) {
                onItemEntity(item, stack);
            }
        }
    }

    public void handleItemFrameItemData(Event<MetadataUpdate> entryUpdateEvent) {
        if (enableSpecial.get() && enableFrame.get()) {
            var entry = entryUpdateEvent.context().metadata();
            if (entry.id() == VDataFlag.ID_ITEM_FRAME_ITEMSTACK
                    && entry.value() instanceof ItemStack stack
                    && entryUpdateEvent.context.entity() instanceof ItemFrame item) {
                onItemEntity(item, stack);
            }
        }
    }

    final RenderCollector<AABB> boxCollector = RenderCollectors.createBoxCollector(true, false, false);
    final RenderCollector<Vec3> tracerCollector = RenderCollectors.createTracerCollector();

    final RenderCollector<RenderElements.Text> textCollector = RenderCollectors.createTextCollector();

    public void onUpdate() {
        boxCollector.clear();
        tracerCollector.clear();
        textCollector.clear();
        if (checkNull()) {
            return;
        }

        if (enable.get()) {
            boolean special = enableSpecial.get();
            boolean common = enableSimple.get();
            int color = this.color.get().withAlpha(255);
            int specialColor = this.specialColor.get().withAlpha(255);
            int nameColor = this.nameColor.get().withAlpha(255);
            int specialNameColor = this.nameSpecialColor.get().withAlpha(255);
            TracingOption op = option.get();
            TracingOption specialOp = specialOptions.get();
            if (special || common) {
                for (var entity : mc.level.entitiesForRendering()) {
                    if ((entity instanceof ItemEntity i || (enableFrame.get() && entity instanceof ItemFrame))) {
                        if (special
                                && entity instanceof EntityAccess<?> access
                                && !access.isMetaEmpty()
                                && access.getMetadata().get(this, ITEM_ESP_METADATA_KEY)
                                        instanceof Component displayText) {
                            if (specialOp.box()) {
                                boxCollector.submit(entity.getBoundingBox(), specialColor);
                            }
                            if (specialOp.line()) {
                                tracerCollector.submit(entity.getBoundingBox().getCenter(), specialColor);
                            }
                            if (nameSpecial.get()) {
                                textCollector.submit(
                                        new RenderElements.Text(
                                                displayText,
                                                entity.getBoundingBox().getCenter(),
                                                (float) nameScale.get()),
                                        specialNameColor);
                            }
                            continue;
                        }
                        if (common) {
                            if (op.box()) {
                                boxCollector.submit(entity.getBoundingBox(), color);
                            }
                            if (op.line()) {
                                tracerCollector.submit(entity.getBoundingBox().getCenter(), color);
                            }
                            if (nameSimple.get()) {
                                Component text;
                                if (entity instanceof ItemEntity entity1
                                        && !entity1.getItem().isEmpty()) {
                                    ItemStack stack = entity1.getItem();
                                    text = VItem.getInstance()
                                            .getFormattedName(stack)
                                            .append(ChatUtils.stringToText("&ex%d".formatted(stack.getCount())));
                                } else if (entity instanceof ItemFrame entity1
                                        && !entity1.getItem().isEmpty()) {
                                    text = VItem.getInstance().getFormattedName(entity1.getItem());
                                } else {
                                    text = null;
                                }
                                if (text != null) {
                                    textCollector.submit(
                                            new RenderElements.Text(
                                                    text,
                                                    entity.getBoundingBox().getCenter(),
                                                    (float) nameScale.get()),
                                            nameColor);
                                }
                            }
                            continue;
                        }
                    }
                }
            }
        }
    }

    public void onRenderEntity3D(Event<Render3D> event) {
        if (enable.get()) {
            PoseStack stack = event.context().stack();
            RenderUtils.startDrawVirtual(stack);
            try {
                boxCollector.render3D(stack, event.context().partialTicks());
                tracerCollector.render3D(stack, event.context().partialTicks());
            } finally {
                RenderUtils.stopDrawVirtual(stack);
            }
        }
    }

    public void onRenderEntity2D(Event<Render2D> vdrawEvent) {
        if (enable.get()) {
            textCollector.render2D(vdrawEvent.context.drawContext(), vdrawEvent.context.partialTicks());
        }
    }
}
