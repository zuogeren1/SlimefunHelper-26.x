package me.matl114.hacks.modules.combat;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.awt.Color;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;
import lombok.Getter;
import me.matl114.accessors.hacks.EntityInternalAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.hacks.utils.RayCastCalculator;
import me.matl114.managers.command.MainCommand;
import me.matl114.gui.basic.*;
import me.matl114.hacks.ChatTasks;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.*;
import me.matl114.hacks.utils.config.*;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hooks.MeteorHooks;
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
import me.matl114.utils.render.RenderCollector;
import me.matl114.versioned.api.VRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

public class TargetSelector extends BaseModule implements RayCastCalculator<Entity> {
    public static TargetSelector INSTANCE;
    private static final int FAKE_PLAYER_MOVEMENT_CHECK_TICKS = 10;

    private final Map<UUID, Integer> detectedFakePlayers = new HashMap<>();
    private final Deque<PlayerListJoin> lastJoinedPlayerListEntries = new ArrayDeque<>();
    private final Map<UUID, SuspectedFakePlayer> suspectedFakePlayers = new HashMap<>();
    private final RenderCollector<List<Vec3>> fakePlayerLineCollector = RenderCollectors.createLinesCollector();

    private record PlayerListJoin(PlayerInfo entry, int joinedAtAge) {}

    private record SuspectedFakePlayer(int spawnedAtAge) {}

    public final ModulePath attack = makePath(Configs.COMBAT_CONFIG, "attack");

    public TargetSelector() {
        super("TargetSelector");
        INSTANCE = this;
    }

    private void onLocalPlayerSpawn(Event<LocalPlayer> event) {
        clearFakePlayerTracking();
    }

    private void onPlayerListJoin(Event<PlayerInfo> event) {
        if (!fakePlayerDetect.get()) return;

        PlayerInfo entry = event.context;
        UUID playerId = VRecord.getId(entry.getProfile());
        if (mc.player != null && playerId.equals(mc.player.getUUID())) return;

        int currentAge = getLocalPlayerAge();
        evictExpiredPlayerListJoins(currentAge);
        lastJoinedPlayerListEntries.removeIf(join -> playerId.equals(getPlayerId(join.entry())));
        if (mc.level != null && mc.level.getEntity(playerId) instanceof Player) {
            suspectedFakePlayers.remove(playerId);
            detectedFakePlayers.remove(playerId);
            return;
        }

        lastJoinedPlayerListEntries.addLast(new PlayerListJoin(entry, currentAge));
    }

    private void onPlayerListExit(Event<PlayerInfo> event) {
        UUID playerId = VRecord.getId(event.context.getProfile());
        detectedFakePlayers.remove(playerId);
        suspectedFakePlayers.remove(playerId);
        lastJoinedPlayerListEntries.removeIf(join -> playerId.equals(getPlayerId(join.entry())));
    }

    private void onPlayerSpawn(Event<ClientboundAddEntityPacket> event) {
        ClientboundAddEntityPacket packet = event.context;
        if (packet.getType() != EntityTypes.PLAYER || !fakePlayerDetect.get()) return;

        UUID playerId = packet.getUUID();
        int currentAge = getLocalPlayerAge();
        evictExpiredPlayerListJoins(currentAge);
        PlayerListJoin matchingJoin = null;
        for (Iterator<PlayerListJoin> iterator = lastJoinedPlayerListEntries.iterator(); iterator.hasNext(); ) {
            PlayerListJoin join = iterator.next();
            if (playerId.equals(getPlayerId(join.entry()))) {
                matchingJoin = join;
                iterator.remove();
                break;
            }
        }
        if (matchingJoin == null || mc.player == null) return;

        detectedFakePlayers.remove(playerId);
        suspectedFakePlayers.remove(playerId);

        Vec3 spawnPosition = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        double maxDistance = fakePlayerDetectDistance.get();
        if (spawnPosition.distanceToSqr(mc.player.position()) > maxDistance * maxDistance) return;

        Vec3 offsetFromPlayer = spawnPosition.subtract(mc.player.position());
        Vec3 viewDirection = mc.player.getLookAngle();
        double horizontalViewDot = offsetFromPlayer.x * viewDirection.x + offsetFromPlayer.z * viewDirection.z;
        if (horizontalViewDot < 0) {
            markDetectedFakePlayer(playerId, currentAge);
        } else {
            suspectedFakePlayers.put(playerId, new SuspectedFakePlayer(currentAge));
        }
    }

