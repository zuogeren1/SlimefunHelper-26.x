package me.zuogeren.slimefunhelperplus.module.conflux.mixin;

import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.zuogeren.slimefunhelperplus.module.conflux.waypoints.SfhWaypointPersistence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 {@code SFH} 组的路径点"只写内存不写盘"。
 *
 * <p>conflux 的保存链路是 {@code WaypointService#saveSnapshot} → {@code WaypointIo.save(Path, State, Logger)}
 * （退出时的 {@code saveOutgoingSnapshot} 也走同一个方法），而 {@code State} 是保存那一刻的不可变快照。
 * 所以这里做两件事：
 * <ul>
 *   <li>把快照里的 {@code SFH} 组摘掉 —— 文件里就永远不会有这些点，等价于 Xaero 的
 *       {@code if (!w.isTemporary())}；</li>
 *   <li>过滤后内容没变就取消这次写盘 —— 只动 SFH 点的时候本来就不需要重写文件（conflux 是整文件重写）。</li>
 * </ul>
 *
 * <p>两条注入都是 {@code @Pseudo} + {@code require = 0}：conflux 不在、或将来改了
 * {@code WaypointIo} 的签名，就整块不生效，退回「写盘 + 启动清扫」的老行为，不会把游戏带崩。
 */
@Pseudo
@Environment(EnvType.CLIENT)
@Mixin(targets = "cn.net.rms.confluxmap.core.waypoint.WaypointIo")
public abstract class ConfluxWaypointSaveMixin {

    /**
     * 注入目标：{@code WaypointIo#saveChecked(Path, State)} —— 真正攒 {@code FileShape} 并写文件的地方。
     *
     * <p>不写描述符：{@code WaypointIo} 有两个 {@code save} 重载，而 {@code saveChecked} 只有这一个名字，
     * 用名字就够；带描述符的选择器在这个 Mixin 版本上匹配不到（实测 "could not find any targets"，
     * 即使描述符与 javap 逐字一致）。
     */
    @Unique
    private static final String slimefunhelper$SAVE = "saveChecked";

    /** 每个路径点文件上一次写盘时的「过滤后指纹」；键是文件绝对路径 */
    @Unique
    private static final Map<String, Long> slimefunhelper$lastSaved = new ConcurrentHashMap<>();

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean slimefunhelper$probeLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    @ModifyVariable(method = slimefunhelper$SAVE, at = @At("HEAD"), argsOnly = true, index = 1, require = 0)
    private static WaypointStore.State slimefunhelper$dropSfhGroup(WaypointStore.State state) {
        try {
            if (slimefunhelper$probeLogged.compareAndSet(false, true)) {
                me.matl114.utils.Debug.getLogger().info("[ConfluxMapHelper] waypoint save hook is active");
            }
            return SfhWaypointPersistence.filter(state);
        } catch (Throwable ignored) {
            // 过滤失败就按原样保存：宁可多写一组点，也不能让存档写入失败
            return state;
        }
    }

    @Inject(method = slimefunhelper$SAVE, at = @At("HEAD"), cancellable = true, require = 0)
    private static void slimefunhelper$skipUnchanged(
            Path file, WaypointStore.State state, CallbackInfo ci) {
        try {
            long fingerprint = SfhWaypointPersistence.fingerprint(state);
            Long previous = slimefunhelper$lastSaved.put(file.toString(), fingerprint);
            if (previous != null && previous.longValue() == fingerprint) {
                // 内容与上次写下去的完全一致（只可能是 SFH 组在变），这次重写纯属浪费
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
