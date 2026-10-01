package me.matl114.hacks;

import me.matl114.utils.ClientUtils;

import com.google.common.base.Preconditions;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import lombok.Getter;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.interfaces.TileInventory;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.gui.elements.SlotElement;
import me.matl114.hacks.api.ModuleGroup;
import me.matl114.hacks.api.ModuleManager;
import me.matl114.hacks.modules.HackModules;
import me.matl114.hacks.modules.inv.*;
import me.matl114.hacks.utils.ItemCache;
import me.matl114.managers.*;
import me.matl114.managers.config.DoubleRef;
import me.matl114.utils.*;
import me.matl114.utils.inventory.ItemStackSample;
import me.matl114.utils.itemdb.ItemStackData;
import me.matl114.utils.itemdb.ItemStackDataWithAmount;
import me.matl114.utils.tasks.LimitedSpeedExecutor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.BlockState;

public class InvTasks {
    public static void init() {}

    private static final Minecraft mc = Minecraft.getInstance();

    @ApiMethod
    public static Screen getCurrentServerScreen(Player player) {
        if (player == null) {
            return null;
        }
        Screen nowScreen = null;
        if (player instanceof ClientPlayerAccess access) {
            nowScreen = access.getServerOpeningScreen();
        } else {
            nowScreen = ClientUtils.getScreen();
        }
        // ignore Creative screen as it is not handled by server
        if (nowScreen instanceof CreativeModeInventoryScreen) {
            return null;
        }
        return nowScreen;
    }

    @ApiMethod
    public static AbstractContainerMenu getCurrentServerScreenHandler(LocalPlayer player) {
        return player == null ? null : ClientPlayerAccess.of(player).getServerScreenHandler();
    }

    @ApiMethod
    public static boolean isHandledScreen(Screen screen) {
        return screen instanceof AbstractContainerScreen;
    }