    private UUID getPlayerId(PlayerInfo entry) {
        return VRecord.getId(entry.getProfile());
    }

    private int getLocalPlayerAge() {
        return mc.player == null ? 0 : EntityInternalAccess.of(mc.player).getLivingTicks();
    }

    private void markDetectedFakePlayer(UUID playerId, int detectedAtAge) {
        detectedFakePlayers.put(playerId, detectedAtAge);
    }

    private void evictExpiredPlayerListJoins(int currentAge) {
        int maxLiveTicks = fakePlayerMaxLiveTicks.get();
        while (!lastJoinedPlayerListEntries.isEmpty()
                && currentAge - lastJoinedPlayerListEntries.peekFirst().joinedAtAge() > maxLiveTicks) {
            lastJoinedPlayerListEntries.removeFirst();
        }
    }

    private void clearFakePlayerTracking() {
        detectedFakePlayers.clear();
        lastJoinedPlayerListEntries.clear();
        suspectedFakePlayers.clear();
    }

    private boolean isDetectedFakePlayer(Entity entity) {
        if (!fakePlayerDetect.get() || !(entity instanceof Player player)) return false;
        UUID playerId = player.getUUID();
        Integer detectedAtAge = detectedFakePlayers.get(playerId);
        if (detectedAtAge == null) return false;
        if (getLocalPlayerAge() - detectedAtAge > fakePlayerMaxLiveTicks.get()) {
            detectedFakePlayers.remove(playerId);
            return false;
        }
        return true;
    }

    private void updateRenderCollector() {
        fakePlayerLineCollector.clear();
        if (checkNull()) {
            clearFakePlayerTracking();
            return;
        }
        if (!fakePlayerDetect.get()) {
            clearFakePlayerTracking();
            return;
        }

        int currentAge = getLocalPlayerAge();
        evictExpiredPlayerListJoins(currentAge);
        detectedFakePlayers.entrySet().removeIf(entry -> currentAge - entry.getValue() > fakePlayerMaxLiveTicks.get());

        for (Iterator<Map.Entry<UUID, SuspectedFakePlayer>> iterator =
                        suspectedFakePlayers.entrySet().iterator();
                iterator.hasNext(); ) {
            Map.Entry<UUID, SuspectedFakePlayer> entry = iterator.next();
            UUID playerId = entry.getKey();
            SuspectedFakePlayer suspected = entry.getValue();
            int age = currentAge - suspected.spawnedAtAge();
            if (age > fakePlayerMaxLiveTicks.get()) {
                iterator.remove();
                continue;
            }

            Entity entity = mc.level.getEntity(playerId);
            if (entity instanceof Player player
                    && PositionPredict.INSTANCE
                                    .getPredictor(player)
                                    .getKnownDeltaMovement()
                                    .lengthSqr()
                            > 0.0D) {
                markDetectedFakePlayer(playerId, suspected.spawnedAtAge());
                iterator.remove();
            } else if (age >= FAKE_PLAYER_MOVEMENT_CHECK_TICKS) {
                iterator.remove();
            }
        }

        if (checkNull()) return;

        for (Player player : mc.level.players()) {
            if (!detectedFakePlayers.containsKey(player.getUUID())) continue;
            Vec3 head = player.position().add(0, player.getBbHeight(), 0);
            fakePlayerLineCollector.submit(List.of(head, head.add(0, 1, 0)), Color.RED.getRGB());
        }
    }

