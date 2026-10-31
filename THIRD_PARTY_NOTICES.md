# 第三方代码声明 / Third-Party Notices

本模组（Ammo Supply Box）自身以 MIT 协议发布。以下为本仓库中**包含或移植自**第三方项目的部分，
按各自协议的要求在此声明版权与许可。

---

## Ritchie's Projectile Library (RPL)

- 项目地址：<https://github.com/Wagers-of-Industrial-Warfare/RitchiesProjectileLib>
- 作者：rbasamoyai / Wagers-of-Industrial-Warfare
- 许可：**MIT License**（经 Modrinth API 与仓库 `LICENSE` 文件双重确认）
- 本仓库中使用范围：**摄像机震颤（screen shake）的弹簧-阻尼数学模型**
  - 文件：`src/main/java/com/koei/ammo_supply_box/client/CameraShakeHandler.java`
    （移植自 RPL 的 `ModScreenShakeHandler`）
  - 文件：`src/main/java/com/koei/ammo_supply_box/entity/AirdropShakeEntity.java`
    （参数载体实体，RPL 中对应 `ScreenShakeEffect` 的数据语义）
  - 改动：参数体系（刚度/阻尼/上限）、距离衰减公式、触发通道（用同步实体替代自定义网络包）
    均由本模组自行设计，未照搬 RPL 的包与配置体系。

### MIT License

```
MIT License

Copyright (c) 2023 Wagers-of-Industrial-Warfare

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 关于 Create: Big Cannons / L_Ender's Cataclysm

这两个项目只作为**本地参考**放在 `extralibs/`（已被 `.gitignore` 排除，不会上传）：

- **Create: Big Cannons**（MIT）：其炮击震颤同样调用 RPL，本模组未从中复制代码。
  但它帮助我们确认了 RPL 的存在与许可。
- **L_Ender's Cataclysm**（All Rights Reserved）：本模组**曾经**的震颤实现参考了它的
  `ScreenShake_Entity` / `ClientEvent`。该实现在迁移到 RPL 的 MIT 方案后**已被完全替换并删除**，
  当前代码中不再包含任何源自该模组的内容。
