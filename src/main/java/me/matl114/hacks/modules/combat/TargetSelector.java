package me.matl114.hacks.modules.combat;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;
import lombok.Getter;
import me.matl114.managers.command.MainCommand;
import me.matl114.gui.basic.*;
import me.matl114.hacks.ChatTasks;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.HotKeyUtils;
import me.matl114.hacks.utils.config.*;
import me.matl114.managers.Configs;
import me.matl114.managers.FileManager;
import me.matl114.managers.config.*;
import me.matl114.managers.file.FileStorage;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.commands.commandGroup.CommandContext;
import me.matl114.utils.commands.commandGroup.SubCommand;
import me.matl114.utils.commands.commandGroup.TreeSubCommand;
import me.matl114.utils.commands.params.ArgumentInputStream;
import me.matl114.utils.commands.params.SimpleCommandArgs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

public class TargetSelector extends BaseModule {
    public static TargetSelector INSTANCE;
    public final ModulePath attack = makePath(Configs.COMBAT_CONFIG, "attack");

    public TargetSelector() {
        super("TargetSelector");
        INSTANCE = this;
    }

    public final FlagRef grimExpandEyeHeight =
            flagBuilder(attack.add("use-grim-expand-eye-height")).build();

    public final NBTRef<EntityTypeRegex> whiteListTypes = builder(attack.add("whitelist"), EntityTypeRegex.class)
            .defaultValue(new EntityTypeRegex(new Regex("^(monster|!endermite|player)$")))
            .build();

    public final NBTRef<Regex> friendNameRegex = builder(attack.add("friends"), Regex.class)
            .defaultValue(new Regex("^(.*NPC.*)$"))
            .build();

    public final KeyBindRef addFriend = hotkey(attack.add("add-friend-hotkey"))
            .defaultValue(new MultiKeyBind())
            .registerHotkey(HotKeyUtils.wrapAsHandler(this::onAddFriend))
            .build();

    public final NBTRef<OptionalPrimitive<String>> onAddSend = builder(
                    attack.add("send-on-add-friend"), OptionalPrimitive.type(String.class))
            .defaultValue(new OptionalPrimitive<>(
                    false, NBTTypes.STRING_TYPE, "/msg %s I have just added you to my friends list!"))
            .build();

    public final FlagRef attackFriend =
            builder(attack.add("att-friend"), FlagRef.TYPE).defaultValue(true).build();

    public final KeyBindRef attackFriendHotkey = toggleHotkey(
                    attack.add("att-friend-hotkey"), new MultiKeyBind(), attack.add("att-friend"))
            .build();

    public final FlagRef attackNamedEntity =
            builder(attack.add("att-named"), Boolean.class).defaultValue(true).build();

    @ApiStatus.Experimental
    public final FlagRef teamMate = builder(attack.add("att-teammate"), Boolean.class)
            .defaultValue(true)
            .build();

    public final FlagRef hostile =
            builder(attack.add("att-hostile"), Boolean.class).defaultValue(true).build();

    public final FlagRef invulnerable =
            flagBuilder(attack.add("att-invulnerable")).build();

    public final FlagRef multiplyBackward =
            flagBuilder(attack.add("opposite-attack-multiply")).build();

    public final DoubleRef multiplyPlayer = builder(attack.add("player-attack-multiply"), DoubleRef.TYPE)
            .defaultValue(0.0D)
            .show(this.multiplyBackward::get)
            .build();

    public final FlagRef fakePlayerDetect =
            flagBuilder(attack.add("fake-player-and-npc-detect")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerCommandBootstrap(this::onFriendCommandBootstrap);
    }

    public final FileStorage fileStorage = FileManager.getInstance().getInternalStorage("friends.nbt");
    public static final String KEY_FRIENDS = "friend-list";

    @Getter
    public FriendListStorage playerList = new FriendListStorage(List.of(), Map.of());

    {
        var path = attack.add(KEY_FRIENDS);
        if (path.getConfig().contains(path.toPath())) {
            var re = path.getConfig().getList(path.toPath());
            path.getConfig().setValueNoNew(null, path.toPath());
            if (re != null) {
                onListChange(new FriendListStorage(re.get(), Map.of()));
            }
        }

        try {
            playerList = fileStorage.readOrThrow(FriendListStorage.CODEC);
        } catch (Throwable e) {
        }
    }

    public void onListChange(FriendListStorage strings) {
        playerList = strings;
        fileStorage.write(FriendListStorage.CODEC, playerList);
    }

