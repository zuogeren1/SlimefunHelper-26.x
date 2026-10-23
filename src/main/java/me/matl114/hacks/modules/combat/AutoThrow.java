package me.matl114.hacks.modules.combat;

import com.google.common.base.Predicates;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.InteractionTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.modules.interact.SequencedActionManager;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.tasks.TimerExecutor;
import me.matl114.managers.Configs;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.CollisionUtil;
import me.matl114.utils.InventoryUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutoThrow extends BaseModule {
    public AutoThrow() {
        super("AutoThrow");
    }

    public final ModulePath root = makePath(Configs.COMBAT_CONFIG, "combat-utils.auto-throw");

    public final FlagRef ground = flagBuilder(root.add("ground-throw")).build();

    public final DoubleRef acceptHeight =
            doubleBuilder(root.add("ground-height")).defaultValue(0.5).build();

    public final FlagRef ceiling = flagBuilder(root.add("ceiling-throw")).build();

    public final DoubleRef ceilingHeight =
            doubleBuilder(root.add("ceiling-height")).defaultValue(0.5).build();

    public final FlagRef flyAlways = flagBuilder(root.add("fly-always")).build();

    public final FlagRef upFly = flagBuilder(root.add("up-fly")).build();

    public final FlagRef avoidEnemies = flagBuilder(root.add("avoid-enemies")).build();

    public final DoubleRef avoidEnemyDistance = doubleBuilder(root.add("avoid-enemies-distance"))
            .defaultValue(1.5)
            .validator(Configs.doubleRange(0.0D, 10.0D))
            .build();

    public final FlagRef eatingAbort = builder(root.add("using-item-abort"), Boolean.class)
            .defaultValue(false)
            .build();
    public final FlagRef offhand = flagBuilder(root.add("offhand")).build();
    public final EnumRef<GhostHandMode> ghostHand = builder(root.add("ghost-hand-mode"), GhostHandMode.class)
            .defaultValue(GhostHandMode.INV_SWAP)
            .build();

    public final FlagRef swingHand =
            builder(root.add("swing-hand"), Boolean.class).defaultValue(true).build();

    public final ModulePath xp = root.add("xp-bottles");

    public final ModulePath potions = root.add("potions");

    public final FlagRef xpEnable = flagBuilder(xp.addEnable()).build();

    public final KeyBindRef xpHotkey =
            moduleEntry(xp.addHotkey(), new MultiKeyBind(), xp.addEnable()).build();

    public final FlagRef checkDurability =
            flagBuilder(xp.add("check-durability")).build();

    public final IntRef startFixArmorDur = intBuilder(xp.add("start-fixing-durability"))
            .defaultValue(100)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef stopFixArmorDur = intBuilder(xp.add("stop-fixing-durability"))
            .defaultValue(50)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final IntRef delay = intBuilder(xp.add("delay"))
            .defaultValue(3)
            .validator(Configs.INT_NONNEGATIVE)
            .build();
    public final IntRef mul = intBuilder(xp.add("multiply"))
            .defaultValue(8)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final FlagRef autoClose = flagBuilder(xp.add("auto-close")).build();

    public final FlagRef potionEnable = flagBuilder(potions.addEnable()).build();

    public final KeyBindRef potionHotkey = moduleEntry(potions.addHotkey(), new MultiKeyBind(), potions.addEnable())
            .build();

    private final TimerExecutor effectCheck = new TimerExecutor();

    public final NBTRef<EntrySet<MobEffect>> keepPotions = builder(
                    potions.add("keep-potion"), EntrySet.<MobEffect>parameter())
            .defaultValue(new EntrySet<>(BuiltInRegistries.MOB_EFFECT, List.of()))
            .build();

    public final KeyBindRef manualPotion = hotkey(potions.add("manually-throw"), new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::throwPotion))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        ModulePreset preset = event.context.getValue();
        if (preset.isMultitaskPreset()) eatingAbort.set(preset.getEatingAbort(eatingAbort.get()));
    }

    boolean startMending = false;

    private boolean canThrow() {
        AABB box = mc.player.getBoundingBox();
        if (avoidEnemies.get()) {
            AABB box2 = box.inflate(avoidEnemyDistance.get(), avoidEnemyDistance.get(), avoidEnemyDistance.get());
            if (TargetSelector.INSTANCE.getAttackableEntities(3).stream()
                    .filter(s -> s instanceof Player)
                    .anyMatch(s -> s.getBoundingBox().intersects(box2))) {
                return false;
            }
        }
        if (ground.get()) {
            box = box.expandTowards(0, -acceptHeight.get(), 0);
        }
        if (ceiling.get()) {
            box = box.expandTowards(0, ceilingHeight.get(), 0);
        }
        if (CollisionUtil.isBoxCollided(mc.level, mc.player, box)) {
            return true;
        }

        if (upFly.get()) {
            Vec3 vec3d = PlayerStateManager.INSTANCE.lastKnownRealMovementSpeed.normalize();
            if (EntityUtils.rotationToPitch(vec3d) < -80 && PlayerStateManager.INSTANCE.lastPitch < -80) {
                return true;
            }
        }
        if (flyAlways.get()) {
            if (mc.player.isFallFlying()
                    && PlayerStateManager.INSTANCE.lastKnownRealMovementSpeed.horizontalDistanceSqr() < 1E-2) {
                return true;
            }
        }
        return false;
    }

    private boolean canMend() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            var stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }

            if (stack.getDamageValue() >= stopFixArmorDur.get()) {
                return true;
            }
        }

        return false;
    }

    private boolean needMending() {
        int startFix = startFixArmorDur.get();

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            var stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            if (stack.getDamageValue() >= startFix) {
                return true;
            }
        }
        return false;
    }

    int timer = 0;

    public void onPreInputEvent() {
        if (checkNull()) return;
        if (canThrow()) {
            if (eatingAbort.get() && mc.player.isUsingItem()) return;
            if (++timer >= delay.get()) {
                //
                if (xpEnable.get()) {
                    timer = 0;
                    if (checkDurability.get()) {
                        if (startMending) {
                            if (canMend()) {
                                throwItem((item) -> item.is(Items.EXPERIENCE_BOTTLE) ? 1.0D : null, mul.get());
                            } else {
                                startMending = false;
                                if (autoClose.get()) {
                                    HotKeyUtils.wrapFlagAsToggle(xp.addEnable().toPath(), xpEnable)
                                            .run();
                                }
                            }
                        } else {
                            if (needMending()) {
                                startMending = true;
                            }
                        }
                    } else {
                        startMending = false;
                        throwItem((item) -> item.is(Items.EXPERIENCE_BOTTLE) ? 1.0D : null, mul.get());
                    }

                } else {
                    startMending = false;
                }
            }
            if (potionEnable.get() && effectCheck.run(5)) {
                throwPotion();
            }
        }
    }

    public void throwPotion() {
        if (SequencedActionManager.INSTANCE.isWaitingItemResponse(s -> s.is(Items.SPLASH_POTION))) {
            return;
        }
        Set<Holder<MobEffect>> onePotion = new HashSet<>();
        for (var re : keepPotions.get().set()) {
            var entry = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(re);
            var statusInstance = mc.player.getEffect(entry);
            if (statusInstance == null || statusInstance.endsWithin(20)) {
                onePotion.add(entry);
            }
        }
        AABB checkUpperBox = mc.player.getBoundingBox().inflate(4.0, 8.0, 4.0).expandTowards(0, 40, 0);
        mc.level
                .getEntitiesOfClass(ThrownSplashPotion.class, checkUpperBox, Predicates.alwaysTrue())
                .forEach(potion -> {
                    var po = potion.getItem().get(DataComponents.POTION_CONTENTS);
                    if (po != null) {
                        po.getAllEffects().forEach(effect -> {
                            onePotion.remove(effect.getEffect());
                        });
                    }
                });
        if (!onePotion.isEmpty()) {
            throwItem(
                    s -> {
                        if (s.is(Items.SPLASH_POTION) && s.has(DataComponents.POTION_CONTENTS)) {
                            var con = s.get(DataComponents.POTION_CONTENTS);
                            if (con == null) return null;
                            int totalLevel = 0;
                            for (var re : con.getAllEffects()) {
                                if (onePotion.contains(re.getEffect())) {
                                    totalLevel += re.getAmplifier();
                                }
                            }
                            return totalLevel > 0 ? (double) totalLevel : null;
                        }
                        return null;
                    },
                    1);
        }
    }

    public void throwItem(Function<ItemStack, Double> stackPredicate, int multiply) {
        AABB box = mc.player.getBoundingBox();
        AABB groundCheck = box.expandTowards(0, -acceptHeight.get(), 0);
        AABB ceilingCheck = box.expandTowards(0, ceilingHeight.get(), 0);
        float pitch;
        boolean needSpeed = false;
        if (CollisionUtil.isBoxCollided(mc.level, mc.player, groundCheck)) {
            pitch = 90.0F;
        } else if (CollisionUtil.isBoxCollided(mc.level, mc.player, ceilingCheck)) {
            pitch = -90.0F;
        } else {
            needSpeed = PlayerStateManager.INSTANCE.lastKnownRealMovementSpeed.y > 0.1;
            pitch = -90.0F;
        }
        var entry = InventoryUtils.findBestPlayerItem(
                stackPredicate, ghostHand.get().getSearchSize(offhand.get()), true, false);
        if (entry == null) return;
        int mul = Math.min(multiply, entry.val().getCount());
        var cb = InvExtra.INSTANCE.swapItemToHand(entry.index(), offhand.get(), ghostHand.get());
        if (cb != null) {
            for (var re = 0; re < mul; ++re) {
                if (needSpeed) {
                    mc.gameMode.startPrediction(
                            mc.level,
                            (seq) -> new ServerboundUseItemPacket(
                                    offhand.get() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                                    seq,
                                    PlayerStateManager.INSTANCE.lastYaw,
                                    pitch));
                    if (swingHand.get()) {
                        mc.player.swing(offhand.get() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                    }
                } else {
                    InteractionTasks.interactItem(
                            offhand.get() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                            pitch,
                            PlayerStateManager.INSTANCE.lastYaw,
                            true,
                            swingHand.get());
                }
            }
            cb.run();
        }
    }
}
