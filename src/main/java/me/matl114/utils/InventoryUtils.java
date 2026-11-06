package me.matl114.utils;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.hacks.InvTasks;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.inventory.*;
import me.matl114.versioned.api.VItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

@ApiMethod
public class InventoryUtils {
    public static Iterable<ItemStack> iterable(Container inventory) {
        return inventory;
    }

    public static Container createReadOnlyOneItemInventory(Supplier<ItemStack> itemStackSupplier) {
        return new ImmutableInventory() {
            @Override
            public int getContainerSize() {
                return 1;
            }

            @Override
            public ItemStack getItem(int slot) {
                return itemStackSupplier.get();
            }
        };
    }

    public static final Codec<IndexEntry<ItemStack>> STACK_WITH_SLOT_CODEC = RecordCodecBuilder.create((instance) -> {
        return instance.group(
                        ExtraCodecs.UNSIGNED_BYTE.fieldOf("Slot").orElse(0).forGetter(IndexEntry::index),
                        VItem.ITEM_STACK_MAP_CODEC.forGetter(IndexEntry::val))
                .apply(instance, IndexEntry::new);
    });

    public static final Codec<IndexEntry<CompoundTag>> NBT_STACK_WITH_SLOT_CODEC = CompoundTag.CODEC.comapFlatMap(
            s -> {
                if (!s.contains("id")) {
                    return DataResult.error(() -> "Can not find field \"id\"");
                }
                if (s.get("Slot") instanceof NumericTag number) {
                    CompoundTag nbt2 = new CompoundTag(new HashMap<>(s.tags));
                    nbt2.remove("Slot");
                    return DataResult.success(new IndexEntry<>(number.intValue(), nbt2));
                } else {
                    return DataResult.error(() -> "Can not find field \"Slot\"");
                }
            },
            s -> {
                CompoundTag compound = new CompoundTag(new HashMap<>(s.val().tags));
                compound.putByte("Slot", (byte) s.index());
                return compound;
            });

    public static Container createReadOnlyInventory(List<ItemStack> itemStackSupplier) {
        return new ImmutableListInventory(itemStackSupplier);
    }

    public static Container createInventory(List<ItemStack> itemStackSupplier) {
        return createInventory(itemStackSupplier.size(), itemStackSupplier);
    }

    public static Container createInventory(int size, List<ItemStack> itemStackSupplier) {
        return new MutableInventory(size, itemStackSupplier);
    }

    public static Container createInventory(ItemStack[] array) {
        return new MutableArrayInventory(array);
    }

    public static Container createSubInventoryView(Container view, int from, int to) {

        return new ImmutableInventory() {
            @Override
            public int getContainerSize() {
                return Math.min(view.getContainerSize(), to) - Math.min(view.getContainerSize(), from);
            }

            @Override
            public ItemStack getItem(int slot) {
                return view.getItem(slot + from);
            }
        };
    }

    public static Stream<ItemStack> streamInventory(Container inv) {
        return IntStream.range(0, inv instanceof Inventory pinv ? getPlayerInvSize() : inv.getContainerSize())
                .mapToObj(inv::getItem);
    }

    public static boolean isContainer(AbstractContainerMenu handler) {
        return !(handler instanceof CreativeModeInventoryScreen.ItemPickerMenu)
                && !(handler instanceof InventoryMenu)
                && !(handler instanceof CraftingMenu);
    }

    public static Container getTopInventory(AbstractContainerScreen<?> screen) {
        if (screen instanceof ContainerScreen generic) {
            return generic.getMenu().getContainer();
        } else {
            List<Slot> slots = screen.getMenu().slots;
            int index = 0;
            for (var i = 0; i < slots.size(); i++) {
                if (slots.get(i).container instanceof Inventory pinv) {
                    index = i;
                    break;
                }
            }
            return new SlotInventory(slots.subList(0, index));
        }
    }

    public static Container getTopInventory(AbstractContainerMenu screen) {
        if (screen instanceof ChestMenu generic) {
            return generic.getContainer();
        } else {
            List<Slot> slots = screen.slots;
            int index = 0;
            for (var i = 0; i < slots.size(); i++) {
                if (slots.get(i).container instanceof Inventory pinv) {
                    index = i;
                    break;
                }
            }
            return new SlotInventory(slots.subList(0, index));
        }
    }

    public static Container getBottomInventory(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        int index = 0;
        for (var i = 0; i < slots.size(); i++) {
            if (slots.get(i).container instanceof Inventory pinv) {
                index = i;
                break;
            }
        }
        return new SlotInventory(slots.subList(index, slots.size()));
    }

