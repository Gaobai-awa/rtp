# Random Teleport (RTP)

Minecraft 1.20.1 Fabric 模组 — 随机传送到地表安全位置。

## 命令

| 命令 | 描述 |
|---|---|
| `/rtp` | 随机传送到半径 3000 格内的地表安全位置，带 3 秒倒计时 |

## 功能

- **地表检测**：保证传送到安全的地面位置，不会被方块卡住或传送到地下洞穴
- **3 秒倒计时**：屏幕中央显示 Title 提示"传送中... / 请保持原地不动"，移动则传送取消
- **螺旋粒子**：倒计时期间 END_ROD + ENCHANTED_HIT 粒子环绕玩家上升
- **传送完成**：落地后播放粒子爆发，并显示传送成功 Title

## 配置

文件：`config/rtp/config.json`

```json
{
  "maxRadius": 3000,
  "minY": -64,
  "maxY": 320,
  "searchAttempts": 100
}
```

| 字段 | 说明 | 默认值 |
|---|---|---|
| `maxRadius` | 传送半径（格） | 3000 |
| `minY` | 搜索起始 Y 轴最低值 | -64 |
| `maxY` | 搜索起始 Y 轴最高值 | 320 |
| `searchAttempts` | 每次搜索的最大尝试次数 | 100 |

## 环境

- Minecraft 1.20.1
- Fabric Loader 0.15+
- Java 17+

## 构建

```bash
./gradlew build
```

jar 文件输出到 `build/libs/`。

## 作者

Gaobaiawa
