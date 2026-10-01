package me.matl114.hacks.modules.interact;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.BlockBreak;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.ac.DisablerManager;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.mine.PacketMine;
import me.matl114.hacks.modules.move.PlayerInputManager;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.hacks.utils.tasks.StateExecutor;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.entity.PlayerInputUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public class SelfTrap extends BaseModule {
    public static SelfTrap INSTANCE;

    public SelfTrap() {
        super("SelfTrap");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final ModulePath selfTrap = makePath(Configs.INTERACT_CONFIG, "place-utils.self-trap");

    public final FlagRef enable = flagBuilder(selfTrap.addEnable()).build();

    public final KeyBindRef hotkey = moduleEntry(selfTrap.addHotkey(), new MultiKeyBind(), selfTrap.addEnable())
            .build();

    public final FlagRef offhand = flagBuilder(selfTrap.add("offhand")).build();

    public final IntRef delay = builder(selfTrap.add("delay"), IntRef.TYPE)
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final IntRef multiply = builder(selfTrap.add("multiply"), IntRef.TYPE)
            .defaultValue(1)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final EnumRef<LegalInteractMode> mode = builder(selfTrap.add("mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.NONE)
            .build();

    public final FlagRef airplace = flagBuilder(selfTrap.add("air-place")).build();

    public final FlagRef placeLower = flagBuilder(selfTrap.add("lower")).build();

    public final FlagRef onlyGround = flagBuilder(selfTrap.add("only-ground")).build();

    public final FlagRef autoSneak = flagBuilder(selfTrap.add("auto-sneak")).build();

    public final FlagRef useWhiteList =
            flagBuilder(selfTrap.add("use-white-list")).build();

    public final NBTRef<EntrySet<Item>> whiteList = builder(selfTrap.add("white-list"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ITEM, List.of(Items.OBSIDIAN)))
            .build();

    public final FlagRef onlyBlastResistance = builder(selfTrap.add("only-blast-resistance"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef eatingAbort = builder(selfTrap.add("using-item-abort"), Boolean.class)
            .defaultValue(false)
            .build();

    public final EnumRef<GhostHandMode> ghostHand = builder(selfTrap.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef swingHand = builder(selfTrap.add("swing-hand"), Boolean.class)
            .defaultValue(true)
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onInput);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
        registerListener(PacketMine.getPacketMineAction().getChannel(BlockBreak.Stage.PRE), this::onPrePacketMine);
    }

    int delayTicks;
    boolean needSneak;
    StateExecutor needPlaceState = new StateExecutor();

    public void onPrePacketMine(Event<BlockBreak> eventPre) {
        if (enable.get()
                && !eventPre.isCancelled()
                && eventPre.canCancel()
                && eventPre.context().stage() == BlockBreak.Stage.PRE) {
            BlockPos pos = eventPre.context().blockPos();
            if (getTargetingPos().contains(pos)) {
                eventPre.cancel();
            }
        }
    }

    public void onInput(Event<Void> inputEvent) {
        if (checkNull() || !enable.get()) {
            return;
        }

        boolean wasSneaking = mc.player.isShiftKeyDown();
        if (++delayTicks >= delay.get()) {
            if (!eatingAbort.get() || !mc.player.isUsingItem()) {
                if (checkSelfTrap()) {
                    delayTicks = 0;
                    needPlaceState.state(true);
                } else {
                    needPlaceState.state(false);
                }
            } else {
                needPlaceState.state(false);
            }
        }

        if (needSneak) {
            needSneak = false;
            if (autoSneak.get()) {
                if (ViaFabricPlusHooks.isSupportInstaSneak()) {
                    if (mc.player.isShiftKeyDown() != wasSneaking) {
                        PlayerInputUtils.of(mc.player)
                                .sneak(wasSneaking)
                                .sendPlayerSneakUpdatePacket()
                                .applyInput(mc.player);
                    }
                } else {
                    PlayerInputManager.INSTANCE.addSneakModifier(0, true, Math.max(delay.get() - 1, 0), 1);
                }
            }
        }
    }

    public Set<BlockPos> getTargetingPos() {
        AABB playerBox = mc.player.getBoundingBox();
        int feetY = (int) Math.floor(playerBox.minY);
        Set<BlockPos> occupiedFeet = new LinkedHashSet<>();

        int minX = (int) Math.floor(playerBox.minX);
        int maxX = (int) Math.ceil(playerBox.maxX) - 1;
        int minZ = (int) Math.floor(playerBox.minZ);
        int maxZ = (int) Math.ceil(playerBox.maxZ) - 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                occupiedFeet.add(new BlockPos(x, feetY, z));
            }
        }

        Set<BlockPos> result = new LinkedHashSet<>();
        if (placeLower.get()) {
            if (!onlyGround.get()
                    || !CollisionUtil.isBoxCollided(
                            mc.level,
                            mc.player,
                            playerBox.setMinY(playerBox.minY - 1.5).setMaxY(playerBox.minY))) {
                for (BlockPos occupied : occupiedFeet) {
                    result.add(occupied.below());
                }
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (BlockPos occupied : occupiedFeet) {
                BlockPos candidate = occupied.relative(direction);
                if (!occupiedFeet.contains(candidate)) {
                    result.add(candidate);
                }
                BlockPos candidateDown = candidate.below();
                if (!occupiedFeet.contains(candidateDown)) {
                    result.add(candidateDown);
                }
            }
        }
        return result;
    }

    public boolean checkSelfTrap() {
        if (onlyGround.get() && !mc.player.onGround() && !CollisionUtil.isEntitySupported(mc.player, 1.5D)) {
            return false;
        }

        boolean legal = mode.get().isLegal();
        int maxPlace =
                DisablerManager.INSTANCE.isMultiRotPlaceCheckDisabled(mode.get().canMultiRotPlace())
                        ? multiply.get()
                        : 1;
        int placeCount = 0;
        Runnable inventoryCallback = null;
        boolean offhandOk = offhand.get();

        for (BlockPos target : getTargetingPos()) {
            BlockState state = mc.level.getBlockState(target);
            if (!state.isAir() && !state.canBeReplaced()) {
                continue;
            }

            var hitResult = InteractionTasks.getPlaceSupportingResult(target, airplace.get(), !legal);
            if (hitResult == null) {
                continue;
            }

            if (hitResult.flag()) {
                if (!needSneak
                        && autoSneak.get()
                        && ViaFabricPlusHooks.isSupportInstaSneak()
                        && !mc.player.isShiftKeyDown()) {
                    PlayerInputUtils.of(mc.player)
                            .sneak(true)
                            .sendPlayerSneakUpdatePacket()
                            .applyInput(mc.player);
                }
                needSneak = true;
            }

            if (!InteractUtils.canInteractAndPlace(mc.player, hitResult)) {
                continue;
            }

            if (placeCount == 0) {
                IndexEntry<ItemStack> supply = supplyBlocks();
                if (supply == null) {
                    break;
                }
                maxPlace = Math.min(maxPlace, supply.val().getCount());
                offhandOk |= supply.index() == 40;
                inventoryCallback = InvExtra.INSTANCE.swapItemToHand(supply.index(), offhandOk, ghostHand.get());
                if (inventoryCallback == null) {
                    break;
                }
            }

            InteractionTasks.handlePlaceMode(
                    mode.get(), hitResult.val(), offhandOk ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, swingHand.get());
            placeCount++;
            if (placeCount >= maxPlace) {
                break;
            }
        }

        if (inventoryCallback != null) {
            inventoryCallback.run();
        }
        return placeCount > 0;
    }

    public IndexEntry<ItemStack> supplyBlocks() {
        return InventoryUtils.findBestPlayerItem(
                item -> {
                    if (!(item.getItem() instanceof BlockItem blockItem)) {
                        return null;
                    }
                    if (useWhiteList.get() && !whiteList.get().test(blockItem)) {
                        return null;
                    }
                    if (onlyBlastResistance.get() && blockItem.getBlock().getExplosionResistance() < 600) {
                        return null;
                    }
                    return (double) blockItem.getBlock().getExplosionResistance()
                            + (blockItem == Items.OBSIDIAN ? 1E8 : 0)
                            + (blockItem.getBlock() instanceof BaseEntityBlock ? -1E8 : 0);
                },
                ghostHand.get().getSearchSize(offhand.get()),
                true,
                false);
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        mode.set(LegalInteractMode.getFromPreset(event.context.getValue()));
        airplace.set(!event.context.getValue().hasAC());
    }
}
