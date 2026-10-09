package cn.net.rms.confluxmap.core.task;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;

/**
 * conflux-map 的会话守卫（编译期桩，不参与打包）。
 * 真实 jar（META-INF/jars/common-x.y.z.jar）中 Session 为 public record。
 */
public final class SessionGuard {
    public record Session(long token, WorldIdentity world, DimensionId dimension) {
        public static final Session NONE = new Session(0L, WorldIdentity.NONE, DimensionId.OVERWORLD);

        public boolean active() {
            return world.isPresent();
        }
    }

    public SessionGuard() {}
}