    public static Container getBottomInventory(AbstractContainerMenu handler) {
        List<Slot> slots = handler.slots;
        int index = 0;
        for (var i = 0; i < slots.size(); i++) {
            if (slots.get(i).container instanceof Inventory pinv) {
                index = i;
                break;
            }
        }
        return new SlotInventory(slots.subList(index, slots.size()));
    }

    public static List<IndexEntry<ItemStack>> getInventoryEntries(Container inv) {
        List<IndexEntry<ItemStack>> entries = new ArrayList<>();
        for (var re = 0; re < inv.getContainerSize(); ++re) {
            ItemStack stack = inv.getItem(re);
            if (!stack.isEmpty()) {
                entries.add(new IndexEntry<>(re, stack));
            }
        }
        return entries;
    }

    public static List<ItemStack> getContainerInventory(ItemContainerContents container) {
        // 上游是 yarn ContainerComponent.stream()（**全部槽位**，空槽给 EMPTY 占位）→ mojmap 对应 allItemsCopyStream()。
        // nonEmptyItemCopyStream() 对应的是 yarn streamNonEmpty()，会把空槽过滤掉、索引不再与槽位对齐。
        //#if MC >= 260300
        //$$ return container.itemCopies().toList();
        //#else
        return container.allItemsCopyStream().toList();
        //#endif
    }

    public static List<ItemStack> getContainerFromItem(ItemStack itemStack) {
        if (ItemStackUtils.hasInPatch(itemStack, DataComponents.CONTAINER)) {
            ItemContainerContents component = ItemStackUtils.getInPatch(itemStack, DataComponents.CONTAINER);
            if (component != null) {
                return getContainerInventory(component);
            }
        }
        return null;
    }

    private static final Minecraft mc = Minecraft.getInstance();

    public static IndexEntry<ItemStack> findPlayerItem(
            Predicate<ItemStack> predicate, boolean doNotFSearchWhenOpenOtherScreen, boolean acceptEmpty) {
        return findPlayerItem(predicate, doNotFSearchWhenOpenOtherScreen, acceptEmpty, true);
    }

    public static IndexEntry<ItemStack> findPlayerItem(
            Predicate<ItemStack> predicate,
            boolean doNotFSearchWhenOpenOtherScreen,
            boolean acceptEmpty,
            boolean handPriority) {
        return findPlayerItem(predicate, doNotFSearchWhenOpenOtherScreen, acceptEmpty, handPriority, false);
    }

    public static IndexEntry<ItemStack> findPlayerItem(
            Predicate<ItemStack> predicate, int size, boolean doNotFSearchWhenOpenOtherScreen, boolean acceptEmpty) {
        return findPlayerItem(predicate, size, doNotFSearchWhenOpenOtherScreen, acceptEmpty, true, false);
    }

    public static IndexEntry<ItemStack> findPlayerItem(
            Predicate<ItemStack> predicate,
            int size,
            boolean doNotFSearchWhenOpenOtherScreen,
            boolean acceptEmpty,
            boolean handPriority,
            boolean offHandPriority) {
        return findPlayerInventory(
                (val) -> predicate.test(val.val()),
                size,
                doNotFSearchWhenOpenOtherScreen,
                acceptEmpty,
                handPriority,
                offHandPriority);
    }

    public static IndexEntry<ItemStack> findPlayerItem(
            Predicate<ItemStack> predicate,
            boolean doNotFSearchWhenOpenOtherScreen,
            boolean acceptEmpty,
            boolean handPriority,
            boolean offHandPriority) {
        return findPlayerInventory(
                (val) -> predicate.test(val.val()),
                getPlayerInvSize(),
                doNotFSearchWhenOpenOtherScreen,
                acceptEmpty,
                handPriority,
                offHandPriority);
    }

    public static IndexEntry<ItemStack> findPlayerInventory(
            Predicate<IndexEntry<ItemStack>> predicate, boolean doNotFSearchWhenOpenOtherScreen, boolean acceptEmpty) {
        return findPlayerInventory(
                predicate, getPlayerInvSize(), doNotFSearchWhenOpenOtherScreen, acceptEmpty, true, false);
    }

