package me.matl114.hacks.modules.combat;

import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;
import lombok.Setter;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.events.impl.UseItem;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hooks.ViaFabricPlusHooks;
import me.matl114.hooks.ViaProtocols;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.ClientUtils;
import me.matl114.utils.NetworkUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import me.matl114.versioned.SupportVersion;
import me.matl114.versioned.api.VDataFlag;
import me.matl114.versioned.api.VItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableInt;

public class SpearEnhance extends BaseModule {
    public static SpearEnhance INSTANCE;
    public final ModulePath spearModule = makePath(Configs.COMBAT_CONFIG, "spear-module");

    public SpearEnhance() {
        super("SpearEnhance");
        INSTANCE = this;
    }

    public final FlagRef spearAutoRestart =
            flagBuilder(spearModule.add("spear-auto-restart")).build();

    public final FlagRef renderKineticPlayers =
            flagBuilder(spearModule.add("render-kinetic-players")).build();

    public final FlagRef replaceSpearModel = builder(spearModule.add("replace-via-spear-model"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef fixOldVersionSpear = builder(spearModule.add("fix-old-version-spear"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef fixOldVersionPiercing = builder(
                    spearModule.add("fix-old-version-spear-piercing"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef fixOldVersionSpearSound = builder(
                    spearModule.add("fix-old-version-spear-sound"), Boolean.class)
            .defaultValue(true)
            .build();

    public final NBTRef<WrapColor> renderColor = builder(
                    spearModule.add("render-kinetic-players-color"), WrapColor.class)
            .defaultValue(new WrapColor((ChatFormatting.YELLOW)))
            .build();

    public final FlagRef spearSpeedReset =
            flagBuilder(spearModule.add("reset-spear-speed-rot-enable")).build();

    public final KeyBindRef spearSpeedRotReset = moduleEntry(
                    spearModule.add("reset-spear-speed-rot-hotkey"),
                    new MultiKeyBind(),
                    spearModule.add("reset-spear-speed-rot-enable"))
            .build();

    public final FlagRef spearSpeedResetAuto =
            flagBuilder(spearModule.add("reset-spear-speed-auto")).build();

    public final FlagRef spearSpeedResetTargetJudge =
            flagBuilder(spearModule.add("reset-spear-speed-only-combat")).build();

    public final FlagRef autoFocusTarget =
            flagBuilder(spearModule.add("reset-spear-auto-focus-target")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreGameTick(), this::onPreTick);
        registerListener(Listener.getPostGameTick(), this::updateKineticPlayerCollector);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
        registerListener(RenderListener.getCustomModelOverride(), this::onReplaceSpearModel);
        registerListener(Listener.getClientPlayerPostSendMovementPoint(), this::onPostTick);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class), this::onUsePiercing);
        registerListener(Listener.getAttackAction(), this::onUsingStab);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundEntityEventPacket.class),
                this::onSpearEntity);
        registerListener(Listener.getPostPlayerUseItem(), this::onSpearUse);
    }

    public static boolean isUsingSpear(Player player) {
        // todo consider viaversion
        if (mc.player == player) {
            return player.isUsingItem() && VItem.getInstance().isSpear(player.getActiveItem());
        }
        return isHoldingSpear(player);
    }

    public static boolean isHoldingSpear(Player player) {
        return player != null
                && (VItem.getInstance().isSpear(player.getMainHandItem())
                        || VItem.getInstance().isSpear(player.getOffhandItem()));
    }

    public static ItemStack getSpear() {
        return mc.player.getUseItem();
    }

    public void onPreTick() {
        if (isUsingSpear(mc.player)) {
            ItemStack stack = getSpear();
            int maxKineticTime = getMaxKineticTime(stack);
            InteractionHand hand = mc.player.getUsedItemHand();
            if (spearAutoRestart.get() && mc.player.getTicksUsingItem() > maxKineticTime) {
                mc.gameMode.releaseUsingItem(mc.player);
                mc.gameMode.useItem(mc.player, hand);
            }
        }
    }

    private final RenderCollector<Entity> kineticPlayerCollector =
            RenderCollectors.createEntityBoxCollector(false, true, false);

    private void updateKineticPlayerCollector() {
        kineticPlayerCollector.clear();
        if (checkNull() || !renderKineticPlayers.get()) return;
        int color = renderColor.get().withAlpha(64);
        for (var player : mc.level.players()) {
            if (player != mc.getCameraEntity() && canSpearKineticAttack(player)) {
                kineticPlayerCollector.submit(player, color);
            }
        }
    }

    public void onRender(Event<Render3D> event) {
        if (renderKineticPlayers.get()) {
            RenderUtils.startDrawVirtual(event.context.stack());
            try {
                kineticPlayerCollector.render3D(event.context.stack(), event.context.partialTicks());
            } finally {
                RenderUtils.stopDrawVirtual(event.context.stack());
            }
        }
    }

    public static boolean canSpearKineticAttack(Player player) {
        if (isUsingSpear(player)) {
            ItemStack stack = getSpear();
            KineticWeapon kineticWeaponComponent = stack.get(DataComponents.KINETIC_WEAPON);
            if (kineticWeaponComponent != null) {
                if (player.getTicksUsingItem()
                        < kineticWeaponComponent.delayTicks() - ((player == mc.player) ? 0 : 4)) {
                    return false;
                }
            } else {
                if (player.getTicksUsingItem() < (8 - ((player == mc.player) ? 0 : 4))) {
                    return false;
                }
            }
            int maxKineticTime = getMaxKineticTime(stack);
            return player.getTicksUsingItem() < maxKineticTime;
        }
        return false;
    }

    public static boolean canSpearKineticAttack() {
        return canSpearKineticAttack(mc.player);
    }

    public static int getMaxKineticTime(ItemStack stack) {
        KineticWeapon kineticWeaponComponent = stack.get(DataComponents.KINETIC_WEAPON);
        if (kineticWeaponComponent != null) {
            if (kineticWeaponComponent.damageConditions().isPresent()) {
                return kineticWeaponComponent.damageConditions().get().maxDurationTicks();
            }
            return 0;
        } else {
            Item item = stack.getItem();
            float lastingSec;
            if (item == Items.DIAMOND_SWORD) {
                lastingSec = 10;
            } else if (item == Items.NETHERITE_SWORD) {
                lastingSec = 8.75f;
            } else if (item == Items.IRON_SWORD) {
                lastingSec = 11.25f;
            } else if (item == Items.COPPER_SWORD) {
                lastingSec = 12.5f;
            } else if (item == Items.STONE_SWORD || item == Items.GOLDEN_SWORD) {
                lastingSec = 13.75f;
            } else if (item == Items.WOODEN_SWORD) {
                lastingSec = 15f;
            } else {
                return 0;
            }
            return (int) lastingSec * 20;
        }
    }

    private final Map<Item, Item> materialSwordToSpearMap = new HashMap<>();

    {
        // 木制
        materialSwordToSpearMap.put(Items.WOODEN_SWORD, Items.WOODEN_SPEAR);
        // 石制
        materialSwordToSpearMap.put(Items.STONE_SWORD, Items.STONE_SPEAR);
        // 铁制
        materialSwordToSpearMap.put(Items.IRON_SWORD, Items.IRON_SPEAR);
        // 金制
        materialSwordToSpearMap.put(Items.GOLDEN_SWORD, Items.GOLDEN_SPEAR);
        // 钻石
        materialSwordToSpearMap.put(Items.DIAMOND_SWORD, Items.DIAMOND_SPEAR);
        // 下界合金
        materialSwordToSpearMap.put(Items.NETHERITE_SWORD, Items.NETHERITE_SPEAR);

        // 铜制（根据模组实际物品名调整）
        materialSwordToSpearMap.put(Items.COPPER_SWORD, Items.COPPER_SPEAR);
        // 如果使用原版铜锭但剑来自其他模组，例如：
        // put(Registry.ITEM.get(new Identifier("some_mod", "copper_sword")),
        //     Registry.ITEM.get(new Identifier("some_mod", "copper_spear")));
    }

    public void onReplaceSpearModel(Event<Identifier> eventIdentifier) {
        if (eventIdentifier.isCancelled() || eventIdentifier.context != null) return;
        if (replaceSpearModel.get()) {
            ItemStack origin = eventIdentifier.getArgs(0);
            if (materialSwordToSpearMap.containsKey(origin.getItem())
                    && VItem.getInstance().isSpear(origin)) {
                Item item = materialSwordToSpearMap.get(origin.getItem());
                if (item != null) {
                    eventIdentifier.context(item.components().get(DataComponents.ITEM_MODEL));
                }
            }
        }
    }

    public ItemStack getOriginalStack(ItemStack stack) {
        Item item = stack.getItem();
        Item trans = materialSwordToSpearMap.get(item);
        return trans == null ? ItemStack.EMPTY : new ItemStack(trans);
    }

    public KineticWeapon getRealComponent(ItemStack stack) {
        Item item = stack.getItem();
        Item trans = materialSwordToSpearMap.get(item);
        if (trans != null && VItem.getInstance().isSpear(stack)) {
            return trans.components().get(DataComponents.KINETIC_WEAPON);
        }
        return stack.get(DataComponents.KINETIC_WEAPON);
    }

    @Setter
    boolean forceSpearReset = false;

    @Setter
    boolean ignoreSpear = false;

    public void onPostTick(Event<LocalPlayer> eventPostTick) {
        if (checkNull()) return;
        if (eventPostTick.context == mc.player
                && (spearSpeedReset.get() || forceSpearReset)
                && ViaFabricPlusHooks.isSupportDupRot()) {
            boolean force = ignoreSpear;
            forceSpearReset = false;
            ignoreSpear = false;
            boolean autoCondition = true;

            if (spearSpeedResetAuto.get() && !force) {
                // 长矛使用时自动关闭啥比玩意免得我忘了
                if (isUsingSpear(mc.player)) {
                    autoCondition = false;
                }
            }
            if (spearSpeedResetTargetJudge.get()) {
                boolean anyMatch = TargetSelector.INSTANCE.getAttackableEntities(25).stream()
                        .anyMatch(s -> s instanceof Player pl && isUsingSpear(pl));
                if (!anyMatch) {
                    autoCondition = false;
                }
            }
            if (autoCondition) {
                Vec3 look = PlayerStateManager.INSTANCE.getLastRotationVector();
                if (autoFocusTarget.get() && isUsingSpear(mc.player)) {
                    Entity targetEntity =
                            TargetSelector.INSTANCE.searchAttackEntity(30, true, pl -> pl instanceof Player);
                    if (targetEntity != null) {
                        look = targetEntity
                                .dimensions
                                .makeBoundingBox(PositionPredict.INSTANCE
                                        .spearPredictArgument
                                        .get()
                                        .predict(targetEntity))
                                .getCenter()
                                .subtract(mc.player.getEyePosition());
                    }
                }
                // reset speed and rotation
                LegacySnapRotManager.INSTANCE.snapAt(look, true);
            }
        }
    }

    public void onUsePiercing(Event<ServerboundPlayerActionPacket> eventPiercing) {
        if (fixOldVersionPiercing.get()
                && ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(21, 9)
                && eventPiercing.context.getAction().ordinal() == 7) {
            if (onPiercing(() -> eventPiercing.context.getSequence())) {
                eventPiercing.cancel();
            }
        }
    }

    public void onUsingStab(Event<HitResult> eventStab) {
        if (fixOldVersionPiercing.get()
                && ViaFabricPlusHooks.getInstance().getCurrentVersion().isLowerOrEqualTo(21, 9)
                && VItem.getInstance().isSpear(mc.player.getItemInHand(InteractionHand.MAIN_HAND))
                && !mc.gameMode.isSpectator()) {
            if (onPiercing(() -> 0)) {
                ClientUtils.swing(mc.player, InteractionHand.MAIN_HAND);
                eventStab.cancel();
            }
        }
    }

    public boolean onPiercing(IntSupplier seq) {
        if (VItem.getInstance().isSpear(mc.player.getItemInHand(InteractionHand.MAIN_HAND))
                && ViaFabricPlusHooks.getInstance().isViaEnabled()) {
            if (SupportVersion.CURRENT.isHigherOrEqualTo(21, 6)) {
                var wrapper = ViaFabricPlusHooks.getInstance().createViaPacket();
                wrapper.writePacketType(ViaProtocols.V1_21_5_TO_1_21_6, GamePacketTypes.SERVERBOUND_PLAYER_ACTION);
                wrapper.write("VAR_INT", 7);
                wrapper.write("LONG", 0L);
                wrapper.write("BYTE", (byte) 0);
                wrapper.write("VAR_INT", seq.getAsInt());
                wrapper.scheduleSendToServer(ViaProtocols.V1_21_6_TO_1_21_7, true);
            } else {
                // todo: need test
                ServerboundPlayerActionPacket actionPacket = new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN);
                ByteBuf buf = NetworkUtils.createBytebuf();
                Listener.getConnectionAccess().getOutboundState().codec().encode(buf, (Packet) actionPacket);
                int id = VarInt.read(buf);
                VarInt.read(buf);
                long pos = buf.readLong();
                short sh = buf.readUnsignedByte();
                int sequence = VarInt.read(buf);
                buf.release();
                buf = NetworkUtils.createBytebuf();
                try {
                    VarInt.write(buf, id);
                    VarInt.write(buf, 7);
                    buf.writeLong(pos);
                    buf.writeByte(sh);
                    VarInt.write(buf, sequence);
                    Listener.getConnectionAccess().sendByteBuf(buf.retain());
                } finally {
                    buf.release();
                }
            }
            return true;
        }
        return false;
    }