    @ApiMethod
    public static boolean quickMoveSlotItem(AbstractContainerScreen screen, Slot slot, boolean speedLimit) {
        if (slot != null) {
            AbstractContainerMenu handler = screen.getMenu();
            ItemStack cleanedStack = ItemStackUtils.getCleanedItem(slot.getItem(), false, false);
            boolean isPlayerInventory = slot.container instanceof Inventory;

            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot2 = handler.getSlot(i);
                if (((slot2.container instanceof Inventory) == isPlayerInventory)
                        && ItemStack.isSameItem(cleanedStack, slot2.getItem())
                        && ItemStack.isSameItemSameComponents(
                                cleanedStack, ItemStackUtils.getCleanedItem(slot2.getItem(), false, false))) {
                    quickMoveSlot(screen.getMenu(), i, false, speedLimit);
                }
            }
            return true;
        }
        return false;
    }

    public static void quickMoveSlot(AbstractContainerMenu handler, int index, boolean ignoreConfig, boolean speedLimit) {
        if (!ignoreConfig
                && handler.getSlot(index).getItem().getCount() > 1
                && getFastInv().enableLeftOne.get()) {
            //            Debug.info("quick move 1");
            int syncId = handler.containerId;
            if (!handler.getCarried().isEmpty()) {
                Debug.chat(Component.literal("[left 1] ")
                        .withStyle(ChatFormatting.RED)
                        .append(Component.literal("cursor stack needs to be empty to apply left-one quickMove")));
                return;
            }
            Slot slot = handler.getSlot(index);
            if (slot.getItem().isEmpty()) {
                return;
            }
            boolean tryTake = slot.container instanceof Inventory;
            boolean hasPlace = false;
            for (Slot s : handler.slots) {
                // skip same-side inventory
                if ((s.container instanceof Inventory) == tryTake) {
                    continue;
                }
                if (s.getItem().isEmpty()
                        || (s.getItem().getCount() < s.getItem().getMaxStackSize()
                                && ItemStack.isSameItemSameComponents(slot.getItem(), s.getItem()))) {
                    hasPlace = true;
                    break;
                }
            }
            // minimize packet amount sent
            if (!hasPlace) {
                return;
            }
            (speedLimit ? clickExecutor : unlimitedClickExecutor).execute(() -> {
                if (mc.gameMode == null || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                    return false;
                }
                mc.gameMode.handleContainerInput(syncId, index, 0, ContainerInput.PICKUP, mc.player);
                mc.gameMode.handleContainerInput(syncId, index, 1, ContainerInput.PICKUP, mc.player);
                return true;
            });
            ItemStack sample = handler.getCarried();
            if (sample.isEmpty()) {
                return;
            }
            // save sample
            sample = sample.copy();
            for (int i = 0; i < handler.slots.size(); ++i) {
                Slot s = handler.slots.get(i);
                if ((s.container instanceof Inventory) == tryTake) {
                    continue;
                }
                if (s.getItem().isEmpty()
                        || (s.getItem().getCount() < s.getItem().getMaxStackSize()
                                && ItemStack.isSameItemSameComponents(slot.getItem(), s.getItem()))) {
                    final int fi = i;
                    (speedLimit ? clickExecutor : unlimitedClickExecutor).execute(() -> {
                        if (mc.gameMode == null
                                || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                            return false;
                        }
                        mc.gameMode.handleContainerInput(syncId, fi, 0, ContainerInput.PICKUP, mc.player);
                        return true;
                    });
                    if (handler.getCarried().isEmpty()) {
                        return;
                    }
                }
            }
            (speedLimit ? clickExecutor : unlimitedClickExecutor).execute(() -> {
                if (mc.gameMode == null || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                    return false;
                }
                if (!handler.getCarried().isEmpty()) {
                    mc.gameMode.handleContainerInput(syncId, index, 0, ContainerInput.PICKUP, mc.player);
                }
                return true;
            });

            //            while (handler.getSlot(index).getItem().getCount() > 1){
            //                mc.gameMode.handleContainerInput(syncId, index, 1,ContainerInput.PICKUP,mc.player);
            //                mc.gameMode.handleContainerInput(syncId, index, 0, ContainerInput.QUICK_MOVE, mc.player);
            //                mc.gameMode.handleContainerInput(syncId, index, 0,);
            //            }
            //                mc.gameMode.handleContainerInput(syncId, index, 1,ContainerInput.PICKUP,mc.player);
            //                int maxTry = 33;
            //                while (handler.getCursorStack().getCount() > 1){
            //                    mc.gameMode.handleContainerInput(syncId, index, 1, ContainerInput.PICKUP, mc.player);
            //                    if(-- maxTry <= 0){
            //                        break;
            //                    }
            //                }
            //                mc.gameMode.handleContainerInput(syncId, index, 0, ContainerInput.QUICK_MOVE, mc.player);
            //                mc.gameMode.handleContainerInput(syncId, index, 1,ContainerInput.PICKUP,mc.player);

        } else {
            (speedLimit ? clickExecutor : unlimitedClickExecutor).execute(() -> {
                if (mc.gameMode == null || InvTasks.getCurrentServerScreenHandler(mc.player) != handler) {
                    return false;
                }
                if (!handler.getCarried().isEmpty()) {

                    mc.gameMode.handleContainerInput(handler.containerId, -999, 0, ContainerInput.PICKUP, mc.player);
                }
                mc.gameMode.handleContainerInput(handler.containerId, index, 0, ContainerInput.QUICK_MOVE, mc.player);
                return true;
            });
        }
    }

    public static void setCreativeInventory(ItemStack itemStack, int slot) {
        if (slot < InventoryUtils.getPlayerInvSize()) {
            mc.player.getInventory().setItem(slot, itemStack.copy());
            mc.gameMode.handleCreativeModeItemAdd(itemStack, INVENTORY_INDEX_TO_SCREEN_SLOT[slot]);
        }
    }

    // from hotbars, backpack contents.  equipments (feet to head 36-39), offhand 40, (craftingResult 41, craftingSlots
    // 42-45)
    private static final int[] INVENTORY_INDEX_TO_SCREEN_SLOT = new int[] {
        36, 37, 38, 39, 40, 41, 42, 43, 44, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
        28, 29, 30, 31, 32, 33, 34, 35, 8, 7, 6, 5, 45, 0, 1, 2, 3, 4
    };
    private static final int[] SCREEN_SLOT_TO_INVENTORY_INDEX = new int[46];

    static {
        for (var i = 0; i < INVENTORY_INDEX_TO_SCREEN_SLOT.length; ++i) {
            SCREEN_SLOT_TO_INVENTORY_INDEX[INVENTORY_INDEX_TO_SCREEN_SLOT[i]] = i;
        }
    }

    @ApiMethod
    public static int getScreenSlotByInventoryIndex(int v) {
        return INVENTORY_INDEX_TO_SCREEN_SLOT[v];
    }

    @ApiMethod
    public static int getInventoryIndexByScreenSlot(int v) {
        return SCREEN_SLOT_TO_INVENTORY_INDEX[v];
    }

    @ApiMethod
    public static void creativeGive(ItemStack itemStack, int count) {
        if (mc.player != null
                && mc.gameMode != null
                && mc.gameMode.getPlayerMode().isCreative()) {
            InventoryMenu inventoryView = mc.player.inventoryMenu;
            int stackMax = itemStack.getMaxStackSize();
            for (int i : INVENTORY_INDEX_TO_SCREEN_SLOT) {
                Slot slot0 = inventoryView.getSlot(i);
                int transfer = 0;
                ItemStack stackToSet = null;
                if (slot0.getItem().isEmpty()) {
                    transfer = Math.min(stackMax, count);
                    stackToSet = itemStack.copyWithCount(transfer);
                } else if (slot0.getItem().getCount() < stackMax
                        && ItemStack.isSameItemSameComponents(slot0.getItem(), itemStack)) {
                    transfer = Math.min(stackMax - slot0.getItem().getCount(), count);
                    stackToSet = itemStack.copyWithCount(slot0.getItem().getCount() + transfer);
                }
                count -= transfer;
                if (stackToSet != null) {
                    mc.gameMode.handleCreativeModeItemAdd(stackToSet, i);
                    slot0.setByPlayer(stackToSet);
                }
                if (count <= 0) return;
            }
            ItemStack sample = itemStack.copy();
            while (count > 0) {
                int transfer = Math.min(stackMax, count);
                count -= transfer;
                sample.setCount(transfer);
                mc.gameMode.handleCreativeModeItemDrop(sample);
            }
        }
    }

    @ApiMethod
    public static void creativeAddItem(ItemStack itemStack, int count) {
        if (mc.player != null
                && mc.gameMode != null
                && mc.gameMode.getPlayerMode().isCreative()) {
            int slot = -1;

            InventoryMenu inventoryView = mc.player.inventoryMenu;
            int stackMax = itemStack.getMaxStackSize();
            int countA = count;
            for (int i : INVENTORY_INDEX_TO_SCREEN_SLOT) {
                Slot slot0 = inventoryView.getSlot(i);
                if (slot0.getItem().isEmpty()) {
                    slot = i;
                    break;
                } else if (slot0.getItem().getCount() < stackMax
                        && ItemStack.isSameItemSameComponents(slot0.getItem(), itemStack)) {
                    slot = i;
                    countA = count + slot0.getItem().getCount();
                    break;
                }
            }
            ItemStack stackToGive = itemStack.copyWithCount(Math.min(stackMax, countA));
            if (slot != -1) {
                inventoryView.getSlot(slot).setByPlayer(stackToGive);
                mc.gameMode.handleCreativeModeItemAdd(stackToGive, slot);
            } else {
                mc.gameMode.handleCreativeModeItemDrop(stackToGive);
            }
        }
    }

    @ApiMethod
    public static void creativeDrop(ItemStack itemStack, int count) {
        if (itemStack.isEmpty()) return;
        mc.gameMode.handleCreativeModeItemDrop(itemStack.copyWithCount(count));
    }

    @ApiMethod
    public static void copyGiveCommand(ItemStack itemStack) {

        mc.keyboardHandler.setClipboard(createGiveCommand(itemStack.copyWithCount(itemStack.getMaxStackSize())));
    }

    @ApiMethod
    public static String createGiveCommand(ItemStack itemStack) {
        if (itemStack.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("/minecraft:give @s ");
        builder.append(BuiltInRegistries.ITEM.getKey(itemStack.getItem()));
        if (ItemStackUtils.hasInPatch(itemStack)) {
            builder.append('[');
            Map<DataComponentType, Optional> map = new HashMap<>(itemStack.components.patch);
            int index = 0;
            for (var entry : map.entrySet()) {
                if (index > 0) {
                    builder.append(',');
                }
                Identifier identifier = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
                if (identifier != null) {
                    String cmp = identifier.toString();
                    Optional val = entry.getValue();
                    if (val.isPresent()) {
                        try {
                            String nbtSeri = entry.getKey()
                                    .codecOrThrow()
                                    .encodeStart(
                                            ItemStackUtils.registry().createSerializationContext(NbtOps.INSTANCE),
                                            val.get())
                                    .getOrThrow()
                                    .toString();
                            builder.append(cmp).append('=').append(nbtSeri);
                            index++;
                        } catch (Throwable e) {
                            // exception, skip
                            Debug.chat(e.getMessage());
                        }
                    } else {
                        builder.append('!').append(cmp);
                        index++;
                    }
                }
            }
            builder.append(']');
        }
        builder.append(" ").append(itemStack.getCount());
        return builder.toString();
    }

    @ApiMethod
    public static boolean isScreenHandlerValid(AbstractContainerMenu handler) {
        return mc.player != null && mc.player.containerMenu == handler;
    }

    @ApiMethod
    public static void quickMoveSlotOrDrop(AbstractContainerMenu handler, int slot) {
        if (!isScreenHandlerValid(handler)) return;
        if (!handler.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(handler.containerId, -999, 0, ContainerInput.PICKUP, mc.player);
        }
        quickMoveSlot(handler, slot, true, false);
        if (!handler.getSlot(slot).getItem().isEmpty()) {
            mc.gameMode.handleContainerInput(handler.containerId, slot, 1, ContainerInput.THROW, mc.player);
        }
    }

    @Getter
    public static class SlotMatchingResult {
        public ItemStack sample;
        public int count;
        public IntList slots;

        public SlotMatchingResult() {
            count = 0;
            slots = new IntArrayList();
            this.sample = null;
        }

        public void setItemSample(ItemStack stack) {
            this.sample = stack.copy();
        }

        public void addMatchingSlot(int idx, Slot slot) {
            slots.add(idx);
            count += slot.getItem().getCount();
        }

        public int[] toIntArray() {
            return slots.toIntArray();
        }
    }

    @ApiMethod
    public static SlotMatchingResult allSlotMatch(AbstractContainerScreen handledScreen) {
        var result = new SlotMatchingResult();
        int[] array = IntStream.range(0, handledScreen.getMenu().slots.size()).toArray();
        result.slots = new IntArrayList(array);
        return result;
    }

    @ApiMethod
    public static SlotMatchingResult getContainerSlots(AbstractContainerScreen handledScreen) {
        var result = new SlotMatchingResult();
        var allSlots = handledScreen.getMenu().slots;
        int size = allSlots.size();
        for (int i = 0; i < size; ++i) {
            Slot slot = allSlots.get(i);
            if (slot != null && !(slot.container instanceof Inventory)) {
                // all match
                result.slots.add(i);
            }
        }
        return result;
    }

    @ApiMethod
    public static SlotMatchingResult getPlayerInventorySlots(AbstractContainerMenu handledScreen) {
        var result = new SlotMatchingResult();
        var allSlots = handledScreen.slots;
        int size = allSlots.size();
        for (int i = 0; i < size; ++i) {
            Slot slot = allSlots.get(i);
            if (slot != null && slot.container instanceof Inventory) {
                // all match
                result.slots.add(i);
            }
        }
        return result;
    }

    @ApiMethod
    public static SlotMatchingResult getEmptySlots(AbstractContainerMenu handledScreen, int... slots) {
        var result = new SlotMatchingResult();
        var allSlots = handledScreen.slots;
        for (int i : slots) {
            Slot slot = allSlots.get(i);
            if (slot != null && slot.getItem().isEmpty()) {

                // all match
                result.slots.add(i);
            }
        }
        return result;
    }

    @ApiMethod
    public static SlotMatchingResult getItemStackMatchingSlot(
            AbstractContainerMenu screen, ItemStack stack, int... list) {
        if (stack.isEmpty()) return getEmptySlots(screen, list);
        var result = new SlotMatchingResult();
        result.setItemSample(stack);
        var allSlots = screen.slots;
        for (int i : list) {
            Slot slot = allSlots.get(i);
            if (slot != null && !slot.getItem().isEmpty()) {

                if (ItemStack.isSameItemSameComponents(slot.getItem(), stack)) {
                    // all match
                    result.addMatchingSlot(i, slot);
                }
            }
        }
        return result;
    }

    /**
     * move items that match itemStack from the trustedSlotIndexList to the toSlot, try adding toAmountAdd count of itemStack
     * @param handledScreen
     * @param itemStack
     * @param toSlot
     * @param toAmountAdd
     * @param removeExist
     * @param trustedSlotIndexList
     */
    @ApiMethod
    public static void moveStackToSlot(
            AbstractContainerMenu handledScreen,
            ItemStack itemStack,
            int toSlot,
            int toAmountAdd,
            boolean removeExist,
            int... trustedSlotIndexList) {
        if (itemStack.isEmpty()) return;
        AbstractContainerMenu handler = handledScreen;
        if (!isScreenHandlerValid(handler)) return;
        // 操作前先清空指针
        if (!handler.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(handler.containerId, -999, 0, ContainerInput.PICKUP, mc.player);
        }
        ItemStack stackAt = handler.getSlot(toSlot).getItem();
        int toAmount = toAmountAdd;
        if (!stackAt.isEmpty()) {
            if (ItemStack.isSameItemSameComponents(stackAt, itemStack)) {
                toAmount += stackAt.getCount();
            } else {
                // 不要动
                if (!removeExist) return;
                // remove stackAt
                mc.gameMode.handleContainerInput(handler.containerId, toSlot, 1, ContainerInput.QUICK_MOVE, mc.player);
                // 不是哥们怎么没取完啊
                if (!handler.getSlot(toSlot).getItem().isEmpty()) {
                    // 看我给你丢出去
                    mc.gameMode.handleContainerInput(handler.containerId, toSlot, 1, ContainerInput.THROW, mc.player);
                }
            }
        }
        // 填满一组不需要控制数量!
        if (toAmount >= itemStack.getMaxStackSize()) {
            toAmount = itemStack.getMaxStackSize();
            if (handler.getSlot(toSlot).getItem().getCount() >= toAmount) {
                return;
            }
            // 直接填满就行
            for (var i : trustedSlotIndexList) {
                if (!handler.getSlot(i).getItem().isEmpty()
                        && ItemStack.isSameItemSameComponents(handler.getSlot(i).getItem(), itemStack)) {
                    moveStackFromTo(handler, i, toSlot);
                    if (handler.getSlot(toSlot).getItem().getCount() >= toAmount) {
                        break;
                    }
                }
            }
        } else {
            // 考虑数量
            for (var i : trustedSlotIndexList) {
                if (!handler.getSlot(i).getItem().isEmpty()
                        && ItemStack.isSameItemSameComponents(handler.getSlot(i).getItem(), itemStack)) {
                    int currentAmount = handler.getSlot(toSlot).getItem().getCount();
                    //
                    if (currentAmount + handler.getSlot(i).getItem().getCount() > toAmount) {
                        // satisfy , use tasks to
                        moveStackFromToAmount(handler, i, toSlot, toAmount - currentAmount);
                        break;
                    } else {
                        moveStackFromTo(handler, i, toSlot);
                        if (handler.getSlot(toSlot).getItem().getCount() >= toAmount) {
                            break;
                        }
                    }
                }
            }
        }
    }

    public static void moveRecipePatternToContainer(
            AbstractContainerMenu screen,
            ItemStack[] ingredients,
            int[] slot,
            int patternAmount,
            boolean removeOrigin,
            BiFunction<AbstractContainerMenu, ItemStack, SlotMatchingResult> slotMatchProvider) {
        int size = ingredients.length;
        Preconditions.checkArgument(slot.length == size);
        Map<ItemStackSample, IntList> stackRecipe = new HashMap<>();
        IntList emptySlots = new IntArrayList();
        for (int i = 0; i < size; ++i) {
            ItemStack item = ingredients[i];
            if (item != null && item.count() != 0) {
                ItemStackSample sample = new ItemStackSample(item);
                int index = i;
                stackRecipe.compute(sample, (key, list) -> {
                    if (list == null) {
                        list = new IntArrayList();
                    }
                    list.add(index);
                    return list;
                });
            } else {
                emptySlots.add(i);
            }
        }

        for (var mapEntry : stackRecipe.entrySet()) {
            ItemStackSample sample = mapEntry.getKey();
            //            String sampleId = getSfIdOrNull(sample.sample());
            var matchResult = slotMatchProvider.apply(screen, sample.sample());
            // getItemStackMatchingSlot(screen, sample.sample(), true, playerInventory);
            int counter = matchResult.count;
            int[] cachedSlots = matchResult.toIntArray();
            ItemStack realStack = matchResult.sample;

            //            int size = allSlots.size();
            //            for (int i=0; i< size; ++i){
            //                Slot slot = allSlots.get(i);
            //                if(slot != null && slot.inventory instanceof PlayerInventory && !slot.getStack().isEmpty()
            // ){
            //                    if(realStack != null){
            //                        if( ItemStack.areItemsAndComponentsEqual(slot.getStack(), realStack)){
            //                            //all match
            //                            cachedSlots.add(i);
            //                            counter += slot.getStack().getCount();
            //                        }
            //                    }else {
            //                        //the first match itemStack will be the realStack template
            //                        if(Objects.equals(sampleId,getSfIdOrNull(slot.getStack()) )){
            //                            realStack = slot.getStack();
            //                            cachedSlots.add(i);
            //                            counter += slot.getStack().getCount();
            //                        }
            //                    }
            //
            //                }
            //            }
            // nothing match this sample, , , counter must be 0, there is no meaning doing left
            if (realStack == null || counter == 0) {
                continue;
            }
            // copy stack to avoid modification
            // do not copy because it must be copied
            //            realStack = realStack.copy();
            int needed = 0;
            for (var i : mapEntry.getValue()) {
                needed += ingredients[i].getCount();
            }
            int maxSupply = Math.min(counter / needed, patternAmount);
            for (var i : mapEntry.getValue()) {
                int slotNeed = ingredients[i].getCount() * maxSupply;
                InvTasks.moveStackToSlot(screen, realStack, slot[i], slotNeed, removeOrigin, cachedSlots);
            }
        }
        for (var i : emptySlots) {
            InvTasks.quickMoveSlotOrDrop(screen, i);
        }
    }

    @ApiMethod
    public static void moveStackFromTo(AbstractContainerMenu handler, int fromIndex, int toSlot) {
        mc.gameMode.handleContainerInput(handler.containerId, fromIndex, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(handler.containerId, toSlot, 0, ContainerInput.PICKUP, mc.player);
        if (!handler.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(handler.containerId, fromIndex, 0, ContainerInput.PICKUP, mc.player);
        }
    }

    @ApiMethod
    public static void moveStackFromToAmount(AbstractContainerMenu handler, int fromIndex, int toSlot, int amount) {
        Slot currentFrom = handler.getSlot(fromIndex);
        Slot currentTo = handler.getSlot(toSlot);
        int currentFromAmount = currentFrom.getItem().getCount();

        // from 的数量完全不够
        if (currentFromAmount <= amount) {
            moveStackFromTo(handler, fromIndex, toSlot);
            return;
        } else {
            // from的数量超出了,我们只需要amount个
            int currentToAmount = currentTo.getItem().getCount();
            int max = currentFrom.getItem().getMaxStackSize();
            if (currentToAmount + amount >= max) {
                // 如果amount赛过去就满了《那和直接把from赛过去一样
                moveStackFromTo(handler, fromIndex, toSlot);
                return;
            } else {
                // amount < max - currentTo
                // currentFrom > amount
                for (int __ = 0; __ < 10; ++__) {
                    if (amount <= 0) {
                        return;
                    }
                    int halfTrans = (currentFromAmount + 1) / 2;
                    int distanceToHalf = Math.abs(halfTrans - amount);
                    int minDelta = Math.min(Math.min(amount, currentFromAmount - amount), distanceToHalf);
                    if (minDelta == amount) {
                        mc.gameMode.handleContainerInput(
                                handler.containerId, fromIndex, 0, ContainerInput.PICKUP, mc.player);
                        for (var i = 0; i < amount; ++i) {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, toSlot, 1, ContainerInput.PICKUP, mc.player);
                        }
                        if (!handler.getCarried().isEmpty()) {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, fromIndex, 0, ContainerInput.PICKUP, mc.player);
                        }
                        return;
                    } else if (minDelta == currentFromAmount - amount) {
                        mc.gameMode.handleContainerInput(
                                handler.containerId, fromIndex, 0, ContainerInput.PICKUP, mc.player);
                        for (int i = 0; i < minDelta; ++i) {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, fromIndex, 1, ContainerInput.PICKUP, mc.player);
                        }
                        mc.gameMode.handleContainerInput(
                                handler.containerId, toSlot, 0, ContainerInput.PICKUP, mc.player);
                        return;
                    } else {
                        //
                        if (halfTrans <= amount) {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, fromIndex, 1, ContainerInput.PICKUP, mc.player);
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, toSlot, 0, ContainerInput.PICKUP, mc.player);
                            // 通过计算currentTo增长了多少来更新amount
                            amount = amount - currentTo.getItem().getCount() + currentToAmount;
                            currentToAmount = currentTo.getItem().getCount();
                            currentFromAmount = currentFrom.getItem().getCount();
                            continue;
                        } else {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, fromIndex, 1, ContainerInput.PICKUP, mc.player);
                            int trans = (currentFromAmount + 1) / 2 - amount;
                            for (int i = 0; i < trans; ++i) {
                                mc.gameMode.handleContainerInput(
                                        handler.containerId, fromIndex, 1, ContainerInput.PICKUP, mc.player);
                            }
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, toSlot, 0, ContainerInput.PICKUP, mc.player);
                            return;
                        }
                    }
                }
                Debug.chat(Component.literal("Error while transfering itemStacks, which takes 10 more loop "));
            }
        }
    }

    @ApiMethod
    public static void openInventoryCacheScreen() {
        getChestHistory().openInventoryCacheScreen();
    }

    // 26.2: ItemStack 必须在组件绑定之后才能构造，改为首次访问时创建
    private static ItemStack invIconUnknownCache = null;
    private static ItemStack invIconNoItemCache = null;

    public static ItemStack invIconUnknown() {
        if (invIconUnknownCache == null) {
            invIconUnknownCache = new ItemStack(Items.BARRIER);
        }
        return invIconUnknownCache;
    }

    public static ItemStack invIconNoItem() {
        if (invIconNoItemCache == null) {
            invIconNoItemCache = new ItemStack(Items.BEDROCK);
        }
        return invIconNoItemCache;
    }

    public static ItemStack generateIconForScreen(AbstractContainerScreen<?> screen) {
        if (screen instanceof TileInventory tile && !tile.isVirtual()) {
            Block blockType = tile.getBlockType();
            if (blockType != null) {
                Item itemType = blockType.asItem();
                if (itemType != Items.AIR) {
                    return new ItemStack(itemType);
                }
            }
            return invIconNoItem();
        }
        return invIconUnknown();
    }
    // suppress random source use when dropItem
    public static final ThreadLocal<Boolean> SUPPRESS_DROPITEM_SPAWN = ThreadLocal.withInitial(() -> false);

    public static void clickSlotAsync(int slotId, int button, ContainerInput actionType) {
        if (mc.player == null) return;
        // handler or player inv

        AbstractContainerMenu screenHandler = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
        AbstractContainerMenu currentHandler = mc.player.containerMenu;

        int syncId = screenHandler.containerId;
        // won't miss any inject
        boolean shouldReplace = syncId != currentHandler.containerId;
        try {
            if (shouldReplace) {
                // use fake screen handler
                mc.player.containerMenu = screenHandler;
            }
            mc.gameMode.handleContainerInput(syncId, slotId, button, actionType, mc.player);
        } finally {
            if (shouldReplace) {
                mc.player.containerMenu = currentHandler;
            }
        }
        return;
    }

    // track screen syncId
    public static int LAST_SYNC_ID = 0;
    // for screen desync fix
    public static final int MAX_DEQUE_SIZE = 8;
    public static Deque<AbstractContainerMenu> historyScreens = new ArrayDeque<>();

    public static void onOpenScreen(Event<ClientboundOpenScreenPacket> packet) {
        LAST_SYNC_ID = packet.context.getContainerId();
    }

    public static void onOpenScreenCreate(Event<AbstractContainerScreen<?>> eventScreen) {
        if (eventScreen.context != null && eventScreen.context.getMenu() != null) {
            historyScreens.addLast(eventScreen.context.getMenu());
        }
        while (historyScreens.size() > MAX_DEQUE_SIZE) {
            historyScreens.removeFirst();
        }
    }

    public static void onInventoryOld(Event<ClientboundContainerSetSlotPacket> invS2CPacket) {
        if (mc.player == null || mc.level == null) return;
        int syncId = invS2CPacket.context.getContainerId();
        if (mc.gameMode.getPlayerMode().isSurvival()
                && ClientPlayerAccess.of(mc.player).getServerScreenHandler().containerId != syncId
                && mc.player.containerMenu.containerId != syncId
                && syncId != 0) {
            // maybe we click too fast that we miss something
            var pkt = invS2CPacket.context;
            for (var handler : historyScreens) {
                if (handler.containerId == syncId) {
                    handler.setItem(pkt.getSlot(), pkt.getStateId(), pkt.getItem());
                    return;
                }
            }
        }
    }

    public static void onInventoryOld2(Event<ClientboundContainerSetContentPacket> eventInv) {
        if (mc.player == null || mc.level == null) return;
        int syncId = eventInv.context.containerId();
        if (mc.gameMode.getPlayerMode().isSurvival()
                && ClientPlayerAccess.of(mc.player).getServerScreenHandler().containerId != syncId
                && mc.player.containerMenu.containerId != syncId
                && syncId != 0) {
            // maybe we click too fast that we miss something
            var pkt = eventInv.context;
            for (var handler : historyScreens) {
                if (handler.containerId == syncId) {
                    handler.initializeContents(pkt.stateId(), pkt.items(), pkt.carriedItem());
                    return;
                }
            }
        }
    }

    public static void onGameJoin(Event<LocalPlayer> gameJoin) {
        LAST_SYNC_ID = 0;
        historyScreens.clear();
    }

    public static void openEditorForPlayer() {
        if (mc.player != null) {
            getItemEditor().openEditorForPlayer(mc.player);
        }
    }

    @ApiMethod
    public static void openEditScreen(ItemStack item, Consumer<ItemStack> callback) {
        getItemEditor().openEditScreen(item, callback);
    }

    public static SlotElement.SlotClickCallback getRightClickOpenEditScreenCallback() {
        return (item, button) -> {
            if (button == 1) getItemEditor().openEditScreen(item, null);
            return true;
        };
    }

    public static int predictOpenVanillaContainerSize(BlockPos blockPos) {
        if (mc.level.getBlockEntity(blockPos) instanceof Container inventory) {
            int size = inventory.getContainerSize();
            if (inventory instanceof ChestBlockEntity chest) {
                BlockState state = chest.getBlockState();
                if (state.getBlock() instanceof ChestBlock chestBlock) {
                    if (ChestBlock.isChestBlockedAt(mc.level, blockPos)) {
                        size = 0;
                    } else if (ChestBlock.getBlockType(state) != DoubleBlockCombiner.BlockType.SINGLE) {
                        size = 54;
                    }
                }
            }
            if (inventory instanceof ShulkerBoxBlockEntity shulker) {
                BlockState state = shulker.getBlockState();
                if (shulker.getAnimationStatus() == ShulkerBoxBlockEntity.AnimationStatus.CLOSED
                        && !InteractUtils.canShulkerOpen(mc.level, blockPos, state)) {
                    size = 0;
                }
            }
            return size;
        }
        return 0;
    }

    public static void executePredictInventoryAction(Container topInventory, Consumer<AbstractContainerMenu> callback) {
        // todo fix prediction initialization
        int nextPredictedIndex = (InvTasks.LAST_SYNC_ID % 100) + 1;
        AbstractContainerMenu fakeScreenHandler = new ChestMenu(
                ScreenUtils.getGenericScreenType(topInventory.getContainerSize()),
                nextPredictedIndex,
                mc.player.getInventory(),
                topInventory,
                ((topInventory.getContainerSize() - 1) / 9) + 1);
        ChestMenu.sixRows(nextPredictedIndex, mc.player.getInventory());
        AbstractContainerMenu handler = mc.player.containerMenu;
        try {
            mc.player.containerMenu = fakeScreenHandler;
            callback.accept(fakeScreenHandler);
        } finally {
            mc.player.containerMenu = handler;
        }
    }

    @Getter
    @ApiMethod
    public static final ModuleGroup moduleManager = new ModuleGroup("Inv");

    @Getter
    private static InvExtra invExtra;

    @Getter
    private static InvDesyncFix invDesyncFix;

    @Getter
    private static GuiMove guiMove;

    @Getter
    private static FastInv fastInv;

    @Getter
    private static FastCraft fastCraft;

    @Getter
    private static InvHelper invHelper;

    @Getter
    private static NoQDrop noQDrop;

    @Getter
    private static AutoStore autoStore;

    @Getter
    private static AutoSteal autoSteal;

    @Getter
    private static AutoShulker autoShulker;

    @Getter
    private static ChestHistory chestHistory;

    @Getter
    private static KitReplenish kitReplenish;

    @Getter
    private static ItemEditor itemEditor;

    // todo: remove
    @Getter
    private static QuickButton quickButton;

    @Getter
    private static SaveItem saveItem;

    @Getter
    private static NbtTooltips nbtTooltips;

    @Getter
    //
    private static final ItemCache customItemDatabase = new ItemCache("item-database.nbt");

    public static final Codec<ItemStackData> CUSTOM_ITEM_DATA_CODEC = customItemDatabase.createStackDataCodec();

    public static final Codec<ItemStackDataWithAmount> CUSTOM_AMOUNT_ITEM_DATA_CODEC =
            ItemStackDataWithAmount.createCodecOf(CUSTOM_ITEM_DATA_CODEC);

    @Getter
    private static final LimitedSpeedExecutor clickExecutor;

    @Getter
    private static final LimitedSpeedExecutor unlimitedClickExecutor;

    private static void initModules(ModuleManager m) {
        invExtra = new InvExtra().register(m);
        invDesyncFix = new InvDesyncFix().register(m);
        guiMove = new GuiMove().register(m);
        fastInv = new FastInv().register(m);
        fastCraft = new FastCraft().register(m);
        invHelper = new InvHelper().register(m);
        noQDrop = new NoQDrop().register(m);
        autoStore = new AutoStore().register(m);
        autoSteal = new AutoSteal().register(m);
        autoShulker = new AutoShulker().register(m);
        chestHistory = new ChestHistory().register(m);
        kitReplenish = new KitReplenish().register(m);

        itemEditor = new ItemEditor().register(m);
        quickButton = new QuickButton().register(m);
        saveItem = new SaveItem().register(m);
        nbtTooltips = new NbtTooltips().register(m);
    }

    static {
        Tasks.registerGameTask(r -> {
            InvTasks.clickExecutor.reset();
            InvTasks.unlimitedClickExecutor.reset();
        });

        Listener.getPacketPoint().getChannel(ClientboundOpenScreenPacket.class).registerHandler(InvTasks::onOpenScreen);
        Listener.getGameJoinPoint().registerHandler(InvTasks::onGameJoin);
        Listener.getPostOpenHandledScreen().registerHandler(InvTasks::onOpenScreenCreate);
        Listener.getPacketPostHandlePoint()
                .getChannel(ClientboundContainerSetSlotPacket.class)
                .registerHandler(InvTasks::onInventoryOld);
        Listener.getPacketPostHandlePoint()
                .getChannel(ClientboundContainerSetContentPacket.class)
                .registerHandler(InvTasks::onInventoryOld2);
        moduleManager.registerFactories(InvTasks::initModules);
        HackModules.registerModuleGroup(moduleManager);
        clickExecutor = new LimitedSpeedExecutor(invExtra.inventoryClickLimit);
        unlimitedClickExecutor = new LimitedSpeedExecutor(new DoubleRef((double) Integer.MAX_VALUE / 2));
    }
}
