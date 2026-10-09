package cn.net.rms.confluxmap.mc.ui.screen;

import java.util.OptionalInt;

/**
 * conflux-map 的右键位置菜单（编译期桩，不参与打包）。
 * 真实 jar 中该类及其嵌套类型是**包级私有**的，这里声明为 public 只是为了 javac；
 * 运行期由 mixin 合并进目标类后以同包身份访问，因此本模块只依赖两版都有的成员：
 *   Bounds: public x() / y() / width() / height()，包私有 buttonX() / buttonY(int) / buttonWidth() / contains(double,double)
 *   Target: public blockX() / blockZ()，包私有 blockY()（语义：地面+1，未知则 empty）
 * 注意：0.1.7 的 Target 组件是 OptionalInt surfaceY，0.1.9 换成了 ColumnStore.SurfaceLookup ground，
 * 这里两个都不声明，避免绑定到任一版本。
 */
public class FullscreenMapLocationMenu {
    public static class Bounds {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        public Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        int buttonX() {
            return x;
        }

        int buttonY(int index) {
            return y;
        }

        int buttonWidth() {
            return width;
        }

        boolean contains(double mouseX, double mouseY) {
            return false;
        }
    }

    public static class Target {
        private final int blockX;
        private final int blockZ;

        public Target(int blockX, int blockZ) {
            this.blockX = blockX;
            this.blockZ = blockZ;
        }

        public int blockX() {
            return blockX;
        }

        public int blockZ() {
            return blockZ;
        }

        OptionalInt blockY() {
            return OptionalInt.empty();
        }
    }
}