    public static IndexEntry<ItemStack> findPlayerInventory(
            Predicate<IndexEntry<ItemStack>> predicate,
            int searchTo,
            boolean doNotFSearchWhenOpenOtherScreen,
            boolean acceptEmpty,
            boolean handPriority,
            boolean offHandPriority) {
        Inventory pinv = mc.player.getInventory();
        ItemStack item = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        // we assert player hold block while scaffold, or it will be really annoying
        // the holding block must be a full cube
        int selecedSlot = pinv.getSelectedSlot();
        IndexEntry<ItemStack> result = null;
        IndexEntry<ItemStack> test;
        if ((acceptEmpty || item.count() != 0) && predicate.test((test = new IndexEntry<>(selecedSlot, item)))) {
            result = test;
        }
        if (handPriority && result != null) {
            return result;
        }
        if (result == null && offHandPriority) {
            item = mc.player.getItemInHand(InteractionHand.OFF_HAND);
            if ((acceptEmpty || item.count() != 0) && predicate.test((test = new IndexEntry<>(40, item)))) {
                result = test;
            }
            if (result != null) {
                return result;
            }
        }
        // while player is open Screen
        if (doNotFSearchWhenOpenOtherScreen
                && ClientPlayerAccess.of(mc.player).getServerScreenHandler().containerId
                        != mc.player.inventoryMenu.containerId) {
            return result;
        }
        for (var i = 0; i < searchTo; ++i) {
            ItemStack stack = pinv.getItem(i);
            test = new IndexEntry<>(i, stack);
            if ((acceptEmpty || !stack.isEmpty()) && predicate.test(test)) {
                //                if(keepInHand.get()){
                //                    MovTasks.getMovExtra().sendPacketsForInventoryAction();
                //                    OptionalInt slotIndex = mc.player.currentScreenHandler.getSlotIndex(pinv, i);
                //                    if(slotIndex.isPresent()){
                //                        mc.gameMode.handleContainerInput(mc.player.currentScreenHandler.syncId,
                // slotIndex.getAsInt(), selecedSlot, ContainerInput.SWAP, mc.player);
                //                        return selecedSlot;
                //                    }
                //                }else
                return new IndexEntry<>(i, stack);
            }
        }
        return null;
    }

    public static IndexEntry<ItemStack> findPlayerHotBarItem(
            Predicate<ItemStack> predicate, boolean acceptEmpty, boolean acceptOffhand) {
        Inventory pinv = mc.player.getInventory();
        ItemStack item = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        // we assert player hold block while scaffold, or it will be really annoying
        // the holding block must be a full cube
        int selecedSlot = pinv.getSelectedSlot();
        if ((acceptEmpty || item.count() != 0) && predicate.test(item)) {
            return new IndexEntry<>(selecedSlot, item);
        }
        if (acceptOffhand) {
            item = mc.player.getItemInHand(InteractionHand.OFF_HAND);
            if ((acceptEmpty || item.count() != 0) && predicate.test(item)) {
                return new IndexEntry<>(40, item);
            }
        }

        for (var i = 0; i < 9; ++i) {
            ItemStack stack = pinv.getItem(i);
            if (i == selecedSlot) continue;
            if ((acceptEmpty || !stack.isEmpty()) && predicate.test(stack)) {
                //                if(keepInHand.get()){
                //                    MovTasks.getMovExtra().sendPacketsForInventoryAction();
                //                    OptionalInt slotIndex = mc.player.currentScreenHandler.getSlotIndex(pinv, i);
                //                    if(slotIndex.isPresent()){
                //                        mc.gameMode.handleContainerInput(mc.player.currentScreenHandler.syncId,
                // slotIndex.getAsInt(), selecedSlot, ContainerInput.SWAP, mc.player);
                //                        return selecedSlot;
                //                    }
                //                }else
                return new IndexEntry<>(i, stack);
            }
        }
        return null;
    }

    public static IndexEntry<ItemStack> findBestPlayerItem(
            Function<ItemStack, Double> maxFunction, boolean doNotFSearchWhenOpenOtherScreen, boolean acceptEmpty) {
        return findBestPlayerInventory(
                s -> {
                    return maxFunction.apply(s.val());
                },
                doNotFSearchWhenOpenOtherScreen,
                acceptEmpty);
    }

    public static IndexEntry<ItemStack> findBestPlayerItem(
            Function<ItemStack, Double> maxFunction,
            int size,
            boolean doNotFSearchWhenOpenOtherScreen,
            boolean acceptEmpty) {
        return findBestPlayerInventory(
                s -> {
                    return maxFunction.apply(s.val());
                },
                size,
                doNotFSearchWhenOpenOtherScreen,
                acceptEmpty);
    }

