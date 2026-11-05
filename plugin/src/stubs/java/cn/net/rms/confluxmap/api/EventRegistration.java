package cn.net.rms.confluxmap.api;

/**
 * 一次登记的句柄（编译期桩，不参与打包）。
 *
 * <p>真类：{@code public final class ... implements java.lang.AutoCloseable}，
 * 由 {@code MarkerApi#registerProvider} 返回；{@code close()} 幂等，重复调用无副作用（javap 实测）。
 */
public final class EventRegistration implements AutoCloseable {

    public static EventRegistration of(Runnable unregister) {
        return new EventRegistration();
    }

    private EventRegistration() {}

    @Override
    public synchronized void close() {}
}