    private void onRenderFakePlayers(Event<Render3D> event) {
        if (checkNull() || !fakePlayerDetect.get()) return;

        RenderUtils.startDrawVirtual(event.context.stack());
        try {
            fakePlayerLineCollector.render3D(event.context.stack(), event.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(event.context.stack());
        }
    }

    public final FlagRef grimExpandEyeHeight =
            flagBuilder(attack.add("use-grim-expand-eye-height")).build();

    public final FlagRef grimInterpolateBox =
            flagBuilder(attack.add("use-grim-interpolation-box")).build();

    public final DoubleRef realMaxExtraDistance = doubleBuilder(attack.add("max-extra-distance-ignore-expanding"))
            .defaultValue(3.6D)
            .validator(Configs.doubleRange(1.0D, 10.0D))
            .build();

    public final NBTRef<EntityTypeEntrySet> whiteListTypes = builder(attack.add("whitelist"), EntityTypeEntrySet.class)
            .defaultValue(new EntityTypeEntrySet(new Regex("^(monster|!endermite|player)$")))
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

    public final FlagRef loadMeteorFriends =
            flagBuilder(attack.add("load-meteor-client-friends")).build();

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
            .build();

    public final FlagRef fakePlayerDetect =
            flagBuilder(attack.add("fake-player-and-npc-detect")).build();

    public final DoubleRef fakePlayerDetectDistance = doubleBuilder(attack.add("fake-player-detect-distance"))
            .defaultValue(12.0D)
            .validator(Configs.doubleRange(1.0D, 128.0D))
            .build();

    public final IntRef fakePlayerMaxLiveTicks = intBuilder(attack.add("fake-player-max-live-ticks"))
            .defaultValue(100)
            .validator(Configs.intRange(1, 1200))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerCommandBootstrap(this::onFriendCommandBootstrap);
        registerListener(Listener.getGameJoinPoint(), this::onLocalPlayerSpawn);
        registerListener(Listener.getPlayerRespawnPoint(), this::onLocalPlayerSpawn);
        registerListener(Listener.getOtherPlayerJoinPoint(), this::onPlayerListJoin);
        registerListener(Listener.getOtherPlayerExitPoint(), this::onPlayerListExit);
        registerListener(
                Listener.getPacketPostHandlePoint().getChannel(ClientboundAddEntityPacket.class), this::onPlayerSpawn);
        registerListener(Listener.getPostTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::onRenderFakePlayers);
    }

    public final FileStorage fileStorage = FileManager.getInstance().getInternalStorage("friends.nbt");
    public static final String KEY_FRIENDS = "friend-list";

    @Getter
    public FriendListStorage playerList = new FriendListStorage(List.of(), Map.of());