    public static IndexEntry<ItemStack> findBestPlayerInventory(
            Function<IndexEntry<ItemStack>, Double> maxFunction,
            boolean doNotFSearchWhenOpenOtherScreen,
            boolean acceptEmpty) {
        return findBestPlayerInventory(maxFunction, getPlayerInvSize(), doNotFSearchWhenOpenOtherScreen, acceptEmpty);
    }

    public static IndexEntry<ItemStack> findBestPlayerInventory(
            Function<IndexEntry<ItemStack>, Double> maxFunction,
            int searchTo,
            boolean doNotFSearchWhenOpenOtherScreen,
            boolean acceptEmpty) {
        // while player is open Screen
        Inventory pinv = mc.player.getInventory();
        ItemStack item = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        // we assert player hold block while scaffold, or it will be really annoying
        // the holding block must be a full cube
        int selecedSlot = pinv.getSelectedSlot();
        Double maxValue = null;
        IndexEntry<ItemStack> result = null;
        IndexEntry<ItemStack> test = null;
        if ((acceptEmpty || item.count() != 0)) {
            test = new IndexEntry<>(selecedSlot, item);
            maxValue = maxFunction.apply(test);
            if (maxValue != null) {
                result = test;
            }
        }
        if (doNotFSearchWhenOpenOtherScreen
                && ClientPlayerAccess.of(mc.player).getServerScreenHandler().containerId
                        != mc.player.inventoryMenu.containerId) {
            return result;
        }

        Double currentValue;
        for (var i = 0; i < searchTo; ++i) {
            ItemStack stack = pinv.getItem(i);
            test = new IndexEntry<>(i, stack);
            if ((acceptEmpty || !stack.isEmpty()) && (currentValue = maxFunction.apply(test)) != null) {
                if (maxValue == null || currentValue > maxValue) {
                    maxValue = currentValue;
                    result = test;
                }
            }
        }
        return result;
    }

    public static IndexEntry<Slot> findScreenSlot(List<Slot> slots, Predicate<Slot> predicate, boolean acceptEmpty) {
        for (var i = 0; i < slots.size(); ++i) {
            var slot = slots.get(i);
            ItemStack stack = slot.getItem();
            if (!acceptEmpty && stack.isEmpty()) continue;
            if (predicate.test(slot)) {
                return new IndexEntry<>(i, slot);
            }
        }
        return null;
    }

    public static IndexEntry<Slot> findPlayerBackpackItem(
            Predicate<ItemStack> predicate, boolean acceptEmpty, boolean includeCraft) {
        return findPlayerBackpackSlot((slot) -> predicate.test(slot.getItem()), acceptEmpty, includeCraft);
    }

    public static IndexEntry<Slot> findPlayerBackpackSlot(
            Predicate<Slot> predicate, boolean acceptEmpty, boolean includeCraft) {
        var handler = mc.player.inventoryMenu;
        var serverHandler = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
        if (serverHandler.containerId == handler.containerId) {
            if (includeCraft) {
                for (var i = 1; i < 5; ++i) {
                    var slot = handler.slots.get(i);
                    if (!acceptEmpty && slot.getItem().isEmpty()) continue;
                    if (predicate.test(slot)) {
                        return new IndexEntry<>(i, slot);
                    }
                }
            }
            var pinv = mc.player.getInventory();
            for (var i = 0; i < getPlayerInvSize(); ++i) {
                int slotIndex = InvTasks.getScreenSlotByInventoryIndex(i);
                var slot = handler.slots.get(slotIndex);
                if (!acceptEmpty && slot.getItem().isEmpty()) continue;
                if (predicate.test(slot)) {
                    return new IndexEntry<>(slotIndex, slot);
                }
            }
            return null;
        } else {
            return null;
        }
    }

    public static IndexEntry<Slot> findBestScreenSlot(
            List<Slot> slots, Function<Slot, Double> maxFunction, boolean acceptEmpty) {
        IndexEntry<Slot> result = null;
        Double maxVal = null;
        for (var i = 0; i < slots.size(); ++i) {
            var slot = slots.get(i);
            ItemStack stack = slot.getItem();
            if (!acceptEmpty && stack.isEmpty()) continue;
            Double val = maxFunction.apply(slot);
            if (val != null) {
                if (maxVal == null || maxVal < val) {
                    maxVal = val;
                    result = new IndexEntry<>(i, slot);
                }
            }
        }
        return result;
    }

