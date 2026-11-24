package me.matl114.hacks.modules.combat;

import java.util.List;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.StringFormat;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.hacks.utils.DamageUtils;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.player.Player;

public class CombatLog extends BaseModule {
    public CombatLog() {
        super("CombatLog");
        bindFlag(enable);
    }

    public ModulePath combatInfo = makePath(Configs.COMBAT_CONFIG, "combat-info");

    public ModulePath combatLog = combatInfo.add("combat-log");
    public final FlagRef enable = flagBuilder(combatLog.addEnable()).build();
    public final FlagRef enableMeHitOther =
            flagBuilder(combatLog.add("log-me-hit-other")).build();
    public final FlagRef enableOtherHitMe =
            flagBuilder(combatLog.add("log-other-hit-me")).build();
    public final FlagRef enableHit = flagBuilder(combatLog.add("log-hit")).build();

    public final NBTRef<StringFormat> logFormat = builder(combatLog.add("log-hit-format"), StringFormat.class)
            .defaultValue(new StringFormat(
                    List.of("attacker", "target", "damageType"), "{attacker} hit {target}, type: {damageType}", true))
            .build();

    public final FlagRef enableSmash = flagBuilder(combatLog.add("log-smash")).build();

    public final NBTRef<StringFormat> logSmashFormat = builder(combatLog.add("log-smash-format"), StringFormat.class)
            .defaultValue(new StringFormat(List.of("attacker", "target"), "{attacker} smash {target}", true))
            .build();

    public final FlagRef enableKinetic =
            flagBuilder(combatLog.add("log-kinetic")).build();

    public final NBTRef<StringFormat> logKineticFormat = builder(
                    combatLog.add("log-kinetic-format"), StringFormat.class)
            .defaultValue(new StringFormat(List.of("attacker", "target"), "{attacker} spear {target}", true))
            .build();

    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundDamageEventPacket.class), this::onEntityDamage);
    }

    public void onEntityDamage(Event<ClientboundDamageEventPacket> e) {
        if (checkNull()) return;
        if (enable.get()
                && e.context.sourceCauseId() == mc.player.getId()
                && mc.level.getEntity(e.context.entityId()) instanceof Player otherPlayer
                && enableMeHitOther.get()) {
            var source = e.context.sourceType().unwrapKey().orElse(null);
            logDamage("you", otherPlayer.getScoreboardName(), source);
        } else if (enable.get()
                && e.context.entityId() == mc.player.getId()
                && mc.level.getEntity(e.context.sourceCauseId()) instanceof Player otherPlayer
                && enableOtherHitMe.get()) {
            var source = e.context.sourceType().unwrapKey().orElse(null);
            logDamage(otherPlayer.getScoreboardName(), "you", source);
        }
    }

    public void logDamage(String from, String to, ResourceKey<DamageType> source) {
        if (enableSmash.get()) {
            if (DamageUtils.isType(source, "mace_smash")) {
                // we trigger a mace smash
                log(logSmashFormat.get().formatText(from, to));
                return;
            }
        }
        if (enableKinetic.get()) {
            if (DamageUtils.isType(source, "spear")) {
                // we trigger a mace smash
                log(logKineticFormat.get().formatText(from, to));
                return;
            }
        }
        if (enableHit.get()) {
            log(logFormat
                    .get()
                    .formatText(
                            from,
                            to,
                            source == null ? "null" : source.identifier().getPath()));
            return;
        }
    }
}
