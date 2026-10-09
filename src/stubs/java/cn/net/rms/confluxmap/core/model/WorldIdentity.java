package cn.net.rms.confluxmap.core.model;

/**
 * conflux-map 的世界标识（编译期桩，不参与打包）。
 * SessionGuard.Session 的 record 组件用到它，本模块只关心 dimension()，不读它的任何成员。
 */
public final class WorldIdentity {
    public static final WorldIdentity NONE = new WorldIdentity("", "");

    private final String serverId;
    private final String worldId;

    public WorldIdentity(String serverId, String worldId) {
        this.serverId = serverId;
        this.worldId = worldId;
    }

    public String serverId() {
        return serverId;
    }

    public String worldId() {
        return worldId;
    }

    public boolean isPresent() {
        return !worldId.isEmpty();
    }
}
