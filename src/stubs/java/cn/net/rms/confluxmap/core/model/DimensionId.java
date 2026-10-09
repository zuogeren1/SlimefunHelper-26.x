package cn.net.rms.confluxmap.core.model;

/**
 * conflux-map 的维度标识（编译期桩，不参与打包）。
 * 真实 jar 中为 public final class，含 namespace() / path() / toString()。
 */
public final class DimensionId {
    public static final DimensionId OVERWORLD = new DimensionId("minecraft", "overworld");
    public static final DimensionId NETHER = new DimensionId("minecraft", "the_nether");
    public static final DimensionId END = new DimensionId("minecraft", "the_end");

    private final String namespace;
    private final String path;

    private DimensionId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static DimensionId of(String namespace, String path) {
        return new DimensionId(namespace, path);
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
