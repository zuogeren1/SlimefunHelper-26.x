package me.matl114.hooks;

import java.util.ArrayList;
import lombok.Getter;
import me.matl114.events.annotations.Broadcast;
import me.matl114.events.annotations.ExtraArgs;
import me.matl114.events.channels.EventChannel;
import me.matl114.hooks.impl.xaeroplus.IMapDrawFactory;
import me.matl114.hooks.impl.xaeroplus.impl.MapDrawFactoryImpl;
import me.matl114.hooks.impl.xaerowaypoints.IXWaypointFactory;
import me.matl114.hooks.impl.xaerowaypoints.impl.XaeroWaypointFactoryImpl;
import me.matl114.hooks.impl.xaeroworldmap.MapClickContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.map.WorldMap;
import xaero.map.gui.GuiMap;
import xaero.minimap.XaeroMinimap;
import xaeroplus.Globals;

public class XaeroHooks implements IHooks {
    public static final XaeroHooks INSTANCE = new XaeroHooks();

    public static XaeroHooks getInstance() {
        return INSTANCE;
    }

    boolean enable;
    XaeroWorldMapHooks worldMapHooks;
    XaeroPlusHooks plusHooks;
    XaeroMiniMapHooks minimap;
    /** 构造期快照：真实是否装了 Xaero 小地图，见 {@link #isXaeroMiniMapEnable()} */
    boolean miniMapInstalled;

    @Getter
    @Broadcast
    @ExtraArgs({ResourceKey.class, BlockPos.class})
    private static final EventChannel<ArrayList<MapClickContext>> worldMapRightClickOption = new EventChannel<>();

    public XaeroHooks() {
        try {
            worldMapHooks = new XaeroWorldMapImpl();
        } catch (Throwable e) {
            worldMapHooks = new XaeroWorldMapHooks();
        }
        try {
            plusHooks = new XaeroPlusImpl();
        } catch (Throwable e) {
            plusHooks = new XaeroPlusHooks();
        }
        try {
            minimap = new XaeroMiniMapImpl();
        } catch (Throwable e) {
            minimap = new XaeroMiniMapHooks();
        }
        // 只在这里取一次：路径点探针会把 minimap 换成一个恒说自己启用的桩，
        // 之后再实时问「装没装」就会被那个桩带着答「装了」
        miniMapInstalled = minimap.isEnabled();
        enable = isXaeroPlusEnable() || miniMapInstalled || isXaeroWorldMapEnable();
    }

    public boolean isXaeroWorldMapEnable() {
        return worldMapHooks.isEnabled();
    }

    /**
     * 真实是否安装了 Xaero 小地图。
     *
     * <p>玩家能看到的判断（界面上的「已检测到」提示）只认它，所以取构造期快照；
     * 运行期 {@code minimap} 可能已经被换成路径点探针的桩，问它等于问「探针在不在」。
     */
    public boolean isXaeroMiniMapEnable() {
        return miniMapInstalled;
    }

    /**
     * 现在是「有没有可用的航点接收器」：可能是真 Xaero，也可能是接管它的探针。
     *
     * <p>航点提交与同步链路只认它 —— 探针在场时必须为真，否则 {@code XaeroHelper}
     * 不会把已提交的点挂到当前世界的 {@code access} 上，点就永远流不出来。
     */
    public boolean isXaeroWaypointSinkAvailable() {
        return minimap.isEnabled();
    }

    public boolean isXaeroPlusEnable() {
        return plusHooks.isEnabled();
    }

    @Override
    public boolean isEnabled() {
        return enable;
    }

    public boolean isGuiMap(Screen screen) {
        return worldMapHooks.isGuiMap(screen);
    }

    public IMapDrawFactory getMapDrawFactory() {
        return plusHooks.getMapDrawFactory();
    }

    public IXWaypointFactory getWaypointFactory() {
        return minimap.getWaypointFactory();
    }

    public static class XaeroWorldMapHooks {
        public boolean isEnabled() {
            return false;
        }

        public boolean isGuiMap(Screen screen) {
            return false;
        }
    }

    public static class XaeroWorldMapImpl extends XaeroWorldMapHooks {
        public XaeroWorldMapImpl() {
            Class<?> main = WorldMap.class;
        }

        public boolean isEnabled() {
            return true;
        }

        public boolean isGuiMap(Screen screen) {
            return screen instanceof GuiMap;
        }
    }

    public static class XaeroPlusHooks {
        public XaeroPlusHooks() {}

        public boolean isEnabled() {
            return false;
        }

        public IMapDrawFactory getMapDrawFactory() {
            return null;
        }
    }

    public static class XaeroPlusImpl extends XaeroPlusHooks {
        final IMapDrawFactory mapDrawFactory;

        public XaeroPlusImpl() {
            Class<?> main = Globals.class;
            this.mapDrawFactory = MapDrawFactoryImpl.INSTANCE;
        }

        public boolean isEnabled() {
            return true;
        }

        @Override
        public IMapDrawFactory getMapDrawFactory() {
            return mapDrawFactory;
        }
    }

    public static class XaeroMiniMapHooks {
        public XaeroMiniMapHooks() {}

        public boolean isEnabled() {
            return false;
        }

        public IXWaypointFactory getWaypointFactory() {
            return null;
        }
    }

    public static class XaeroMiniMapImpl extends XaeroMiniMapHooks {
        IXWaypointFactory waypointFactory;

        public XaeroMiniMapImpl() {
            Class<?> miniMap = XaeroMinimap.class;
            Class<?> access = BuiltInHudModules.class;
            waypointFactory = XaeroWaypointFactoryImpl.INSTANCE;
        }

        public boolean isEnabled() {
            return true;
        }

        @Override
        public IXWaypointFactory getWaypointFactory() {
            return waypointFactory;
        }
    }
}
