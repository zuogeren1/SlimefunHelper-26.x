package me.matl114.managers.config;

/**
 * 数值型配置引用的公共基类：统一「按 Number 取值 / 赋值」的跨类型拷贝语义。
 *
 * <p>不同数值类型之间（如 int 配置与 double 配置）互相拷贝时，调用方只关心数值本身，
 * 因此把 {@code getAsPrimitive} / {@code isSameTypeWith} / {@code copyValueFrom} 收在这里，
 * 子类只需实现 {@link #setFromNumber(Number)}。
 */
public abstract class NumberRef<T extends Number> extends Ref<T> {
    @Override
    public final Object getAsPrimitive() {
        return this.getValue();
    }

    @Override
    public final <W> boolean isSameTypeWith(Ref<W> ref) {
        return ref.getClass() == this.getClass();
    }

    public abstract void setFromNumber(Number number);

    @Override
    public abstract T getValue();

    @Override
    public abstract void setValue(T value);

    @Override
    public final <W> boolean copyValueFrom(Ref<W> otherRef) {
        if (otherRef instanceof NumberRef<?> db) {
            this.setFromNumber(db.getValue());
            return true;
        }
        return false;
    }
}