    public static IndexEntry<Slot> findScreenItem(
            List<Slot> slots, Predicate<ItemStack> predicate, boolean acceptEmpty) {
        for (var i = 0; i < slots.size(); ++i) {
            var slot = slots.get(i);
            ItemStack stack = slot.getItem();
            if (!acceptEmpty && stack.isEmpty()) continue;
            if (predicate.test(stack)) {
                return new IndexEntry<>(i, slot);
            }
        }
        return null;
    }

    public static int computePlayerInventory(Item maxFunction) {
        return (int) computePlayerInventory(
                (stack) -> {
                    if (stack.is(maxFunction)) {
                        return (double) stack.getCount();
                    } else {
                        return null;
                    }
                },
                false);
    }

    public static double computePlayerInventory(Function<ItemStack, Double> maxFunction, boolean acceptEmpty) {
        double sum = 0.0D;
        Double currentValue;
        Inventory pinv = mc.player.getInventory();
        for (var i = 0; i < getPlayerInvSize(); ++i) {
            ItemStack stack = pinv.getItem(i);
            if ((acceptEmpty || !stack.isEmpty()) && (currentValue = maxFunction.apply(stack)) != null) {
                sum += currentValue;
            }
        }
        return sum;
    }

    public static int getPlayerBackpackSize() {
        return 36;
    }

    public static int getPlayerInvSize() {
        // 傻逼mojang你给玩家放特么的saddle槽位干什么
        return 41;
    }

    public static IndexEntry<ItemStack> findItem(Container inventory, Item predicate) {
        return findItem(inventory, (v) -> v.is(predicate), predicate == Items.AIR);
    }

    public static IndexEntry<ItemStack> findItem(
            Container inventory, Predicate<ItemStack> predicate, boolean acceptEmpty) {
        for (var i = 0; i < inventory.getContainerSize(); ++i) {
            if (!acceptEmpty && inventory.getItem(i).isEmpty()) continue;
            if (predicate.test(inventory.getItem(i))) {
                return new IndexEntry<>(i, inventory.getItem(i));
            }
        }
        return null;
    }

    public static IndexEntry<ItemStack> findInventory(
            Container inventory, Predicate<IndexEntry<ItemStack>> predicate, boolean acceptEmpty) {
        IndexEntry<ItemStack> result;
        for (var i = 0; i < inventory.getContainerSize(); ++i) {
            if (!acceptEmpty && inventory.getItem(i).isEmpty()) continue;
            if (predicate.test(result = new IndexEntry<>(i, inventory.getItem(i)))) {
                return result;
            }
        }
        return null;
    }

    public static IndexEntry<ItemStack> findBestItem(
            Container inventory, Function<ItemStack, Double> predicate, boolean acceptEmpty) {
        return findBestInventory(inventory, (v) -> predicate.apply(v.val()), acceptEmpty);
    }

    public static IndexEntry<ItemStack> findBestInventory(
            Container inventory, Function<IndexEntry<ItemStack>, Double> predicate, boolean acceptEmpty) {
        IndexEntry<ItemStack> maxResult = null;

        Double maxValue = null;
        for (var i = 0; i < inventory.getContainerSize(); ++i) {
            if (!acceptEmpty && inventory.getItem(i).isEmpty()) continue;
            IndexEntry<ItemStack> result = new IndexEntry<>(i, inventory.getItem(i));
            Double value = predicate.apply(result);
            if (value == null) {
                continue;
            } else if (maxValue == null || maxValue < value) {
                maxValue = value;
                maxResult = result;
            }
        }
        return maxResult;
    }

    public static int getSelectedSlot() {
        return mc.player.getInventory().getSelectedSlot();
    }

    @Nonnull
    public static IndexEntry<ItemStack> getSelectedItem() {
        return new IndexEntry<>(
                InventoryUtils.getSelectedSlot(), mc.player.getInventory().getSelectedItem());
    }

    public static Map<ItemStackSample, IntList> collectItemIndexes(Iterable<ItemStack> stacks) {
        Map<ItemStackSample, IntList> indexMap = new LinkedHashMap<>();
        int idx = 0;
        for (ItemStack stack : stacks) {
            indexMap.computeIfAbsent(ItemStackSample.of(stack), (i) -> new IntArrayList())
                    .add(idx);
            idx += 1;
        }
        return indexMap;
    }

    public static void copyInventory(Inventory from, Inventory to) {
        for (var re = 0; re < InventoryUtils.getPlayerInvSize(); ++re) {
            to.setItem(re, from.getItem(re).copy());
        }
        to.setSelectedSlot(from.getSelectedSlot());
    }
}
