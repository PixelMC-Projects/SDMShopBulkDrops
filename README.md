# SDM Shop Bulk Drops

[中文](#中文) · [English](#english)

## 中文

把 SDM Shop 大额购买产生的大量溢出掉落物压缩成货物，减少瞬时实体数量。

### 功能

- 先填满主背包；预计会产生至少 128 个溢出实体时，将相同物品压缩存放。
- 只有买家能领取，可分次拾取，剩余货物继续保留。
- 黄色发光描边和悬浮数量，数量变化时实时更新。
- 货物不自然消失，可抵御火烧和爆炸，并在正常保存、重启后保留。
- 仅影响商店大额购买，不改变平常的掉落物。

### 使用

适用于 Minecraft **1.20.1 / Forge**，服务端和客户端均需安装。针对 **SDM Shop Rework 1.20.1-6.11-pre**。

达到条件后自动生效。管理员仍可用 `/kill @e[type=item]` 清理货物；掉入虚空也会销毁。

## English

Reduce item-entity bursts by compressing large SDM Shop purchase overflows into cargo drops.

### Features

- Fill the main inventory first, then compress matching items when a purchase would create at least 128 overflow entities.
- Only the buyer can collect cargo, in portions that fit their inventory.
- Show a yellow outline and a floating count that updates as the quantity changes.
- Keep cargo through normal saves and restarts, without natural despawning, fire damage, or explosion damage.
- Apply compression only to large shop purchases, leaving ordinary drops unchanged.

### Usage

For Minecraft **1.20.1 / Forge**. Install on both the server and clients. Designed for **SDM Shop Rework 1.20.1-6.11-pre**.

Compression activates automatically. Administrators can still remove cargo with `/kill @e[type=item]`; the void also destroys it.

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).

[AGPL-3.0-only](LICENSE) · [Minecraft linking permission](LICENSE-EXCEPTION.md).
保留版权与署名；分发修改版或使用修改版提供联网服务时，按许可证提供对应源码。
Retain copyright and attribution; provide corresponding source when distributing modifications or offering modified software over a network, as required by the license.