    private static final double[] FALL_FLYING_EYE_HEIGHTS = {0.4D, 1.62D, 1.27D};
    private static final double[] STANDING_EYE_HEIGHTS = {1.62D, 1.27D, 0.4D};

    public DoubleStream getPotentialEyeHeights() {
        if (grimExpandEyeHeight.get()) {
            double scale = mc.player.getScale();
            if (mc.player.isFallFlying() || mc.player.isAutoSpinAttack() || mc.player.isSwimming()) {
                return DoubleStream.concat(
                        Arrays.stream(FALL_FLYING_EYE_HEIGHTS).map(s -> s * scale),
                        DoubleStream.of(mc.player.dimensions.eyeHeight()));
            }
            return DoubleStream.concat(
                    Arrays.stream(STANDING_EYE_HEIGHTS).map(s -> s * scale),
                    DoubleStream.of(mc.player.dimensions.eyeHeight()));
        }
        return DoubleStream.of(mc.player.dimensions.eyeHeight());
    }

    public boolean isWithinAttackRange(Vec3 pos, Entity entity) {
        return isWithinAttackRange(pos, entity.getBoundingBox(), CombatExtra.INSTANCE.getAttackAtTargetRange(entity));
    }

    public boolean isWithinAttackRange(Vec3 pos, AABB box, double range) {
        if (box.distanceToSqr(pos) > MathUtils.s2(range + 3 + mc.player.dimensions.eyeHeight())) {
            // filter all outofrange
            // optimize calculation
            return false;
        }
        return getPotentialEyeHeights()
                .mapToObj(s -> pos.add(0, s, 0))
                .anyMatch(ps -> box.distanceToSqr(ps) < MathUtils.s2(range));
    }

    public Vec3 getBestAttackEyePos(Vec3 pos, AABB box) {
        var poses = getPotentialEyeHeights().mapToObj(s -> pos.add(0, s, 0)).toList();
        Vec3 playerPos = pos.add(mc.player.getEyePosition().subtract(mc.player.position()));
        double s2 = box.distanceToSqr(playerPos);
        for (var pp : poses) {
            double s3 = box.distanceToSqr(pp);
            if (s3 < s2) {
                s2 = s3;
                playerPos = pp;
            }
        }
        return playerPos;
    }

    public Optional<Vec3> getBestAttackEyePos(Vec3 pos, AABB box, double range) {
        if (box.distanceToSqr(pos) > MathUtils.s2(range + 3 + mc.player.dimensions.eyeHeight())) {
            // filter all outofrange
            // optimize calculation
            return Optional.empty();
        }
        var poses = getPotentialEyeHeights().mapToObj(s -> pos.add(0, s, 0)).toList();
        Vec3 playerPos = pos.add(mc.player.getEyePosition().subtract(mc.player.position()));
        double s2 = box.distanceToSqr(playerPos);
        for (var pp : poses) {
            double s3 = box.distanceToSqr(pp);
            if (s3 < s2) {
                s2 = s3;
                playerPos = pp;
            }
        }
        if (MathUtils.s2(range) >= s2) {
            return Optional.of(playerPos);
        } else {
            return Optional.empty();
        }
    }

