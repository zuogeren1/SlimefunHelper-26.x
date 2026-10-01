package me.matl114.hacks.modules.extra;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.ChatRecv;
import me.matl114.events.impl.MetadataUpdate;
import me.matl114.gui.basic.ButtonAction;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.hooks.BaritoneHooks;
import me.matl114.hooks.impl.baritone.BaritoneFuture;
import me.matl114.hooks.impl.baritone.BaritoneLanding;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.DamageUtils;
import me.matl114.versioned.api.VDataFlag;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class SoundNotify extends BaseModule {
    private static final String MOD_ID = "slimefunhelper";
    private static final int SOUND_COOLDOWN_TICKS = 10;
    public static SoundNotify INSTANCE;

    public final ModulePath path = makePath(Configs.EXTRA_CONFIG, "other.sound-notify");

    public SoundNotify() {
        super("SoundNotify");
        INSTANCE = this;
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(path.addEnable()).build();

    private final ModulePath entityLogPath = path.add("entity-log");
    public final FlagRef playerEnter =
            flagBuilder(entityLogPath.add("player-enter-range")).build();
    public final FlagRef playerLeave =
            flagBuilder(entityLogPath.add("player-leave-range")).build();
    public final NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> entityLogSound = soundBuilder(entityLogPath, ENTITY_LOG_SOUND);

    private final ModulePath totemPath = path.add("totem");
    public final FlagRef selfTotem = flagBuilder(totemPath.add("self-pop")).build();
    public final FlagRef otherTotem = flagBuilder(totemPath.add("other-pop")).build();
    public final NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> totemSound = soundBuilder(totemPath, TOTEM_SOUND);

    private final ModulePath effectWarnPath = path.add("effect-warn");
    public final FlagRef effectWarn = flagBuilder(effectWarnPath.addEnable()).build();
    public final NBTRef<EntrySet<MobEffect>> warnedEffects = builder(
                    effectWarnPath.add("effects"), EntrySet.<MobEffect>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.MOB_EFFECT, List.of()))
            .build();
    public final IntRef effectWarnDuration =
            intBuilder(effectWarnPath.add("duration-ticks")).defaultValue(200).build();
    public final NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> effectWarnSound = soundBuilder(effectWarnPath, EFFECT_WARN_SOUND);

    private final ModulePath messagePath = path.add("message-detection");
    public final FlagRef messageDetection = flagBuilder(messagePath.addEnable()).build();
    public final ListRef messageKeywords = builder(messagePath.add("keywords"), ListRef.TYPE)
            .defaultValue(List.of())
            .build();

    // Private-message detection stays inactive until a server-specific format is defined.
    public final FlagRef privateMessageSound =
            flagBuilder(messagePath.add("private-message-sound")).build();
    public final NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> messageDetectionSound = soundBuilder(messagePath, MESSAGE_DETECTION_SOUND);

    private final ModulePath itemSearchPath = path.add("item-search");
    public final FlagRef itemSearch = flagBuilder(itemSearchPath.addEnable()).build();
    public final NBTRef<EntrySet<Item>> importantItems = builder(
                    itemSearchPath.add("important-items"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(
                    new Regex(
                            "^(.*ton_skull|netherite.*|.*_star|.*_apple|.*potion|tot.*|end_c.*l|obsi.*|.*anchor|expe.*|mace|ely.*|.*shulker.*|trident)$"),
                    BuiltInRegistries.ITEM))
            .build();
    public final NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> itemSearchSound = soundBuilder(itemSearchPath, ITEM_SEARCH_SOUND);

    private final ModulePath durabilityPath = path.add("durability");
    public final FlagRef durabilityWarn =
            flagBuilder(durabilityPath.addEnable()).build();
    public final IntRef durabilityThresholdValue =
            intBuilder(durabilityPath.add("threshold")).defaultValue(10).build();

    public final DoubleRef durabilityThresholdPercentage = doubleBuilder(durabilityPath.add("threshold-percentage"))
            .defaultValue(0.1)
            .build();
    public final NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> durabilitySound = soundBuilder(durabilityPath, DURABILITY_SOUND);

    private final ModulePath baritonePath = path.add("baritone");
    public final FlagRef baritoneLanding =
            flagBuilder(baritonePath.add("landing")).build();
    public final NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> baritoneSound = soundBuilder(baritonePath, BARITONE_SOUND);

    private final ModulePath attackPath = path.add("attack");
    public final FlagRef clientMaceAttack =
            flagBuilder(attackPath.add("client-mace")).build();
    public final FlagRef serverMaceAttack =
            flagBuilder(attackPath.add("server-mace")).build();
    public final NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> attackSound = soundBuilder(attackPath, ATTACK_SOUND);

    public static final Optional<Holder<SoundEvent>> ENTITY_LOG_SOUND = registerSound("event.entity-log.notify");
    public static final Optional<Holder<SoundEvent>> TOTEM_SOUND = registerSound("event.totem.notify");
    public static final Optional<Holder<SoundEvent>> EFFECT_WARN_SOUND =
            registerSound("event.effect-warn.notify");
    public static final Optional<Holder<SoundEvent>> MESSAGE_DETECTION_SOUND =
            registerSound("event.message-detection.notify");
    public static final Optional<Holder<SoundEvent>> ITEM_SEARCH_SOUND =
            registerSound("event.item-search.notify");
    public static final Optional<Holder<SoundEvent>> DURABILITY_SOUND = registerSound("event.durability.notify");
    public static final Optional<Holder<SoundEvent>> BARITONE_SOUND = registerSound("event.baritone.notify");
    public static final Optional<Holder<SoundEvent>> ATTACK_SOUND = registerSound("event.attack.notify");
    public static final Optional<Holder<SoundEvent>> SEARCH_LABEL_SOUND =
            registerSound("event.search-label.notify");
    public static final Optional<Holder<SoundEvent>> TEST_SOUND = registerSound("event.test");

    private static Optional<Holder<SoundEvent>> registerSound(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, path);
        try {
            return Optional.of(Registry.registerForHolder(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> soundBuilder(
            ModulePath soundPath, Optional<Holder<SoundEvent>> defaultSound) {
        return builder(soundPath.add("sound"), me.matl114.hacks.utils.config.Holder.<SoundEvent>parameter())
                .defaultValue(me.matl114.hacks.utils.config.Holder.of(
                        BuiltInRegistries.SOUND_EVENT,
                        defaultSound.map(Holder::value).orElse(null)))
                .build();
    }

    private enum Cue {
        ENTITY_LOG,
        TOTEM,
        EFFECT_WARN,
        MESSAGE_DETECTION,
        ITEM_SEARCH,
        DURABILITY,
        BARITONE,
        ATTACK
    }

    private final EnumMap<Cue, TimerExecutor> soundTimers;

    {
        EnumMap<Cue, TimerExecutor> timers = new EnumMap<>(Cue.class);
        for (Cue cue : Cue.values()) {
            timers.put(cue, new TimerExecutor());
        }
        soundTimers = timers;
    }

    private final Set<Holder<MobEffect>> alertedEffects = new HashSet<>();
    private final Set<EquipmentSlot> lowDurabilitySlots = new HashSet<>();
    private boolean durabilityInitialized;

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundAddEntityPacket.class), this::onEntitySpawn);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundRemoveEntitiesPacket.class), this::onEntityDestroy);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundEntityEventPacket.class), this::onEntityStatus);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class), this::onClientAttack);
        registerListener(Listener.getPacketPoint().getChannel(ClientboundDamageEventPacket.class), this::onServerAttack);
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.ITEM), this::handleItemEntityItemData);
        registerListener(
                Listener.getEntityTrackDataUpdate().getChannel(EntityTypes.ITEM_FRAME), this::handleItemFrameItemData);
        registerListener(Listener.getPostTick(), this::onPostTick);
        registerListener(Listener.getServerDisconnectPoint(), this::onDisconnect);
        registerListener(Listener.getChatMessageReceive(), this::onChatReceive);
        registerListener(BaritoneHooks.getLandingEvent(), this::onBaritoneLanding);
    }

    public void onEntitySpawn(Event<ClientboundAddEntityPacket> event) {
        if (!enable.get() || !playerEnter.get() || checkNull() || loginServerCheck()) return;
        ClientboundAddEntityPacket packet = event.context();
        if (packet.getType() == EntityTypes.PLAYER && !packet.getUUID().equals(mc.player.getUUID())) {
            play(Cue.ENTITY_LOG);
        }
    }

    public void onEntityDestroy(Event<ClientboundRemoveEntitiesPacket> event) {
        if (!enable.get() || !playerLeave.get() || checkNull() || loginServerCheck()) return;
        for (int entityId : event.context().getEntityIds()) {
            if (mc.level.getEntity(entityId) instanceof Player player && player != mc.player) {
                play(Cue.ENTITY_LOG);
                return;
            }
        }
    }

    public void onEntityStatus(Event<ClientboundEntityEventPacket> event) {
        if (!enable.get() || checkNull() || event.context().getEventId() != EntityEvent.PROTECTED_FROM_DEATH) {
            return;
        }
        Entity entity = event.context().getEntity(mc.level);
        if (entity == mc.player && selfTotem.get()) {
            play(Cue.TOTEM);
        } else if (entity instanceof Player && entity != mc.player && otherTotem.get()) {
            play(Cue.TOTEM);
        }
    }

    public void onClientAttack(Event<ServerboundAttackPacket> event) {
        if (!enable.get()
                || !clientMaceAttack.get()
                || checkNull()
                || event.isCancelled()) {
            return;
        }
        if (mc.player.getMainHandItem().is(Items.MACE) && PlayerStateManager.INSTANCE.fallDistance > 1.5D) {
            play(Cue.ATTACK);
        }
    }

    public void onServerAttack(Event<ClientboundDamageEventPacket> event) {
        if (!enable.get() || !serverMaceAttack.get() || checkNull()) return;
        ClientboundDamageEventPacket packet = event.context();
        if (packet.sourceCauseId() == mc.player.getId()
                && mc.level.getEntity(packet.entityId()) instanceof Player
                && DamageUtils.isType(packet.sourceType().unwrapKey().orElse(null), "mace_smash")) {
            play(Cue.ATTACK);
        }
    }

    public void onPostTick(Event<Void> event) {
        if (checkNull()) return;
        if (!enable.get()) {
            alertedEffects.clear();
            resetDurabilityTracking();
            return;
        }
        updateEffectWarnings();
        updateDurabilityWarnings();
    }

    private void updateEffectWarnings() {
        if (!effectWarn.get()) {
            alertedEffects.clear();
            return;
        }
        Set<Holder<MobEffect>> activeConfiguredEffects = new HashSet<>();
        int threshold = Math.max(0, effectWarnDuration.get());
        for (MobEffectInstance instance : mc.player.getActiveEffects()) {
            Holder<MobEffect> effect = instance.getEffect();
            if (!warnedEffects.get().test(effect.value())) continue;
            activeConfiguredEffects.add(effect);
            int duration = instance.getDuration();
            if (duration > 0 && duration < threshold && alertedEffects.add(effect)) {
                play(Cue.EFFECT_WARN);
            } else if (duration <= 0 || duration >= threshold) {
                alertedEffects.remove(effect);
            }
        }
        alertedEffects.retainAll(activeConfiguredEffects);
    }

    public void handleItemEntityItemData(Event<MetadataUpdate> entryUpdateEvent) {
        if (itemSearch.get()) {
            var entry = entryUpdateEvent.context().metadata();
            if (entry.id() == VDataFlag.ID_ITEM_ITEMSTACK
                    && (entry.value()) instanceof ItemStack stack
                    && entryUpdateEvent.context.entity() instanceof ItemEntity item) {
                onItemEntity(item, item.getItem(), stack);
            }
        }
    }

    public void handleItemFrameItemData(Event<MetadataUpdate> entryUpdateEvent) {
        if (itemSearch.get()) {
            var entry = entryUpdateEvent.context().metadata();
            if (entry.id() == VDataFlag.ID_ITEM_FRAME_ITEMSTACK
                    && entry.value() instanceof ItemStack stack
                    && entryUpdateEvent.context.entity() instanceof ItemFrame item) {
                onItemEntity(item, item.getItem(), stack);
            }
        }
    }

    public void onItemEntity(Entity itemEntity, ItemStack oldStack, ItemStack stack) {
        if (!stack.isEmpty()
                && importantItems.get().test(stack.getItem())
                && !importantItems.get().test(oldStack.getItem())) {
            play(Cue.ITEM_SEARCH);
        }
    }

    private void updateDurabilityWarnings() {
        if (!durabilityWarn.get()) {
            resetDurabilityTracking();
            return;
        }
        double per = Math.clamp(durabilityThresholdPercentage.get(), 0, 1);
        int thresholdValue = Math.max(0, durabilityThresholdValue.get());
        for (EquipmentSlot slot :
                new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.BODY, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                lowDurabilitySlots.remove(slot);
                continue;
            }
            ItemStack identity = stack.copy();
            identity.setDamageValue(0);
            int left = stack.getMaxDamage() - stack.getDamageValue();
            double remainingPercent = (double) (left) / stack.getMaxDamage();
            if (remainingPercent >= per && left >= thresholdValue) {
                lowDurabilitySlots.remove(slot);
            } else if (!durabilityInitialized) {
                lowDurabilitySlots.add(slot);
            } else if (lowDurabilitySlots.add(slot)) {
                play(Cue.DURABILITY);
            }
        }
        durabilityInitialized = true;
    }

    private void resetDurabilityTracking() {
        lowDurabilitySlots.clear();
        durabilityInitialized = false;
    }

    public void onChatReceive(Event<ChatRecv> event) {
        if (!enable.get() || !messageDetection.get() || event.isCancelled() || checkNull()) {
            return;
        }
        if (privateMessageSound.get()) {
            // todo: detect private message?
        }
        String message = ChatUtils.textToPlainString(event.context().text()).toLowerCase(Locale.ROOT);
        for (String keyword : messageKeywords.get()) {
            if (keyword != null && !keyword.isBlank() && message.contains(keyword.toLowerCase(Locale.ROOT))) {
                play(Cue.MESSAGE_DETECTION);
                return;
            }
        }
    }

    public void onBaritoneLanding(Event<BaritoneFuture> event) {
        if (enable.get() && baritoneLanding.get()) {
            BaritoneLanding landing = event.getArgs(0);
            if (landing != null) {
                play(Cue.BARITONE);
            }
        }
    }

    public void onDisconnect(Event<Void> event) {
        alertedEffects.clear();
        resetDurabilityTracking();
        for (TimerExecutor timer : soundTimers.values()) {
            timer.mark(-SOUND_COOLDOWN_TICKS);
        }
    }

    @Override
    public void addCustomWidgets(Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank) {
        super.addCustomWidgets(acceptor, dx, dy, dblank);
        acceptor.accept(createExecuteButton(
                "widget.sound-notify.test-usage", ButtonAction.run(this::playTestSound), 0, dblank, dx, dy));
    }

    private void playTestSound() {
        playSound(TEST_SOUND);
    }

    private void play(Cue cue) {
        if (!enable.get() || checkNull()) return;
        soundTimers.get(cue).run(SOUND_COOLDOWN_TICKS, () -> playSound(soundFor(cue)));
    }

    public void playSound(Optional<Holder<SoundEvent>> sound) {
        if (checkNull()) return;
        sound.ifPresent(soundEvent -> mc.level.playSound(
                mc.player,
                mc.player.getX(),
                mc.player.getY(),
                mc.player.getZ(),
                soundEvent,
                mc.player.getSoundSource(),
                1.0F,
                1.0F));
    }

    private boolean loginServerCheck() {
        return mc.level.getWorldBorder().getSize() < 100;
    }

    private Optional<Holder<SoundEvent>> soundFor(Cue cue) {
        return switch (cue) {
            case ENTITY_LOG -> configuredSound(entityLogSound);
            case TOTEM -> configuredSound(totemSound);
            case EFFECT_WARN -> configuredSound(effectWarnSound);
            case MESSAGE_DETECTION -> configuredSound(messageDetectionSound);
            case ITEM_SEARCH -> configuredSound(itemSearchSound);
            case DURABILITY -> configuredSound(durabilitySound);
            case BARITONE -> configuredSound(baritoneSound);
            case ATTACK -> configuredSound(attackSound);
        };
    }

    private Optional<Holder<SoundEvent>> configuredSound(NBTRef<me.matl114.hacks.utils.config.Holder<SoundEvent>> soundRef) {
        me.matl114.hacks.utils.config.Holder<SoundEvent> holder = soundRef.get();
        if (holder == null || holder.entry() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(holder.registry().wrapAsHolder(holder.entry()));
    }
}