    private static final Optional<Holder<SoundEvent>> currentSpearHitSoundEvent = Optional.of(SoundEvents.SPEAR_HIT);
    private static final Optional<Holder<SoundEvent>> currentSpearUseSoundEvent = Optional.of(SoundEvents.SPEAR_USE);

    public void onSpearEntity(Event<ClientboundEntityEventPacket> eventPost) {
        if (checkNull()) return;
        if (fixOldVersionSpearSound.get() && eventPost.context.getEventId() == VDataFlag.ENTITY_STATUS_KINETIC_ATTACK) {
            Entity entity = eventPost.context.getEntity(mc.level);
            if (entity instanceof LivingEntity lv && lv.isUsingItem()) {
                ItemStack stack = lv.getUseItem();
                if (materialSwordToSpearMap.containsKey(stack.getItem())
                        && VItem.getInstance().isSpear(stack)) {
                    currentSpearHitSoundEvent.ifPresent((hitSound) -> {
                        mc.level.playLocalSound(lv, hitSound.value(), entity.getSoundSource(), 1.0F, 1.0F);
                    });
                }
            }
        }
    }

    public void onSpearUse(Event<UseItem> eventAction) {
        if (checkNull()) return;
        if (fixOldVersionSpearSound.get()) {
            InteractionHand hand = eventAction.context.hand();
            ItemStack stack = mc.player.getItemInHand(hand);
            if (materialSwordToSpearMap.containsKey(stack.getItem())
                    && VItem.getInstance().isSpear(stack)) {
                MutableInt mutableInt = new MutableInt(0);
                Tasks.scheduleRepeatedPre(
                        () -> {
                            if (mutableInt.getAndIncrement() > 20) {
                                return true;
                            }
                            if (checkNull()) return true;
                            if (mc.player.isUsingItem()) {
                                if (mc.player.getUsedItemHand() == hand
                                        && ItemStack.isSameItemSameComponents(stack, mc.player.getUseItem())) {
                                    currentSpearUseSoundEvent.ifPresent((sound) -> {
                                        mc.player
                                                .level()
                                                .playSound(
                                                        mc.player,
                                                        mc.player.getX(),
                                                        mc.player.getY(),
                                                        mc.player.getZ(),
                                                        sound,
                                                        mc.player.getSoundSource(),
                                                        1.0F,
                                                        1.0F);
                                    });
                                }
                                return true;
                            } else {
                                return false;
                            }
                        },
                        1,
                        1);
            }
        }
    }
}
