# 📦 UPDATE 0.1.1

- 🛠️ 适配 SlimeFunHelper 1.2.1.3（无功能变化）

# 📦 UPDATE 0.1.0

- ✨ 首个版本：SlimeFunHelper 的独立插件工程（`slimefunhelper-plus`），宿主通过 `slimefunhelper` entrypoint 发现
- ✨ Conflux 地图联动（从宿主迁入）
  - ✨ 全屏地图右键菜单追加「自定义指令 / 自定义补全 / 复制坐标」与固定的 `/!!travel to <坐标>`
  - ✨ 小地图与全屏地图渲染已加载区块边界（颜色可配）与旅行目标标记
  - ✨ 小地图缩放可设 0.1~8（每像素格数，0 = 跟随 conflux）
  - 🛠️ 配置迁到 `config/slimefunhelper-plus/conflux.yml`，旧 `survival.yml` 的值首次加载时自动迁移
- ✨ 定位栏坐标反推（从宿主迁入）
  - ✨ 用定位栏方位角 + 自身移动的视差反推看不见的玩家坐标，显示为名单里的 `~[x, z] d:距离 ±σ n=采样数` 行
  - ✨ 观测点最小二乘求解、σ 保守上界、换世界作废、旧值标注 `+Ns`、调试日志
  - 🛠️ 配置迁到 `config/slimefunhelper-plus/locator.yml`，旧 `render.yml` 的值首次加载时自动迁移
- 🛠️ 模块并入宿主模块树：Conflux 进 Survival 组、定位栏进 Render 组；另有只读视图组 `SlimeFunHelper+`
- 🛠️ 配置合并在 `config/slimefunhelper-plus/config.yml` 一份文件里，「全配置」页只显示一个 `SlimeFunHelper+`，
  下面分「Conflux 地图扩展」「定位栏反推」两段
