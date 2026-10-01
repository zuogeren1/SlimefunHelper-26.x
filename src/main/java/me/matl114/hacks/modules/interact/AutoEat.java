package me.matl114.hacks.modules.interact;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import me.matl114.accessors.access.ClientAccess;
import me.matl114.accessors.hacks.KeyBindAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.UseItem;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.ElytraExtra;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.OptionalPrimitive;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.InteractUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.versioned.api.VItem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import org.apache.commons.lang3.stream.Streams;

public class AutoEat extends BaseModule {
    public final ModulePath interactionTweaks = makePath(Configs.INTERACT_CONFIG, "interaction-tweaks");
    public final ModulePath autoEat = interactionTweaks.add("auto-eat");

    public AutoEat() {
        super("AutoEat");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(autoEat.addEnable()).build();

    public final KeyBindRef hotkey = moduleEntry(autoEat.addHotkey(), new MultiKeyBind(), autoEat.addEnable())
            .build();

    public final NBTRef<EntrySet<Item>> whiteListItem = builder(
                    autoEat.add("white-list-item"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(new Regex("^(golden_apple|potion|golden_carrot)$"), BuiltInRegistries.ITEM))
            .build();

    public final FlagRef fireworkFix = builder(autoEat.add("pause-for-fireworks"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef autoFireworks = builder(autoEat.add("auto-fireworks"), Boolean.class)
            .defaultValue(false)
            .build();

    public final FlagRef pauseWhenSurround = builder(autoEat.add("pause-when-surround"), Boolean.class)
            .defaultValue(false)
            .build();

    public final NBTRef<OptionalPrimitive<Integer>> pauseWhenInteracting = builder(
                    autoEat.add("pause-when-interact"), OptionalPrimitive.INT_TYPE)
            .defaultValue(new OptionalPrimitive<>(false, NBTTypes.INT_TYPE, 2))
            .build();

    public final EnumRef<GhostHandMode> ghostHand = builder(autoEat.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef log =
            builder(autoEat.add("log"), Boolean.class).defaultValue(true).build();

    public final ModulePath auto = autoEat.add("auto");

    public final FlagRef inv = flagBuilder(auto.addEnable()).build();

    public final KeyBindRef hk1 =
            toggleHotkey(auto.addHotkey(), new MultiKeyBind(), auto.addEnable()).build();

    public final FlagRef enableHealth =
            builder(auto.add("enable-health"), Boolean.class).defaultValue(true).build();

    public final FlagRef enableHunger =
            builder(auto.add("enable-hunger"), Boolean.class).defaultValue(true).build();

    public final DoubleRef healthLevel = doubleBuilder(auto.add("health-level"))
            .defaultValue(10.0D)
            .validator(Configs.doubleRange(0.0D, 20.0D))
            .build();

    public final IntRef hungerLevel = intBuilder(auto.add("hunger-level"))
            .defaultValue(16)
            .validator(Configs.intRange(0, 20))
            .build();

    public final FlagRef noEnemy =
            builder(auto.add("no-enemy"), Boolean.class).defaultValue(true).build();

    public final DoubleRef noEnemyAir = doubleBuilder(auto.add("no-enemy-distance-air"))
            .defaultValue(8.0D)
            .validator(Configs.doubleRange(0.0D, 64.0D))
            .build();

    public final DoubleRef noEnemyGround = doubleBuilder(auto.add("no-enemy-distance-ground"))
            .defaultValue(8.0D)
            .validator(Configs.doubleRange(0.0D, 64.0D))
            .build();

    public final IntRef cooldown =
            intBuilder(auto.add("cooldown")).defaultValue(20).build();

    public final FlagRef pauseInLava = builder(auto.add("pause-in-lava"), Boolean.class)
            .defaultValue(false)
            .build();

    public final ModulePath manual = autoEat.add("left-click-force-eat");

    public final FlagRef forceEatLeftClick = flagBuilder(manual.addEnable()).build();

    public final KeyBindRef hk2 = toggleHotkey(manual.addHotkey(), new MultiKeyBind(), manual.addEnable())
            .build();

    public final FlagRef forceEatLeftClickHold =
            flagBuilder(manual.add("need-press-left-button")).build();

    public final FlagRef forceEatOffhand = builder(manual.add("enable-offhand"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef leftClickWeapon = builder(manual.add("enable-weapon"), Boolean.class)
            .defaultValue(true)
            .build();

    public final NBTRef<EntrySet<Item>> extraLeftClickItem = builder(
                    manual.add("enable-extra-items"), EntrySet.<Item>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.ITEM, List.of(Items.TOTEM_OF_UNDYING)))
            .build();

    private boolean eating;
    private boolean manualEating;
    private Runnable nextTickCallback = null;
    private Runnable restoreCallback = null;
    private int eatingSlot = -1;
    private int eatingCooldownTick = 0;
    private int nextTickStartEat = 0;
    private final TimerExecutor interactMark = new TimerExecutor();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onTickPre);
        registerListener(Listener.getPostHandleInputEvents(), this::onTickPost);
        registerListener(Listener.getPlayerRespawnPoint(), this::onWorldSwitch);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundEntityEventPacket.class),
                this::onStatusConsumed);
        registerListener(Listener.getPrePlayerUseItem(), this::onRightClick);
        registerListener(Listener.getPlayerScrollHotBar(), this::onHotBarManuallySwap);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class),
                this::onInteractPacket,
                114514);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemOnPacket.class),
                this::onInteractPacket,
                114514);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class),
                this::onInteractPacket,
                114514);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        stopEating();
    }

    private void onInteractPacket(Event<?> eventPacket) {
        if (eventPacket.isCancelled()) return;
        interactMark.mark();
    }

    private void onStatusConsumed(Event<ClientboundEntityEventPacket> event) {
        if (eating
                && event.context.getEntity(mc.level) == mc.player
                && event.context.getEventId() == EntityEvent.USE_ITEM_COMPLETE) {
            stopEating();
            eatingCooldownTick = Tasks.getTick() + cooldown.get();
        }
    }

    private void onHotBarManuallySwap(Event<Integer> eventSwap) {
        if (eating && eventSwap.context() != InventoryUtils.getSelectedSlot()) {
            if (log.get()) {
                logI18N("message.module.auto-eat.stop");
            }
            stopEating();
            eventSwap.cancel();
        }
    }

    private boolean canContinueEat() {
        return eatingSlot >= 0
                && mc.player.isUsingItem()
                && (mc.player.getUsedItemHand()
                        == (eatingSlot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND))
                && (InventoryUtils.getSelectedSlot() == eatingSlot || eatingSlot == 40)
                && eating;
    }

    private IndexEntry<ItemStack> findHandStack(boolean health) {
        ItemStack stack = mc.player.getMainHandItem();
        ItemStack stackOffhand = mc.player.getOffhandItem();
        Double main = scoreFood(stack, health);
        Double off = scoreFood(stackOffhand, health);
        if (main != null) {
            if (off != null && off > main) {
                return new IndexEntry<>(40, stackOffhand);
            }
            return new IndexEntry<>(InventoryUtils.getSelectedSlot(), stack);
        }
        return off != null ? new IndexEntry<>(40, stackOffhand) : null;
    }

    private IndexEntry<ItemStack> findFood(boolean useInv) {
        // TODO VALIDATE
        boolean healthPriority = enableHealth.get() && mc.player.getHealth() <= healthLevel.get();
        return useInv
                ? InventoryUtils.findBestPlayerItem(
                        stack -> scoreFood(stack, healthPriority),
                        ghostHand.get().getSearchSize(false),
                        true,
                        false)
                : findHandStack(healthPriority);
    }

    private boolean interactionCheck() {
        if (pauseWhenSurround.get()) {
            if (AutoSurround.INSTANCE.enable.get() && AutoSurround.INSTANCE.needPlaceState.state()) {
                return false;
            }
            if (SelfTrap.INSTANCE.enable.get() && SelfTrap.INSTANCE.needPlaceState.state()) {
                return false;
            }
        }
        if (pauseWhenInteracting.get().isPresent()) {
            if (!interactMark.canRun(pauseWhenInteracting.get().getValue())) {
                return false;
            }
        }
        return true;
    }

    private void tryStartEating(@Nonnull IndexEntry<ItemStack> re, boolean offHand, boolean manual) {

        if (log.get()) {
            Component text = VItem.getInstance().getFormattedName(re.val());
            logI18N("message.module.auto-eat.start", text);
        }
        offHand = offHand || re.index() == 40;
        Runnable cbb = InvExtra.INSTANCE.swapItemToHand(re.index(), offHand, ghostHand.get());
        if (cbb != null) {
            Runnable nextTick = nextTickCallback;
            nextTickCallback = null;
            ClientAccess.of(mc).simulateUseItem(offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            if (mc.player.isUsingItem()
                    && ((mc.player.getUsedItemHand() == InteractionHand.OFF_HAND) == offHand)
                    && ItemStack.isSameItemSameComponents(re.val(), mc.player.getUseItem())) {
                mc.options.keyUse.setDown(true);
                eating = true;
                manualEating = manual;
                if (nextTick != null) {
                    restoreCallback = () -> {
                        cbb.run();
                        nextTick.run();
                    };
                } else {
                    restoreCallback = cbb;
                }
                eatingSlot = offHand ? 40 : InventoryUtils.getSelectedSlot();
            } else {
                KeyBindAccess.of(mc.options.keyUse).resetKeyState();
                cbb.run();
                if (nextTick != null) nextTick.run();
            }
        }
    }

    private void onWorldSwitch(Event<LocalPlayer> event) {
        stopEating();
    }

    private void stopEating() {
        if (eating) {
            KeyBindAccess.of(mc.options.keyUse).resetKeyState(); // mc.options.keyUse.setDown(false);
            if (!checkNull() && restoreCallback != null) {
                nextTickCallback = restoreCallback;
            }
        }
        restoreCallback = null;
        eating = false;
        manualEating = false;
        eatingSlot = -1;
        eatingCooldownTick = Tasks.getTick() + cooldown.get();
    }

    boolean lastAutoFireworkIsDone = false;

    public void onTickPre(Event<Void> event) {
        LocalPlayer player = mc.player;
        if (checkNull()) {
            if (eating) {
                stopEating();
            }
        }
        if (eating) {
            if (canContinueEat()) {
                mc.options.keyUse.setDown(true);
            } else {
                if (log.get()) {
                    logI18N("message.module.auto-eat.stop");
                }
                stopEating();
            }
        }
    }

    public boolean mayUseItem() {
        if (mc.player.isUsingItem()) {
            return true;
        } else if ((VItem.getInstance().isSpear(mc.player.getItemInHand(InteractionHand.MAIN_HAND))
                || VItem.getInstance().isSpear(mc.player.getItemInHand(InteractionHand.OFF_HAND)))) {
            if (mc.options.keyUse.isDown()) {
                return true;
            }
            if (InteractionTasks.getAutoUse().lastAutoUsingSpear) {
                return true;
            }
        }
        return false;
    }

    public void onTickPost(Event<Void> event) {
        if (checkNull()) {
            return;
        }
        LocalPlayer player = mc.player;
        if (enable.get()) {
            if (!eating) {
                boolean canStartEat = false;
                boolean useInv = inv.get();
                boolean offHand = false;
                if (!mayUseItem()) {
                    find_eat_condition:
                    {
                        if (nextTickStartEat != 0) {
                            canStartEat = true;
                            useInv = true;
                            offHand = nextTickStartEat > 1;
                            nextTickStartEat = 0;
                            break find_eat_condition;
                        }
                        if (eatingCooldownTick > Tasks.getTick()) {
                            break find_eat_condition;
                        }
                        if (pauseInLava.get()
                                && (PlayerStateManager.INSTANCE.lastInLava || PlayerStateManager.INSTANCE.lastInWall)) {
                            break find_eat_condition;
                        }
                        if (noEnemy.get()) {
                            double dist = mc.player.isFallFlying() ? noEnemyAir.get() : noEnemyGround.get();
                            if (dist > 1E-6
                                    && TargetSelector.INSTANCE.searchAttackEntity(
                                                    dist, true, (pl) -> pl instanceof Player)
                                            != null) {
                                break find_eat_condition;
                            }
                        }
                        if (enableHealth.get() && player.getHealth() <= healthLevel.get()) {
                            canStartEat = true;
                            break find_eat_condition;
                        }
                        if (enableHunger.get() && player.getFoodData().getFoodLevel() <= hungerLevel.get()) {
                            canStartEat = true;
                            break find_eat_condition;
                        }
                    }
                }
                if (canStartEat) {
                    boolean canStartNow = true;
                    var re = findFood(useInv);
                    if (re == null) {
                        return;
                    }
                    if (fireworkFix.get() && player.isFallFlying()) {
                        // using
                        if (ElytraExtra.INSTANCE.getTicksSinceLastFireworkSpawn() > 10) {
                            canStartNow = false;
                        }
                    }
                    if (autoFireworks.get() && player.isFallFlying() && !canStartNow) {
                        // fresh
                        if (!lastAutoFireworkIsDone) {
                            ElytraExtra.INSTANCE.sendCustomUseFireworkPacket();
                            lastAutoFireworkIsDone = true;
                        }
                    }
                    if (!interactionCheck()) {
                        canStartNow = false;
                    }
                    if (canStartNow) {
                        lastAutoFireworkIsDone = false;
                        tryStartEating(re, false, false);
                    }
                }
            }
        }
        if (restoreCallback == null && nextTickCallback != null) {
            try {
                nextTickCallback.run();
            } finally {
                nextTickCallback = null;
            }
        }
    }

    private boolean canHoldUseEat(ItemStack stack, ItemStack offhandStack) {
        return (((leftClickWeapon.get()
                                && (VItem.getInstance().isTool(stack)
                                        || VItem.getInstance().isWeapon(stack)))
                        || extraLeftClickItem.get().test(stack.getItem()))
                && !VItem.getInstance().isSpear(stack)
                && !InteractUtils.canHoldUse(offhandStack));
    }

    public void onRightClick(Event<UseItem> event) {
        InteractionHand hand = event.context.hand();
        if (enable.get() && forceEatLeftClick.get() && mc.options.keyUse.isDown() && !eating) {
            ItemStack stack = mc.player.getItemInHand(hand);
            InteractionHand offhand =
                    hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack offhandStack = mc.player.getItemInHand(offhand);
            if (canHoldUseEat(stack, offhandStack) || (nextTickCallback != null)) {
                var re = findFood(true);
                if (re == null) {
                    return;
                }
                boolean canStartEat = true;
                if (fireworkFix.get() && mc.player.isFallFlying()) {
                    // using
                    if (ElytraExtra.INSTANCE.getTicksSinceLastFireworkSpawn() > 10) {
                        canStartEat = false;
                    }
                }
                if (autoFireworks.get() && mc.player.isFallFlying() && !canStartEat) {
                    // fresh
                    if (!lastAutoFireworkIsDone) {
                        ElytraExtra.INSTANCE.sendCustomUseFireworkPacket();
                        lastAutoFireworkIsDone = true;
                    }
                }
                if (!interactionCheck()) {
                    canStartEat = false;
                }
                if (canStartEat) {
                    lastAutoFireworkIsDone = false;
                    tryStartEating(re, hand == InteractionHand.OFF_HAND, true);
                    if (eating) {
                        event.cancel();
                        event.context.actionResult(InteractionResult.SUCCESS);
                    }
                }
            }
        }
    }

    private Double scoreFood(ItemStack stack, boolean hurtPriority) {
        if (!VItem.getInstance().isEatable(stack)) {
            return null;
        }
        if (!whiteListItem.get().test(stack.getItem())) {
            return null;
        }
        FoodProperties food = getFoodComponent(stack);

        double score;
        if (food != null) {
            if (mc.player.canEat(food.canAlwaysEat())) {
                int hunger = food.nutrition();
                score = food.saturation() * hunger;
            } else {
                return null;
            }
        } else {
            score = 0;
        }
        // only combat eat gapple
        if (hurtPriority && mc.level.players().size() > 1) {
            if (isGoldenAppleFood(stack)) {
                score += 100.0D;
            }
            if (isHealingPotion(stack)) {
                score += 50.0D;
            }
        }
        if (score <= 0.0D) {
            return null;
        }
        return score;
    }

    private FoodProperties getFoodComponent(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.get(DataComponents.FOOD);
    }

    private boolean isGoldenAppleFood(ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    private final Set<Holder<MobEffect>> healingEffects = new HashSet<>();

    {
        healingEffects.add(MobEffects.INSTANT_HEALTH);
        healingEffects.add(MobEffects.REGENERATION);
    }

    private boolean isHealingPotion(ItemStack stack) {
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) {
            return false;
        }
        if (stack.getAllOfType(PotionContents.class)
                .anyMatch(component -> Streams.of(component.getAllEffects())
                        .map(MobEffectInstance::getEffect)
                        .anyMatch(healingEffects::contains))) {
            return true;
        }
        for (var effect : consumable.onConsumeEffects()) {
            if (effect instanceof ApplyStatusEffectsConsumeEffect apply
                    && apply.effects().stream()
                            .map(MobEffectInstance::getEffect)
                            .anyMatch(healingEffects::contains)) {
                return true;
            }
        }
        return false;
    }
}