    {
        var path = attack.add(KEY_FRIENDS);
        if (path.getConfig().contains(path.toPath())) {
            var re = path.getConfig().getList(path.toPath());
            path.getConfig().setValue(null, path.toPath());
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

    @Override
    public AABB getEffectiveBox(Entity entity) {
        if (entity instanceof Player && !(entity instanceof RemotePlayer)) {
            return entity.getBoundingBox();
        }
        if (grimInterpolateBox.get() && entity instanceof LivingEntity) {
            return CombatExtra.INSTANCE.getTracker(entity).getInteractBox(entity);
        }
        return entity.getBoundingBox();
    }

    public AABB getEffectiveBox(Entity entity, int ticksLater) {
        if (ticksLater <= 0 || entity instanceof Player && !(entity instanceof RemotePlayer)) {
            return getEffectiveBox(entity);
        }
        if (grimInterpolateBox.get() && entity instanceof LivingEntity) {
            return CombatExtra.INSTANCE
                    .getTracker(entity)
                    .getNextInterpolatedBox(entity, ticksLater, CombatExtra.INSTANCE.maySkipInterpolationTick());
        }
        return grimInterpolateBox.get() ? entity.getBoundingBox() : getVanillaInterpolatedBox(entity, ticksLater);
    }

    private AABB getVanillaInterpolatedBox(Entity entity, int ticksLater) {
        InterpolationHandler interpolator = entity.getInterpolation();
        //#if MC >= 260300
        //$$ net.minecraft.core.PositionAndRotation targetRot = interpolator.target();
        //$$ if (targetRot == null || !interpolator.hasActiveInterpolation()) return entity.getBoundingBox();
        //$$ Vec3 target = targetRot.position();
        //$$ int remaining = 0;
        //$$ Vec3 lastPos = null;
        //$$ if (interpolator instanceof net.minecraft.world.entity.LinearInterpolationHandler linear) {
        //$$     remaining = linear.interpolationData.remainingSteps;
        //$$     lastPos = linear.lastPositionAndRotation.position();
        //$$ } else if (interpolator instanceof net.minecraft.world.entity.SteppedInterpolationHandler stepped) {
        //$$     remaining = Math.max(1, (int) Math.ceil(stepped.interpolationData.remainingTicks));
        //$$     lastPos = stepped.lastPositionAndRotation.position();
        //$$ }
        //#else
        if (interpolator == null || !interpolator.hasActiveInterpolation()) return entity.getBoundingBox();
        int remaining = interpolator.interpolationData.steps;
        Vec3 target = interpolator.position();
        Vec3 lastPos = interpolator.previousTickPosition;
        //#endif
        Vec3 pos = entity.position();
        if (lastPos != null) {
            Vec3 delta = pos.subtract(lastPos);
            if (entity.level().noCollision(entity, entity.dimensions.makeBoundingBox(target.add(delta)))) {
                target = target.add(delta);
            }
        }
        for (int i = 0; i < ticksLater && remaining > 0; i++, remaining--) {
            pos = pos.add(target.subtract(pos).scale(1.0 / remaining));
        }
        return entity.getBoundingBox().move(pos.subtract(entity.position()));
    }

    public Stream<Vec3> getPotentialEyeHeights(Vec3 pos) {
        return getPotentialEyeHeights().mapToObj(i -> pos.add(0, i, 0));
    }

    public boolean isWithinAttackRange(Vec3 pos, Entity entity) {
        // add real place check if more than 3
        Vec3 syncPos = PositionPredict.INSTANCE.getPredictor(entity).getCurrentSyncedPos();
        AABB syncBox = entity.dimensions.makeBoundingBox(syncPos);
        if (syncBox.distanceToSqr(pos.add(0, mc.player.getEyeHeight(), 0))
                >= MathUtils.s2(CombatExtra.INSTANCE.getAttackRangeAttribute() + realMaxExtraDistance.get())) {
            return false;
        }
        return isWithinAttackRange(pos, getEffectiveBox(entity), CombatExtra.INSTANCE.getAttackAtTargetRange(entity));
    }

    public boolean isWithinAttackRange(Vec3 pos, Entity entity, double range) {
        if (pos.distanceToSqr(entity.position())
                > MathUtils.s2(range
                        + realMaxExtraDistance.get()
                        + Math.max(entity.dimensions.height(), entity.dimensions.width()))) {
            return false;
        }
        if (!entity.trackingPosition().equals(Vec3.ZERO)) {
            Vec3 syncPos = PositionPredict.INSTANCE.getPredictor(entity).getCurrentSyncedPos();
            AABB syncBox = entity.dimensions.makeBoundingBox(syncPos);
            if (syncBox.distanceToSqr(pos.add(0, mc.player.getEyeHeight(), 0))
                    >= MathUtils.s2(range + realMaxExtraDistance.get())) {
                return false;
            }
        }
        return isWithinAttackRange(pos, getEffectiveBox(entity), range);
    }

    public boolean isWithinAttackRange(Vec3 pos, AABB box) {
        return isWithinAttackRange(pos, box, CombatExtra.INSTANCE.getAttackRange());
    }

    public boolean isWithinAttackRange(Vec3 pos, AABB box, double range) {
        if (box.distanceToSqr(pos.add(0, mc.player.getEyeHeight(), 0))
                >= MathUtils.s2(range + realMaxExtraDistance.get())) {
            // filter all outofrange
            // optimize calculation
            return false;
        }
        return getPotentialEyeHeights()
                .mapToObj(s -> pos.add(0, s, 0))
                .anyMatch(ps -> box.distanceToSqr(ps) < MathUtils.s2(range));
    }

    @Override
    public double getReachDistance(Entity val) {
        return CombatExtra.INSTANCE.getAttackAtTargetRange(val);
    }

    public Vec3 getBestAttackEyePos(Vec3 pos, Entity entity) {
        AABB box = getEffectiveBox(entity);
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

    public Vec3 getEntityRayCast(Vec3 pos, Entity entity) {
        return getRaycast(pos, entity);
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

    public void openEditFriendsScreen(ElementFactory factory) {
        List<Pair<String, String>> playerListCopy = playerList.toPairList();
        NBTRef<PrimitivePairList<String, String>> holder = new NBTRef<>(new PrimitivePairList<>(
                "widget.friend-list.friend-name",
                "widget.friend-list.friend-alias",
                NBTTypes.STRING_TYPE,
                NBTTypes.STRING_TYPE,
                playerListCopy));
        var keyValue = holder.createKeyValue("");
        DrawableWidget drawableWidget = keyValue.generateValueWidget(factory, 0, 0, 300, 20);
        // open edit screen
        keyValue.addListener((pairList) -> onListChange(FriendListStorage.create(pairList.list())));

        drawableWidget.onMouseClicked(150, 10, 0);
    }

    public boolean canAttack(Entity target) {
        if (mc.player == null) return false;
        // filter not vanilla targets
        if (target == null
                || target == mc.player
                || (target instanceof AbstractClientPlayer && !(target instanceof RemotePlayer))) {
            return false;
        }
        if (isDetectedFakePlayer(target)) return false;
        if (target instanceof LivingEntity lv && lv.getHealth() <= 0) {
            return false;
        }
        if (!whiteListTypes.get().test(target.getType())) {
            if (!passHostileCheck(target)) {
                return false;
            }
            // if it is not friendly to us, and attackHostile is enabled, then we can still attack them
        }

        if (!isNotFriendly(target)) {
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

    public boolean isFriend(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        FriendListStorage list = playerList;
        if (list != null && list.contains(playerName)) {
            return true;
        }
        if (loadMeteorFriends.get() && MeteorHooks.getInstance().isFriend(playerName)) {
            return true;
        }
        return false;
    }

    public boolean isInFriendList(Player e) {
        return isFriend(e.getScoreboardName());
    }

    @Deprecated
    public boolean isNotFriend(Entity e) {
        return isNotFriendly(e);
    }

    public boolean isNotFriendly(Entity e) {
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
                if (e instanceof EnderMan enderman) return enderman.isCreepy();
                if (e instanceof PolarBear bear) return bear.isStanding() || bear.isAggressive();
                if (e instanceof IronGolem || e instanceof ZombifiedPiglin) {
                    return ((Mob) e).isAggressive();
                }
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

    public List<Entity> getAttackableEntities(double nearbyOverride, int ticksLater, Predicate<Entity> predicate) {
        if (mc.level == null || mc.player == null) {
            return List.of();
        }
        List<Entity> entities = new ArrayList<>();
        for (var re : mc.level.entitiesForRendering()) {
            double range = Math.max(nearbyOverride, CombatExtra.INSTANCE.getAttackAtTargetRange(re));
            if (isWithinAttackRange(mc.player.position(), getEffectiveBox(re, ticksLater), range)
                    && predicate.test(re)) {
                entities.add(re);
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

    @Deprecated
    public boolean isTargetInRange(Entity e, double nearby, int ticks) {
        return isTargetInRange(e, nearby);
    }

    public boolean isTargetInRange(Entity e, double nearby) {
        if (mc.player == null) return false;
        nearby = Math.max(nearby, CombatExtra.INSTANCE.getAttackAtTargetRange(e));
        return isWithinAttackRange(mc.player.position(), e, nearby);
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
            if (!isDetectedFakePlayer(entityCheck) && (!autoSelect || combinedPredicate.test(entityCheck))) {
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

    public boolean isInFront(Entity entity) {
        Entity camera = mc.getCameraEntity();
        if (camera == null) return false;
        Vec3 cameraEyePos = camera.getEyePosition(1.0F);
        Vec3 viewDirection = camera.getViewVector(1.0F);
        Vec3 cameraToTarget = entity.getBoundingBox().getCenter().subtract(cameraEyePos);
        return viewDirection.dot(cameraToTarget) >= 0.0D;
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
                                Debug.chat(Component.translatable("message.command.friends_command.friends.list.title")
                                        .withStyle(ChatFormatting.GREEN));
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
                            .post(e -> e.executor(CommandContext.run(() -> openEditFriendsScreen(CONFIG_FACTORY))))
                            .complete())
                    .complete();
        }
    }

    @Override
    public void addCustomWidgets(
            Consumer<DrawableWidget> acceptor,
            int dx,
            int dy,
            int dblank,
            me.matl114.gui.interfaces.ElementFactory factory) {
        acceptor.accept(factory.createExecuteButton(
                "widget.friend-list.edit-friend-list",
                ButtonAction.run(() -> openEditFriendsScreen(factory)),
                0,
                dblank,
                dx,
                dy));
        acceptor.accept(factory.createTitle("widget.friend-list.command", 0, dblank, dx, dy));
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
