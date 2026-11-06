package me.matl114.hacks.modules.move;

import java.util.*;
import me.matl114.accessors.access.ClientPlayerAccess;
import me.matl114.accessors.access.PlayerInteractEntityC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.impl.MetadataUpdate;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.events.impl.SlowMovement;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.mine.FakeBlockManager;
import me.matl114.hacks.modules.task.MovementManager;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import me.matl114.hacks.utils.enums.BypassMode;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.entity.PlayerInputUtils;
import me.matl114.versioned.api.VDataFlag;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import me.matl114.hacks.utils.EntityUtils;

public class NoSlowDown extends BaseModule implements LegalMovementManager.MovementModifier {
    public final ModulePath moveSpeed = makePath(Configs.MOV_CONFIG, "move-speed");
    public final ModulePath noSlowdown = moveSpeed.add("no-slowdown");
    public final ModulePath fakeSneakStatusPath = moveSpeed.add("fake-sneak-status");

    public static NoSlowDown INSTANCE;

    public NoSlowDown() {
        super("NoSlowDown");
        // 哎我操GrimAC别修了，真没辙了，再修我还怎么打啊。。。
        INSTANCE = this;
        MovementManager.registerMovementPipeline(this);
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
        registerListener(Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.PLAYER), this::onServerSyncSneak);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundInteractPacket.class), this::onInteractSend);
        registerListener(Listener.getPlayerSlowedByBlockPoint(), this::onWeb);
        registerListener(Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.PLAYER), this::onEntityDataUpdate);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundEntityEventPacket.class), this::onConsume);
        registerListener(Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class), this::onSendStartUse);
        registerListener(Listener.getPreGameTick(), this::onPreGameTick);
    }

    public final FlagRef sneak = flagBuilder(noSlowdown.add("when-sneak")).build();

    public final FlagRef useItem = flagBuilder(noSlowdown.add("when-use-item")).build();

    public final FlagRef blockSlow =
            flagBuilder(noSlowdown.add("when-with-block")).build();

    public final FlagRef blockFrac =
            flagBuilder(noSlowdown.add("when-on-block")).build();

    public final FlagRef blockIn = flagBuilder(noSlowdown.add("when-in-block")).build();

    public final FlagRef blockSpecial =
            flagBuilder(noSlowdown.add("when-special-block")).build();

    public final EnumRef<UseBypassMode> useItemBypass = builder(noSlowdown.add("use-item-bypass"), UseBypassMode.class)
            .defaultValue(UseBypassMode.NO_BYPASS)
            .build();

    public final IntRef swapDelay = builder(noSlowdown.add("use-item-swap-item-delay"), Integer.class)
            .show(() -> useItemBypass.get().isIn(UseBypassMode.BYPASS_GRIM_LAZY, UseBypassMode.BYPASS_GRIM_LAZY_V3))
            .defaultValue(1)
            .build();

    public final FlagRef noSprint = flagBuilder(noSlowdown.add("use-item-swap-no-sprint"))
            .show(() -> useItemBypass.get().isIn(UseBypassMode.BYPASS_GRIM_LAZY_V3))
            .build();

    public final EnumRef<NoWebMode> blockInBypass = builder(noSlowdown.add("block-in-bypass"), NoWebMode.class)
            .defaultValue(NoWebMode.NO_BYPASS)
            .build();

    public final FlagRef blockInKeepYVelocity = flagBuilder(noSlowdown.add("block-in-keep-y"))
            .show(() -> blockInBypass.get().isIn(NoWebMode.GRIM_SPEED))
            .build();

    public final FlagRef blockInMineWhenJump = flagBuilder(noSlowdown.add("block-in-mine-when-jump"))
            .show(() -> blockInBypass.get().isIn(NoWebMode.GRIM_SPEED))
            .build();

    public final EnumRef<BypassMode> fakeSneakBypass = builder(
                    noSlowdown.add("fake-sneak-mode"), BypassMode.class)
            .defaultValue(BypassMode.NO_BYPASS)
            .build();

    public final ModulePath safeSneak = moveSpeed.add("safe-sneak");
    public final FlagRef enableFakeSneak = flagBuilder(safeSneak.addEnable()).build();

    public final KeyBindRef keyBindRef = moduleEntry(safeSneak.addHotkey(), new MultiKeyBind(), safeSneak.addEnable())
            .build();

    public final FlagRef onlySneakPress =
            flagBuilder(safeSneak.add("only-sneak-press")).build();

    public final KeyBindRef fakeStatus = hotkey(fakeSneakStatusPath.add("hotkey"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::onSneakStatus))
            .build();

    public final EnumRef<PacketSneakMode> fakeStatusBypass = builder(
                    fakeSneakStatusPath.add("mode"), PacketSneakMode.class)
            .defaultValue(PacketSneakMode.BAD_PACKET)
            .build();

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        if (event.context().getValue().isOptionPreset()) return;
        ModulePreset preset = event.context().getValue();
        switch (preset) {
            case HACKING -> {
                sneak.set(true);
                blockSlow.set(true);
                blockFrac.set(true);
                blockSpecial.set(true);
            }
            default -> {
                sneak.set(false);
                blockSlow.set(false);
                blockFrac.set(false);
                blockIn.set(false);
                blockSpecial.set(false);
            }
        }
        switch (preset) {
            case HACKING -> {
                useItem.set(true);
                useItemBypass.set(UseBypassMode.NO_BYPASS);
            }
            case AC_GRIM, AC_GRIM_LEGACY -> {
                useItem.set(true);
                useItemBypass.set(UseBypassMode.BYPASS_GRIM_50);
            }
            default -> {
                useItem.set(false);
            }
        }
        switch (preset) {
            case HACKING, VANILLA -> {
                blockIn.set(true);
                blockInBypass.set(NoWebMode.NO_BYPASS);
            }
            case AC_GRIM, AC_GRIM_LEGACY, AC_VULCAN, AC_MATRIX, AC_COMMON -> {
                blockIn.set(true);
                blockInBypass.set(NoWebMode.GRIM_FAKE_MINE);
            }
            default -> {
                blockIn.set(false);
            }
        }
    }

    public void onWeb(Event<SlowMovement> slowMovementEvent) {
        if (blockIn.get()) {
            Vec3 currentMovementSpeed = mc.player.getDeltaMovement();
            SlowMovement slowMovement = slowMovementEvent.context;
            Vec3 stuckSimulation = currentMovementSpeed.multiply(slowMovement.movementModifier());
            double delta = stuckSimulation.subtract(currentMovementSpeed).horizontalDistanceSqr();
            if (delta > 0.0625) {
                return;
            }
            BlockPos pos = slowMovement.pos();
            switch (blockInBypass.get()) {
                case GRIM_SPEED -> {
                    if (slowMovement.state().is(Blocks.COBWEB) && blockInKeepYVelocity.get()) {
                        slowMovement.movementModifier(
                                slowMovement.movementModifier().with(Direction.Axis.Y, 1.0F));
                    }
                    // todo: why
                    var input = PlayerInputUtils.of(mc.player);
                    if (blockInMineWhenJump.get()
                            && !mc.player.isFallFlying()
                            && (mc.player.getDeltaMovement().y >= 0 || mc.player.onGround())
                            && input.jump()
                            && slowMovement.state().is(Blocks.COBWEB)) {
                        FakeBlockManager.INSTANCE.addFakeCompensateState(pos);
                        slowMovementEvent.cancel();
                        return;
                    }
                    if (mc.player.isFallFlying()) {
                        return;
                    }
                    if (input.hasMovement()
                    // PlayerInputUtils.of(mc.player).hasMovement()
                    ) {
                        //                        Vec3d magicVec = mc.player.getVelocity();
                        mc.player.setDeltaMovement(EntityUtils.withStrafe(mc.player.getDeltaMovement(), 0.64));
                        //                        Vec3d magicVec2 = mc.player.getVelocity();
                        // Debug.chat("Magic", magicVec.length(), magicVec2.length());
                    }
                    return;
                }
                case GRIM_FAKE_MINE -> {
                    if (slowMovement.state().is(Blocks.COBWEB)) {
                        var input = PlayerInputUtils.of(mc.player);
                        if (mc.player.isFallFlying() || input.hasWASDMovement() || input.jump()) {
                            FakeBlockManager.INSTANCE.addFakeCompensateState(pos.immutable());
                            slowMovementEvent.cancel();
                            return;
                        }
                    }
                }
                case NO_BYPASS -> {
                    slowMovementEvent.cancel();
                    return;
                }
            }
        }
        return;
    }

    public void resetPlayer() {
        sneakStatus = false;
        cachedPos = null;
        supportingPos = null;
    }

    boolean sneakStatus = false;

    public void onSneakStatus() {
        if (mc.player == null) return;
        if (sneakStatus) {
            sneakStatus = false;
            ClientPlayerAccess.of(mc.player).resyncSneak();
            var lastInput = PlayerInputUtils.of(mc.player);
            var clone = lastInput.clone();
            clone.sneak(true).sendPlayerSneakUpdatePacket();
            clone.sneak(false).sendPlayerSneakUpdatePacket();
            clone.applyInput(mc.player);
            // clone.sneak(lastInput.sneak()).sendPlayerSneakUpdatePacket();
            Debug.chat("[NoSlow] 取消当前伪造潜行状态");
        } else {
            PacketSneakMode mode = fakeStatusBypass.get();
            if (mc.player.isShiftKeyDown()) {
                var re = PlayerInputUtils.of(mc.player).sneak(false);
                re.sendPlayerSneakUpdatePacket();
                re.applyInput(mc.player);
            }
            mc.options.keyShift.setDown(false);
            switch (mode) {
                case GRIM_FALLFLYING -> {
                    PlayerInputUtils.Input input = PlayerInputUtils.of(mc.player);
                    // to trigger plugin events
                    input.sneak(true).sendPlayerSneakUpdatePacket();
                    input.sneak(false).sendPlayerSneakUpdatePacket();
                    if (!mc.player.onGround() && ViaFabricPlusHooks.isSupportEndTick()) {
                        input.jump(true).sendPlayerInputPacket();
                        input.applyInput(mc.player);
                    }
                    mc.getConnection()
                            .send(new ServerboundPlayerCommandPacket(
                                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    sneakStatus = true;
                    Debug.chat("[NoSlow] 成功伪造状态");
                }
                case BAD_PACKET, INTERACT -> {
                    Entity entity;
                    boolean canBypass;
                    if (mc.hitResult instanceof EntityHitResult entityHitResult) {
                        entity = entityHitResult.getEntity();
                        canBypass = true;
                    } else {

                        List<Entity> entities = new ArrayList<>();
                        for (var et : mc.level.entitiesForRendering()) {
                            if (et != mc.player) {
                                entities.add(et);
                            }
                        }
                        entities.sort(Comparator.comparingDouble(s -> s.distanceToSqr(mc.player)));
                        if (!entities.isEmpty()) {
                            entity = entities.get(0);
                            canBypass = false;
                        } else {
                            entity = null;
                            canBypass = false;
                        }
                    }
                    if (canBypass || mode == PacketSneakMode.BAD_PACKET) {
                        int id = entity == null ? mc.player.getId() - 1 : entity.getId();
                        PlayerInputUtils.Input input = PlayerInputUtils.of(mc.player);
                        // to trigger plugin events
                        input.sneak(true).sendPlayerSneakUpdatePacket();
                        input.sneak(false).sendPlayerSneakUpdatePacket();
                        mc.gameMode.startPrediction(mc.level, (seq) -> {
                            return new ServerboundInteractPacket(
                                    id, InteractionHand.MAIN_HAND, mc.player.position(), true);
                        });
                        sneakStatus = true;
                        Debug.chat("[NoSlow] 成功伪造状态");
                    } else {
                        // out of interact range
                        if (entity == null
                                || entity.getBoundingBox().distanceToSqr(mc.player.getEyePosition())
                                        > MathUtils.s2(mc.player.entityInteractionRange() + 0.5)) {
                            Debug.chat("[NoSlow] 当前模式下需要一个实体以交互");
                            return;
                        }
                        Entity target = Objects.requireNonNull(entity);
                        PlayerInputUtils.Input input = PlayerInputUtils.of(mc.player);
                        RotationManager.INSTANCE.request(RotationManager.ofEntity(() -> target)
                                .postTick(() -> Tasks.scheduleDelayedPre(
                                        () -> {
                                            // to trigger plugin events
                                            input.sneak(true).sendPlayerSneakUpdatePacket();
                                            input.sneak(false).sendPlayerSneakUpdatePacket();
                                            NetworkUtils.sendSwing(mc.getConnection());
                                            mc.gameMode.startPrediction(
                                                    mc.level,
                                                    (seq) -> new ServerboundInteractPacket(
                                                            target.getId(),
                                                            InteractionHand.MAIN_HAND,
                                                            mc.player.position(),
                                                            true));
                                            NetworkUtils.sendSwing(mc.getConnection());
                                            Debug.chat("[NoSlow] 成功伪造状态");
                                            sneakStatus = true;
                                        },
                                        0)));
                    }
                }
            }
        }
    }

    public boolean shouldNoSlowSneak() {
        return sneak.get() && (!fakeSneakBypass.get().hasAc());
    }

    public boolean shouldFakeSneakStatus() {
        return (sneakStatus || isFakeSneakActive() || (isFakeSneakEnabled())) && mc.player.onGround();
    }

    private boolean isFakeSneakEnabled() {
        return enableFakeSneak.get() && checkSneakSpeed();
    }

    private boolean isFakeSneakActive() {
        if (!isFakeSneakEnabled()) {
            return false;
        }
        if (onlySneakPress.get()) {
            return mc.options.keyShift.isDown();
        }
        PlayerInputUtils.Input input = PlayerInputUtils.of(mc.options);
        return input.hasWASDMovement() && !input.sneak() && !input.jump();
    }

    private boolean checkSneakSpeed() {
        return mc.player.getAttributeValue(Attributes.SNEAKING_SPEED) < 0.9F;
    }

    private float getActiveItemSpeedMultiplier() {
        ItemStack stack = mc.player.getUseItem();
        //        if(VItem.getInstance().isSpear(stack))return 1.0F;
        return ((UseEffects) stack.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT)).speedMultiplier();
    }

    public void onInteractSend(Event<ServerboundInteractPacket> interactPacket) {
        if (sneakStatus) {
            ServerboundInteractPacket packet = interactPacket.context();
            if (!packet.usingSecondaryAction()) {
                PlayerInteractEntityC2SPacketAccess.of(packet).setPlayerSneaking(true);
            }
            //            else{
            //                if(packet.isPlayerSneaking()){
            //                    interactPacket.context(new PlayerInteractEntityC2SPacket(packet.entityId, false,
            // packet.type));
            //                }
            //            }
        }
    }

    public void onServerSyncSneak(Event<MetadataUpdate> event) {
        if (event.isCancelled()) {
            return;
        }
        if (sneakStatus && event.context.entity() instanceof LocalPlayer player && player == mc.player) {
            var val = event.context().metadata();
            if (val.id() == VDataFlag.ID_FLAGS) {
                byte data = (byte) val.value();
                boolean sneakFlag = (data & (1 << VDataFlag.SNEAKING_FLAG_INDEX)) != 0;
                if (!sneakFlag) {
                    sneakStatus = false;
                    Debug.chat("[NoSlow] 伪造的潜行状态被重置了");
                }
            }
        }
    }

    //
    //    int postSlot2 = -1;
    //    int postHotbar2 = -1;
    Runnable postCallBack = null;

    public void preSwap(boolean v3) {
        // ClientPlayerAccess.of(mc.player).resyncMovementPacket();
        var re = InventoryUtils.findPlayerHotBarItem(ItemStack::isEmpty, true, true);
        int selectedIdx;
        // todo: optimize these shit
        if (mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            selectedIdx = InventoryUtils.getSelectedSlot();
        } else {
            selectedIdx = 40;
        }
        int selectedEmpty;
        if (re != null) {
            selectedEmpty = re.index();
        } else {
            if (mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                selectedEmpty = 40;
            } else {
                selectedEmpty = InventoryUtils.getSelectedSlot();
            }
        }
        ItemStack stackEmpty = mc.player.getInventory().getItem(selectedEmpty);
        var handler = ClientPlayerAccess.of(mc.player).getServerScreenHandler();
        // may use MultiActionsC to resync inventory, wierd
        // MovTasks.getMovExtra().sendInputPacketsForInventoryAction();
        // save current server sprinting status
        // use MultiActionsC to create ghost inventory and bypass useItem NoSlow
        // pre, send sprint
        if (v3) {
            PlayerStateManager.INSTANCE.sendSprintStatus(true, false);
        } else {
            PlayerStateManager.INSTANCE.sendSprintStatus(mc.player.isSprinting(), false);
        }

        postCallBack = null;
        // try find a empty slot to switch
        if (!stackEmpty.isEmpty()) {
            int postHotbar2 = selectedIdx;
            ItemStack stackHand = mc.player.getInventory().getItem(selectedIdx);
            var slot = InventoryUtils.findBestScreenSlot(
                    handler.slots,
                    (sl) -> {
                        if (sl.getItem().isEmpty() && sl.mayPlace(stackHand)) {
                            // prior inv slot
                            return sl.container instanceof Inventory ? 1.0D : null;
                        } else return null;
                    },
                    true); //  mc.player.currentScreenHandler.getSlotIndex(mc.player.getInventory(), selected);
            if (slot != null) {
                // cancel sprint at this moment
                mc.gameMode.handleContainerInput(
                        handler.containerId, slot.index(), selectedIdx, ContainerInput.SWAP, mc.player);
                // any flying packet
                int postSlot2 = slot.index();
                postCallBack = () -> {
                    // may use MultiActionsC to resync inventory, wierd
                    // MovTasks.getMovExtra().sendInputPacketsForInventoryAction();
                    mc.gameMode.handleContainerInput(
                            handler.containerId, postSlot2, postHotbar2, ContainerInput.SWAP, mc.player);
                };
            } else {
                if (mc.player.containerMenu.getCarried().isEmpty()) {
                    //                    var idx =
                    // mc.player.currentScreenHandler.getSlotIndex(mc.player.getInventory(), selectedIdx);
                    int hotbarShot = selectedIdx == 8 ? 7 : 8;

                    var hbSlot2 = mc.player.containerMenu.findSlot(mc.player.getInventory(), hotbarShot);
                    // 何意味...
                    if (hbSlot2.isPresent()) {
                        // NO FUCKING USE
                        //                        mc.gameMode.handleContainerInput(handler.containerId, idx.getAsInt(),
                        // 0,
                        // ContainerInput.PICKUP, mc.player);
                        //                        postCallBack =
                        //                            ()->{
                        //                            mc.gameMode.handleContainerInput(handler.containerId,
                        // idx.getAsInt(), 0,
                        // ContainerInput.PICKUP, mc.player);
                        //                        };
                        mc.gameMode.handleContainerInput(
                                handler.containerId, hbSlot2.getAsInt(), 0, ContainerInput.PICKUP, mc.player);
                        mc.gameMode.handleContainerInput(
                                handler.containerId, hbSlot2.getAsInt(), selectedIdx, ContainerInput.SWAP, mc.player);
                        postCallBack = () -> {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId,
                                    hbSlot2.getAsInt(),
                                    selectedIdx,
                                    ContainerInput.SWAP,
                                    mc.player);
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, hbSlot2.getAsInt(), 0, ContainerInput.PICKUP, mc.player);
                        };
                    }
                } else {
                    // todo: swap other item to
                    int hotbarShot = selectedIdx == 8 ? 7 : 8;
                    var slotEmpty = InventoryUtils.findScreenSlot(
                            handler.slots,
                            (sl) -> {
                                if (sl.getItem().isEmpty()
                                        && sl.mayPlace(stackHand)
                                        && !(sl.container instanceof Inventory)) {
                                    // prior inv slot
                                    return true;
                                } else return false;
                            },
                            true);
                    var idx = mc.player.containerMenu.findSlot(mc.player.getInventory(), hotbarShot);
                    if (slotEmpty != null && idx.isPresent()) {
                        mc.gameMode.handleContainerInput(
                                handler.containerId, slotEmpty.index(), hotbarShot, ContainerInput.SWAP, mc.player);
                        mc.gameMode.handleContainerInput(
                                handler.containerId, idx.getAsInt(), selectedIdx, ContainerInput.SWAP, mc.player);
                        postCallBack = () -> {
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, idx.getAsInt(), selectedIdx, ContainerInput.SWAP, mc.player);
                            mc.gameMode.handleContainerInput(
                                    handler.containerId, slotEmpty.index(), hotbarShot, ContainerInput.SWAP, mc.player);
                        };
                    }
                }
            }
        } else {
            int postHotbar2 = selectedEmpty;
            var result = handler.findSlot(mc.player.getInventory(), selectedIdx);
            if (result.isPresent()) {
                mc.gameMode.handleContainerInput(
                        mc.player.containerMenu.containerId,
                        result.getAsInt(),
                        postHotbar2,
                        ContainerInput.SWAP,
                        mc.player);
                // any flying packet
                ClientPlayerAccess.of(mc.player).resyncPos();
                postCallBack = () -> {
                    mc.gameMode.handleContainerInput(
                            mc.player.containerMenu.containerId,
                            result.getAsInt(),
                            postHotbar2,
                            ContainerInput.SWAP,
                            mc.player);
                };
            }
        }
        if (v3) {
            // mc.player.setSprinting();
            PlayerStateManager.INSTANCE.sendSprintStatus(mc.player.isSprinting(), false);
        }
    }

    public void postSwap(boolean v3) {
        // restore sprint
        // may use MultiActionsC to resync inventory, wierd
        if (postCallBack != null) {
            if (v3) {
                PlayerStateManager.INSTANCE.sendSprintStatus(true, false);
                ClientPlayerAccess.of(mc.player).setLastSprintFlag(true);
            }
            postCallBack.run();
            postCallBack = null;
        }
    }

    public void setPreAttackUseTick() {
        preAttackUseTick = true;
    }

    boolean preAttackUseTick;
    int lastNoSlowUseTick = 0;

    public boolean noSlowUseItemGrim() {
        if (mc.player.isUsingItem() && useItem.get()) {

            return switch (useItemBypass.get()) {
                case NO_BYPASS, BYPASS_GRIM_50 -> false;
                case BYPASS_GRIM_LAZY -> {
                    if (preAttackUseTick) {
                        yield true;
                    }
                    boolean isNotFallFlying;
                    if (mc.player.isFallFlying()) {
                        if (mc.player.isInWater()) {
                            isNotFallFlying = true;
                        } else {
                            isNotFallFlying = false;
                        }
                    } else {
                        isNotFallFlying = true;
                    }
                    if (isNotFallFlying
                            && !mc.player.isPassenger()
                            && PlayerInputUtils.of(mc.player).hasWASDMovement()
                            && getActiveItemSpeedMultiplier() < 0.99F) {
                        if (lastNoSlowUseTick >= Tasks.getTick() - swapDelay.get()) {
                            yield false;
                        } else {
                            lastNoSlowUseTick = Tasks.getTick();
                            yield true;
                        }
                    } else {
                        yield false;
                    }
                }
                case BYPASS_GRIM_LAZY_V3 -> {
                    if (!mc.player.isPassenger()
                            && PlayerInputUtils.of(mc.player).hasWASDMovement()
                            && getActiveItemSpeedMultiplier() < 0.99F) {
                        if (grimSlowedByItemFlag) {
                            grimSlowedByItemFlag = false;
                            yield true;
                        }
                        yield false;
                    } else {
                        yield false;
                    }
                }
            };
        }
        lastNoSlowUseTick = 0;
        return false;
    }

    boolean grimSlowedByItemFlag = false;

    public void onEntityDataUpdate(Event<MetadataUpdate> eventEntityDataUpdate) {
        if (useItem.get() && eventEntityDataUpdate.context.entity() == mc.player) {
            if (eventEntityDataUpdate.context.metadata().id() == VDataFlag.ID_LIVING_FLAGS
                    && eventEntityDataUpdate.context.metadata().value() instanceof Number number) {
                byte flagByte = number.byteValue();
                boolean bl = (flagByte & (1 << VDataFlag.USING_ITEM_FLAG_INDEX)) > 0;
                if (bl) {
                    // only spread true flag
                    // may receive false flag that transaction < currentUseItemTransaction
                    Tasks.scheduleRepeatedPre(
                            () -> {
                                grimSlowedByItemFlag = true;
                                return false;
                            },
                            1,
                            1,
                            2);
                }

                if (!bl) {
                    lastNoSlowUseTick = 0;
                }
            }
        }
    }

    public void onConsume(Event<ClientboundEntityEventPacket> eventStatus) {
        if (checkNull()) return;
        if (useItem.get()
                && eventStatus.context.getEventId() == EntityEvent.USE_ITEM_COMPLETE
                && eventStatus.context.getEntity(mc.level) == mc.player) {
            grimSlowedByItemFlag = false;
            lastNoSlowUseTick = 0;
        }
    }

    public void onSendStartUse() {
        if (useItem.get() && mc.player.isUsingItem()) {
            grimSlowedByItemFlag = true;
            lastNoSlowUseTick = 0;
        }
    }

    public void onSendMovePreNoSlowUse(Event<Packet<?>> event) {
        if (noSlowUseItemGrim()) {
            preSwap(false);
        }
    }

    public void onSendMovePostNoSlowUse(Event<Packet<?>> event) {
        postSwap(false);
    }

    public boolean workNoSlowItemThisTick;
    boolean grimFlagNoSlowOnce = false;

    private void onPreGameTick(Event<LocalPlayer> event) {
        LocalPlayer args = event.context;
        lastPredictWasSneakEdge = false;
        supportingPos = args.getBlockPosBelowThatAffectsMyMovement();
        AABB playerBox = args.getBoundingBox();
        List<AABB> nearbyCollisionBoxes = getNearbyCollisionBoxes(playerBox);
        boolean supportAt05 = hasSupportCollision(playerBox, nearbyCollisionBoxes, 0.05);
        boolean supportAt15 = hasSupportCollision(playerBox, nearbyCollisionBoxes, 0.15);
        boolean shouldSneakFromSupport = isFakeSneakEnabled()
                && args.onGround()
                && (!supportAt05 || (!supportAt15 && isMovingOutwardFromEdge(args)));
        boolean trackSneakEdge = (shouldFakeSneakStatus() || shouldSneakFromSupport) && args.onGround();
        if (trackSneakEdge) {
            cachedPos = supportingPos;
            lastPredictWasSneakEdge = !supportAt05 || isPredictedSneakEdge(args);
        } else {
            cachedPos = null;
        }

        if ((isFakeSneakActive() || shouldSneakFromSupport) && args.onGround()) {
            if (lastPredictWasSneakEdge) {
                PlayerInputManager.INSTANCE.addSneakModifier(0, true, 1);
            } else if (onlySneakPress.get()) {
                PlayerInputManager.INSTANCE.addSneakModifier(0, false, 1);
            }
        }
    }

    @Override
    public void applyPreTickModify(Event<LegalMovementManager> movementManagerEvent) {
        LocalPlayer args = movementManagerEvent.context.playerStatus.entity;
        if (shouldNoSlowSneak()) {
            PlayerInputUtils.of(args).sneak(mc.options.keyShift.isDown()).applyInput(args);
        }
        workNoSlowItemThisTick = false;
        if (useItem.get() && mc.player.isUsingItem()) {
            if (useItemBypass.get() == UseBypassMode.BYPASS_GRIM_50) {
                PlayerInputUtils.Input input = PlayerInputUtils.of(args);
                if (input.hasWASDMovement()) {
                    if (mc.player.isFallFlying() && !mc.player.isInWater()) {
                        grimFlagNoSlowOnce = true;
                    } else {
                        if (grimFlagNoSlowOnce) {
                            grimFlagNoSlowOnce = false;
                            workNoSlowItemThisTick = false;
                        } else {
                            grimFlagNoSlowOnce = true;
                            workNoSlowItemThisTick = true;
                        }
                    }
                } else {
                    if (grimFlagNoSlowOnce) {
                        ClientPlayerAccess.of(mc.player).resyncPos();
                        grimFlagNoSlowOnce = false;
                    }
                }
            } else {
                workNoSlowItemThisTick = true;
            }
        } else {
            grimFlagNoSlowOnce = false;
        }
    }

    BlockPos cachedPos;
    BlockPos supportingPos;

    private List<AABB> getNearbyCollisionBoxes(AABB playerBox) {
        AABB searchBox = playerBox.inflate(0.05, 0.5, 0.05);
        List<AABB> collisionBoxes = new ArrayList<>();
        for (BlockPos pos : CollisionUtil.getIntersectingBlockPositions(mc.level, searchBox, false)) {
            var shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
            if (shape.isEmpty()) continue;
            for (AABB collisionBox : shape.toAabbs()) {
                collisionBoxes.add(collisionBox.move(pos));
            }
        }
        return collisionBoxes;
    }

    private boolean hasSupportCollision(AABB playerBox, List<AABB> collisionBoxes, double shrink) {
        AABB supportCheckBox = playerBox.move(0, -0.5, 0).inflate(-shrink);
        return collisionBoxes.stream().anyMatch(supportCheckBox::intersects);
    }

    private boolean isMovingOutwardFromEdge(LocalPlayer args) {
        PlayerInputUtils.Input input = PlayerInputUtils.of(args);
        if (!input.hasWASDMovement()) return false;
        Vec3 inputDirection = getInputDirection(args, input);
        if (inputDirection.equals(Vec3.ZERO)) return false;

        AABB playerBox = args.getBoundingBox();
        AABB predictedPlayerBox = playerBox.move(inputDirection.x * 0.1, 0, inputDirection.z * 0.1);
        return !hasSupportCollision(predictedPlayerBox, getNearbyCollisionBoxes(predictedPlayerBox), 0.05);
    }

    private Vec3 getInputDirection(LocalPlayer args, PlayerInputUtils.Input input) {
        double forward = input.forwardSpeed();
        double sideways = input.sidewaysSpeed();
        double inputLength = Math.sqrt(forward * forward + sideways * sideways);
        if (inputLength == 0) {
            return Vec3.ZERO;
        }

        double yaw = Math.toRadians(args.getYRot());
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        return new Vec3(
                (-forward * sinYaw + sideways * cosYaw) / inputLength,
                0,
                (forward * cosYaw + sideways * sinYaw) / inputLength);
    }

    private boolean isPredictedSneakEdge(LocalPlayer args) {
        Vec3 position = args.position();
        Vec3 velocity = args.getDeltaMovement();
        Vec3 inputDirection = getInputDirection(args, PlayerInputUtils.of(args));
        double lookAhead = 0.1;
        Vec3 predictedPosition =
                position.add(velocity.x + inputDirection.x * lookAhead, 0, velocity.z + inputDirection.z * lookAhead);
        AABB predictedPlayerBox =
                args.getBoundingBox().move(predictedPosition.x - position.x, 0, predictedPosition.z - position.z);
        return !hasSupportCollision(predictedPlayerBox, getNearbyCollisionBoxes(predictedPlayerBox), 0.066);
    }

    @Override
    public void applyAfterInputTick(Event<LegalMovementManager> movementManagerEvent) {
        LocalPlayer args = movementManagerEvent.context.playerStatus.entity;
        if (useItem.get()
                && useItemBypass.get().isIn(UseBypassMode.BYPASS_GRIM_LAZY_V3)
                && noSprint.get()
                && grimSlowedByItemFlag) {
            if (!mc.player.isPassenger()
                    && PlayerInputUtils.of(args).hasWASDMovement()
                    && getActiveItemSpeedMultiplier() < 0.99F) {
                PlayerInputUtils.of(args).sprint(false).applyInput(args);
            }
        }
    }

    boolean lastPredictWasSneakEdge = false;

    @Override
    public void applyBeforeMovementPacketModify(Event<LegalMovementManager> movementManagerEvent) {
        onSendMovePreNoSlowUse(null);
        lastPredictWasSneakEdge = false;
    }

    @Override
    public boolean postModify(Event<LegalMovementManager> movementManagerEvent, boolean enabledThisTick) {
        onSendMovePostNoSlowUse(null);
        preAttackUseTick = false;
        return true;
    }

    public static enum PacketSneakMode implements ConfigEnum {
        BAD_PACKET,
        INTERACT,
        GRIM_FALLFLYING;

        @Override
        public String getConfigEnumType() {
            return "packet_sneak_bypass_mode";
        }
    }

    public static enum UseBypassMode implements ConfigEnum {
        NO_BYPASS,
        // fixed in 2026.0701 grim commit
        BYPASS_GRIM_LAZY,
        // fixed in 2026.0701 grim commit
        BYPASS_GRIM_LAZY_V3,
        BYPASS_GRIM_50;

        @Override
        public String getConfigEnumType() {
            return "use_item_noslow_bypass";
        }
    }

    public static enum NoWebMode implements ConfigEnum {
        NO_BYPASS,
        GRIM_SPEED,
        GRIM_FAKE_MINE;

        @Override
        public String getConfigEnumType() {
            return "no_web_slow_bypass";
        }
    }
}