    public boolean onAddFriend() {
        if (mc.hitResult.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) mc.hitResult).getEntity() instanceof Player player
                && player != mc.player) {
            addFriend(player.getScoreboardName(), "");
            return true;
        }
        return false;
    }

    public void addFriend(String friends, String alias) {
        FriendListStorage friendList = playerList;
        if (friendList.contains(friends, alias)) {
            logI18NSub("Friends", "message.module.target-selector.friends.already-added", friends);
        } else {
            logI18NSub("Friends", "message.module.target-selector.friends.added", friends);
            friendList = friendList.withRemove(friends).withAdd(friends, alias);
            onListChange(friendList);
            addFriendSayMessage(friends);
        }
    }

    public void addFriendSayMessage(String friendName) {
        var strO = onAddSend.get();
        if (strO.isPresent()) {
            ChatTasks.sayMessage(strO.getValue().formatted(friendName), false);
        }
    }

    public void removeFriend(String friend) {
        FriendListStorage friendList = playerList;
        if (friendList.contains(friend)) {
            logI18NSub("Friends", "message.module.target-selector.friends.removed", friend);
            friendList = friendList.withRemove(friend);
            onListChange(friendList);
        } else {
            logI18NSub("Friends", "message.module.target-selector.friends.not-added", friend);
        }
    }

    public void openEditFriendsScreen() {
        List<Pair<String, String>> playerListCopy = playerList.toPairList();
        NBTRef<PrimitivePairList<String, String>> holder = new NBTRef<>(new PrimitivePairList<>(
                "widget.friend-list.friend-name",
                "widget.friend-list.friend-alias",
                NBTTypes.STRING_TYPE,
                NBTTypes.STRING_TYPE,
                playerListCopy));
        var keyValue = holder.createKeyValue("");
        DrawableWidget drawableWidget = keyValue.generateValueWidget(0, 0, 300, 20);
        // open edit screen
        keyValue.addListener((pairList) -> onListChange(FriendListStorage.create(pairList.list())));

        drawableWidget.mouseClicked(150, 10, 0);
    }

    public boolean canAttack(Entity target) {
        if (mc.player == null) return false;
        // filter not vanilla targets
        if (target == null
                || target == mc.player
                || (target instanceof AbstractClientPlayer && !(target instanceof RemotePlayer))) {
            return false;
        }
        if (target instanceof LivingEntity lv && lv.getHealth() <= 0) {
            return false;
        }
        if (!whiteListTypes.get().test(target.getType())) {
            if (!passHostileCheck(target)) {
                return false;
            }
            // if it is not friendly to us, and attackHostile is enabled, then we can still attack them
        }

        if (!isNotFriend(target)) {
            return false;
        }

        if (!isNotTeamMate(target)) {
            return false;
        }
        if (!isNotInvulnerable(target)) {
            return false;
        }
        return true;
    }

    public boolean canAttackWithBow(Entity target) {
        return checkWeapon(target, true) && canAttack(target);
    }

    public boolean isInFriendList(Player e) {
        FriendListStorage list = playerList;
        if (list != null && list.contains(e.getScoreboardName())) {
            return true;
        }
        return false;
    }

    public boolean isNotFriend(Entity e) {
        if (e instanceof Player pl) {
            String name = pl.getScoreboardName();
            Regex regex = friendNameRegex.get();
            if (regex != null && regex.test(name)) {
                // friend
                return false;
            }
            if (!attackFriend.get()) {
                if (isInFriendList(pl)) {
                    return false;
                }
            }
            return true;
        } else {
            if (e.hasCustomName()) {
                if (attackNamedEntity.get()) {
                    Regex regex = friendNameRegex.get();
                    if (regex != null && regex.test(ChatUtils.textToString(e.getCustomName()))) {
                        return false;
                    }
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        }
    }

    private boolean isSameTeam(Player pl, EquipmentSlot slot) {
        ItemStack chestPlate = pl.getItemBySlot(slot);
        if (chestPlate.has(DataComponents.DYED_COLOR)) {
            ItemStack ourPlate = mc.player.getItemBySlot(slot);
            if (ourPlate.has(DataComponents.DYED_COLOR)) {
                if (Objects.equals(
                        chestPlate.get(DataComponents.DYED_COLOR), ourPlate.get(DataComponents.DYED_COLOR))) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isNotTeamMate(Entity e) {
        if (teamMate.get()) {
            if (e instanceof Player pl) {

                if (pl.getTeam() != null && pl.getTeam() == mc.player.getTeam()) {
                    PlayerTeam team = pl.getTeam();
                    // 过滤友伤
                    if (!team.isAllowFriendlyFire()) {
                        return false;
                    }
                }
                for (var re : EquipmentSlot.values()) {
                    if (isSameTeam(pl, re)) {
                        return false;
                    }
                }

                // more, consider colors of chestplates
                return true;
            }
        }
        return true;
    }

    public boolean isNotInvulnerable(Entity e) {
        if (!invulnerable.get()) {
            if (e instanceof Player pl) {
                // login players are invulnerable
                if (pl.getAttributeValue(Attributes.MOVEMENT_SPEED) < 1e-6) {
                    return false;
                }
                // creative players are invulnerable
                if (pl.isCreative()) {
                    return false;
                }
                // wtf
                if (pl.isInvulnerable()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean passHostileCheck(Entity e) {
        if (hostile.get()) {
            if (e instanceof NeutralMob anger) {
                long time = anger.getPersistentAngerEndTime();
                long currentTime = e.level().getGameTime();
                return currentTime < time;
            }
            return false;
        } else {
            return false;
        }
    }

    public List<Entity> getAttackableEntities(double nearbyOverride) {
        return getAttackableEntities(nearbyOverride, 0, this::canAttack);
    }

    public List<Entity> getAttackableEntities(double nearbyOverride, int ticks, Predicate<Entity> predicate) {
        List<Entity> entities = new ArrayList<>();
        List<Entity> et = ImmutableList.copyOf(mc.level.entitiesForRendering());
        for (var e : et) {
            if (predicate.test(e) && isTargetInRange(e, nearbyOverride, ticks)) {
                entities.add(e);
            }
        }
        return entities;
    }

    public boolean checkWeapon(Entity e, boolean commonBow) {
        return (!commonBow || (!(e instanceof EnderMan) && !(e instanceof Shulker)));
    }

    public List<Entity> getAimableEntities(boolean commonBow) {
        return getAimableEntities(commonBow ? this::canAttackWithBow : this::canAttack);
    }

    public List<Entity> getAimableEntities(Predicate<Entity> predicate) {
        List<Entity> entities = new ArrayList<>();
        List<Entity> et = ImmutableList.copyOf(mc.level.entitiesForRendering());
        for (var e : et) {
            // 普通弹射物， 无法攻击末影人和贝壳， 过滤掉
            if (predicate.test(e) && canPlayerDirectlySee(e)) {
                entities.add(e);
            }
        }
        return entities;
    }

    public boolean isTargetInRange(Entity e, double nearby, int ticks) {
        if (mc.player == null) return false;
        nearby = Math.max(nearby, CombatExtra.INSTANCE.getAttackAtTargetRange(e));
        Vec3 predictedPlayerPos =
                mc.player.position().add(mc.player.getDeltaMovement().scale(mc.player.isFallFlying() ? ticks : 0));
        return isWithinAttackRange(predictedPlayerPos, e.getBoundingBox(), nearby);
    }

    public Entity searchAttackEntity(double nearby, boolean autoSelect) {
        return searchAttackEntity(nearby, autoSelect, 0);
    }

    public Entity searchAttackEntity(double nearby, boolean autoSelect, int tickPredict) {
        return searchAttackEntity(nearby, autoSelect, tickPredict, null);
    }

    public Entity searchAttackEntity(double nearby, boolean autoSelect, Predicate<Entity> predicate) {
        return searchAttackEntity(nearby, autoSelect, 0, predicate);
    }

    public Entity searchAttackEntity(double nearby, boolean autoSelect, int tickPredict, Predicate<Entity> predicate) {
        Predicate<Entity> combinedPredicate =
                predicate != null ? (e) -> canAttack(e) && predicate.test(e) : this::canAttack;
        return searchAttack(nearby, autoSelect, tickPredict, combinedPredicate);
    }

    public Entity searchAttack(
            double nearby, boolean autoSelect, int tickPredict, Predicate<Entity> combinedPredicate) {
        if (mc.player == null) return null;
        // when tp reach, also attack the targeted entity first

        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity entityCheck = ((EntityHitResult) mc.hitResult).getEntity();
            // fix: check attackable when not auto
            if (!autoSelect || combinedPredicate.test(entityCheck)) {
                return entityCheck;
            }
        }
        HitResult result = RaycastUtils.createEntityOnlyCrossHairResult(mc.player, nearby, 1.0F, combinedPredicate);
        if (result != null && result.getType() == HitResult.Type.ENTITY) {
            // focusing entity， attack
            // should respect whitelist
            if (combinedPredicate.test(((EntityHitResult) result).getEntity())) {
                //                    mc.gameMode.attackEntity(mc.player,
                // ((EntityHitResult)result).getEntity());
                //                    mc.player.swingHand(Hand.MAIN_HAND);
                //                    handleShieldPredict(mc.player.getPitch(), mc.player.getYaw());
                return ((EntityHitResult) result).getEntity();
            }
        }
        // this use mc.crosshairTarget; if hand ...
        if (!autoSelect
                && mc.hitResult.getType() == HitResult.Type.BLOCK
                && CombatTasks.notSuitableForAttack(mc.player.getMainHandItem())) {
            // stop if player only want to mine a block
            return null;
        }
        List<Entity> targets = getAttackableEntities(nearby, tickPredict, combinedPredicate);
        // Debug.info(pos);
        // fixed: if player is targeting a faraway entity, then it should be privileged
        // fixed: should not target entity at back of me, because some anticheat place fake players to test killarua;
        // use weighted value
        targets.sort(Comparator.comparingDouble(e -> {
            return getEntityWeight(e, mc.player);
        }));
        if (!targets.isEmpty()) {
            return targets.get(0);
        }
        return null;
    }

    public Entity searchAimableEntity(boolean commonBow) {
        return searchAimableEntity(commonBow, null);
    }

    public Entity searchAimableEntity(boolean commonBow, Predicate<Entity> predicate) {
        Predicate<Entity> originPredicate = commonBow ? this::canAttackWithBow : this::canAttack;
        Predicate<Entity> combinedPredicate =
                predicate != null ? (e) -> originPredicate.test(e) && predicate.test(e) : originPredicate;
        return searchAimable(combinedPredicate);
    }

    public Entity searchAimable(Predicate<Entity> combinedPredicate) {
        if (mc.player == null) return null;
        // when tp reach, also attack the targeted entity first

        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) mc.hitResult).getEntity();
            if (combinedPredicate.test(entity)) {
                return entity;
            }
        }
        // 25格子之内的瞄准

        HitResult result = RaycastUtils.createEntityOnlyCrossHairResult(mc.player, 25, 1.0F, combinedPredicate);
        if (result != null && result.getType() == HitResult.Type.ENTITY) {
            // focusing entity， attack
            // should respect whitelist
            return ((EntityHitResult) result).getEntity();
        }
        List<Entity> targets = getAimableEntities(combinedPredicate);
        // filter raycast
        // 考虑夹角
        Vec3 vec3d = mc.player.getEyePosition();
        Vec3 playerRotation = mc.player.getLookAngle().normalize();
        // 通过
        if (targets.isEmpty()) return null;
        // 通过视角偏差
        targets.sort(Comparator.comparingDouble(
                e -> -e.getEyePosition().subtract(vec3d).normalize().dot(playerRotation)));
        // Debug.info(pos);
        return targets.get(0);
    }

    private double getEntityWeight(Entity e, Player player) {
        Vec3 vec3d = player.getEyePosition();
        Vec3 eye = player.getLookAngle().normalize();
        var pos = e.position().subtract(vec3d).normalize(); // .dotProduct(eye))
        double horizontalMultiply = (pos.x * eye.x + pos.z * eye.z);
        if (multiplyBackward.get()) {
            if (horizontalMultiply >= 0) {
                return -((horizontalMultiply / ((e.position().subtract(vec3d).horizontalDistance() + 1E-10)))
                        + (e instanceof Player ? multiplyPlayer.get() : 0.0D));
            } else {
                // rotate
                double horizontalNormalize = horizontalMultiply / (pos.length() * eye.length() + 1E-10);
                return -(horizontalNormalize + (e instanceof Player ? multiplyPlayer.get() : 0.0D));
            }
        } else {
            return -(Math.abs((horizontalMultiply)
                            / ((e.position().subtract(vec3d).horizontalDistance() + 1E-10)))
                    + (e instanceof Player ? multiplyPlayer.get() : 0.0D));
        }
    }

    //    private double withMultiply(Entity e, double v) {
    //        return Math.abs(v)
    //                - ((v < 0.0) ? multiplyBackward.get() : 0.0D)
    //                + (e instanceof Player ? multiplyPlayer.get() : 0.0D);
    //    }

    public static boolean canPlayerDirectlySee(Entity entity) {
        // 横向距离小于300
        return entity.position().subtract(mc.player.position()).horizontalDistanceSqr() < 90000
                && !RaycastUtils.raycastAnySolidBlock(mc.player, mc.player.getEyePosition(), entity.getEyePosition());
    }

    private void onFriendCommandBootstrap(MainCommand mainCommand) {
        TreeSubCommand main = mainCommand.mainBuilder().name("friends_command").build();
        {
            main.subBuilder(SubCommand.treeBuilder())
                    .name("friends")
                    .post(m -> m.subBuilder(SubCommand.taskBuilder())
                            .name("list")
                            .helper("message.command.friends_command.friends.list.help")
                            .post(e -> e.executor(CommandContext.run(() -> {
                                Debug.chat(Component.literal("== 当前好友列表 ==").withStyle(ChatFormatting.GREEN));
                                for (var re : playerList.friends()) {
                                    Debug.chat(re);
                                }
                            })))
                            .complete()
                            .subBuilder(SubCommand.taskBuilder())
                            .name("add")
                            .helper("message.command.friends_command.friends.add.help")
                            .arg(me.matl114.utils.commands.params.SimpleCommandArgs.argumentBuilder()
                                    .name("name")
                                    .tabSupplier(WorldUtils::getPlayerListNames)
                                    .build())
                            .arg(SimpleCommandArgs.argumentBuilder()
                                    .name("alias")
                                    .select("<请输入别名>")
                                    .defaultValue("")
                                    .build())
                            .post(e -> e.executor(CommandContext.run((Consumer<ArgumentInputStream>)
                                    (arg) -> this.addFriend(arg.nextNonnullString(), arg.nextArg()))))
                            .complete()
                            .subBuilder(SubCommand.taskBuilder())
                            .name("remove")
                            .helper("message.command.friends_command.friends.remove.help")
                            .arg(SimpleCommandArgs.argumentBuilder()
                                    .name("name")
                                    .tabSupplier(() -> playerList.stream())
                                    .build())
                            .post(e -> e.executor(CommandContext.run((arg) -> {
                                this.removeFriend(arg.nextNonnullString());
                            })))
                            .complete()
                            .subBuilder(SubCommand.taskBuilder())
                            .name("gui")
                            .helper("message.command.friends_command.friends.gui.help")
                            .post(e -> e.executor(CommandContext.run(this::openEditFriendsScreen)))
                            .complete())
                    .complete();
        }
    }

    @Override
    public void addCustomWidgets(Consumer<DrawableWidget> acceptor, int dx, int dy, int dblank) {
        acceptor.accept(createExecuteButton(
                "widget.friend-list.edit-friend-list",
                ButtonAction.run(this::openEditFriendsScreen),
                0,
                dblank,
                dx,
                dy));
        acceptor.accept(createTitle("widget.friend-list.command", 0, dblank, dx, dy));
    }

    public static record FriendListStorage(List<String> friends, Map<String, String> alias) {
        public static Codec<FriendListStorage> CODEC = RecordCodecBuilder.create(oinstance -> oinstance
                .group(
                        Codec.list(Codec.STRING).fieldOf("friend-list").forGetter(FriendListStorage::friends),
                        Codec.unboundedMap(Codec.STRING, Codec.STRING)
                                .optionalFieldOf("friend-alias", Map.of())
                                .forGetter(FriendListStorage::alias))
                .apply(oinstance, FriendListStorage::new));

        public String getFriendAlias(String friendName) {
            return alias.getOrDefault(friendName, "F");
        }

        public static FriendListStorage create(List<Pair<String, String>> data) {
            Map<String, String> mapData = new LinkedHashMap<>();
            for (var re : data) {
                if (!re.getSecond().trim().isEmpty()) {
                    mapData.put(re.getFirst(), re.getSecond().trim());
                }
            }
            return new FriendListStorage(data.stream().map(Pair::getFirst).toList(), mapData);
        }

        public boolean contains(String friendName) {
            return friends.contains(friendName);
        }

        public boolean contains(String friendName, @Nullable String alias) {
            return friends.contains(friendName)
                    && Objects.equals(
                            (alias == null || alias.trim().isEmpty()) ? null : alias.trim(),
                            this.alias.get(friendName));
        }

        public FriendListStorage withAdd(String friendName, @Nullable String alias) {
            if (contains(friendName, alias)) {
                return this;
            } else {
                List<String> str = new ArrayList<>(friends);
                str.add(friendName);
                Map<String, String> mapData = this.alias;
                if (alias != null && !alias.trim().isEmpty()) {
                    mapData = new LinkedHashMap<>(mapData);
                    mapData.put(friendName, alias);
                }
                return new FriendListStorage(str, mapData);
            }
        }

        public FriendListStorage withRemove(String friendName) {
            if (contains(friendName)) {
                List<String> str = new ArrayList<>(friends);
                str.remove(friendName);
                Map<String, String> mapData = this.alias;

                if (this.alias.containsKey(friendName)) {
                    mapData = new LinkedHashMap<>(mapData);
                    mapData.remove(friendName);
                }
                return new FriendListStorage(str, mapData);
            } else {
                return this;
            }
        }

        public List<Pair<String, String>> toPairList() {
            List<Pair<String, String>> pairList = new ArrayList<>();
            for (var re : friends) {
                pairList.add(Pair.of(re, alias.getOrDefault(re, "")));
            }
            return pairList;
        }

        public Stream<String> stream() {
            return friends.stream();
        }
    }
}
