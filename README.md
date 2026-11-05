# In-Game Datapack Editor (IGDE)

Generate in-game datapack editing forms from Minecraft and modded codecs.

IGDE 希望让玩家直接在 Minecraft 中理解和编写数据包条目：从已有 `Codec` 提取结构、生成表单，并由服务端校验和保存。当前主要开发分支是 **`1.20.1`**，使用 **Minecraft 1.20.1 / Forge 47.2.0 / Java 17**，尚处于开发阶段。

## 设计方向

- **以 Codec 为依据。** 通过 Forge 的数据包注册表发现原版与模组提供的 Codec，尽量复用字段、可选项、枚举、分派类型和注册表信息，减少手工维护表单与数据格式的重复工作。
- **结构与界面分离。** 服务端将 Codec 转为 `DataSchema` 树，再序列化为 Schema JSON；客户端按类型创建记录、列表、映射、可选项、枚举等编辑节点。查看器与编辑器共享结构信息。
- **服务端负责最终校验。** 表单提供输入反馈，保存时仍使用目标注册表的原始 Codec 与服务器 `RegistryOps` 解码。反射推断出的表单不是完整的数据格式验证器。
- **渐进支持复杂结构。** 反射解析采用处理器链、缓存和递归防环；无法识别的结构回退为 `UNKNOWN`。优先保证已有输入不被静默改写，再逐步扩展 Codec 覆盖范围。

主要代码入口：

| 目录或类 | 职责 |
| --- | --- |
| `common/codec/editor/DatapackCodecs` | 发现可编辑的数据包注册表与 Codec |
| `common/codec/parser` | 解析 Codec，构建、输出 Schema |
| `client/gui/editor` | Schema 查看器、表单节点与 JSON 预览 |
| `common/codec/editor/CodecEditorManager` | 服务端 Schema 请求、数据校验与保存 |
| `common/network` | 客户端与服务端的请求和结果通信 |
| `common/command/IDECommand` | `/ide` 命令入口 |

## 开发与构建

先安装 JDK 17，并将 `JAVA_HOME` 指向该 JDK。项目使用 Gradle Wrapper 8.10.2，无需另外安装 Gradle。首次构建会下载 Forge、映射和其他依赖，需要可访问相应 Maven 仓库的网络。

```powershell
git clone --branch 1.20.1 https://github.com/HungTeen/In-Game-Datapack-Editor.git
cd In-Game-Datapack-Editor
.\gradlew.bat test build
.\gradlew.bat runClient
```

Linux/macOS 使用对应的 `./gradlew test build` 与 `./gradlew runClient`。构建产物位于 `build/libs/`，开发客户端的运行目录为 `run/`；开发服务器可用 `runServer` 启动。

默认依赖通过 CurseMaven 下载 [HTLib 1.2.0](https://www.curseforge.com/minecraft/mc-mods/hungteens-lib/files/6616773)，坐标为 `curse.maven:hungteens-lib-794622:6616773`，不再要求开发者使用固定的本机 Maven 路径。安装模组时，客户端和服务端均需安装兼容版本的 HTLib。

如需联调本地 HTLib，将 `htlib-1.20.1-1.2.0.jar` 放入一个目录，并显式覆盖依赖来源：

```powershell
.\gradlew.bat build -Phtlib_local_dir=C:/path/to/jars -Phtlib_local_version=1.2.0
```

## 游戏内使用

`/ide` 命令要求权限等级 2。单人测试世界请启用作弊；多人服务器使用拥有相应权限的账号。

| 命令 | 用途 |
| --- | --- |
| `/ide editor` | 打开编辑器，选择注册表并填写条目 |
| `/ide view [registry]` | 打开结构查看器，可预选注册表 |
| `/ide schema <registry>` | 在聊天中输出结构 |
| `/ide schema <registry> json` | 输出结构并导出 Schema JSON 到服务器 `schema_output/` |
| `/ide schema <registry> types` | 列出可枚举的 dispatch 类型；也支持 `/ide schema types <registry>` |
| `/ide schema <registry> type <type>` | 查看某个 dispatch 分支的结构 |

例如先用 `/ide view minecraft:worldgen/configured_feature` 查看结构，再通过 `/ide editor` 选择同一注册表。编辑器中的 JSON 模式用于**预览表单生成的内容**，目前不是可输入的 JSON 文本编辑器，也没有读取既有文件的导入入口。

保存时填写资源 ID，例如 `mypack:examples/test`。其命名空间 `mypack` 同时作为生成的数据包目录名；保存目标由服务端依据注册表与资源 ID 计算：

| 注册表 | 资源 ID | 世界目录下的保存位置 |
| --- | --- | --- |
| `minecraft:worldgen/configured_feature` | `mypack:examples/test` | `datapacks/mypack/data/mypack/worldgen/configured_feature/examples/test.json` |
| `opentd:effects` | `mypack:examples/test` | `datapacks/mypack/data/mypack/opentd/effects/examples/test.json` |

非 `minecraft` 注册表保留注册表命名空间目录，以匹配 Forge 的数据包目录规则。第二个示例需要安装提供该注册表的模组。

服务端检查玩家权限、注册表、资源 ID、JSON 语法以及 Codec 解码结果，拒绝不合法的数据。写入限定在世界的数据包目录内，并使用临时文件与原子替换；同一资源 ID 再次保存会覆盖对应 JSON。数据包缺少 `pack.mcmeta` 时自动创建，`pack_format` 为 15；已有元数据保留。界面显示服务端返回的保存结果，而不把请求已发送视为保存成功。

单次保存的 JSON 最多为 30,000 个 UTF-8 字节，资源 ID 最长 256 个字符；这些限制为 Minecraft 1.20.1 的网络消息预留了开销。网络协议已更新，客户端与服务端应使用同一版本的 IGDE。

保存成功只表示文件已写入。需要手动检查 `/datapack list`，必要时启用生成的数据包，再执行 `/reload` 并查看反馈。世界生成等在加载世界时读取的注册表，可能需要重新进入世界或重启服务器才能应用；不要仅凭保存成功判断游戏中的数据已经更新。

## 当前边界与后续方向

- 当前范围是通过 Forge 数据包注册表发现的条目；函数、配方、进度等并不因此自动获得通用编辑能力。
- Codec 反射依赖 DFU 与 Minecraft 的实现细节。复杂变换、递归结构、内联 Holder、`Either` 等尚未完整覆盖；`UNKNOWN` 也不代表已有可用表单。服务端校验会拦截无法解码的输出。
- 下一步优先补充典型 Codec 的解析与 JSON 往返回归用例，完善 Either、多态与默认值编码，避免新增控件改变原始数据语义。
- 在往返行为稳定后，增加既有条目导入、明确的未保存变更提示，以及适合不同注册表生命周期的应用反馈。

## 手工验证

自动测试与构建不能代替游戏内验证。可在测试世界执行以下检查：

1. 用 `/ide editor` 选择可编辑的注册表，填写合法条目和 `mypack:examples/test`，切换 JSON 预览，核对嵌套字段名、可选字段和数值。
2. 对可选字段分别测试缺省、启用和重新禁用；滚动、展开或切换 JSON 预览后，确认输入及缺省语义保持一致。
3. 保存合法条目，确认服务端返回成功，并核对文件位置和 `pack.mcmeta`。再次保存同一 ID，确认仅目标 JSON 被更新，已有元数据不变。
4. 输入非法资源 ID、超出范围的数值或无法解码的内容，确认获得明确失败反馈且原文件未被覆盖；无权限账号应无法请求编辑或保存。
5. 检查并启用数据包，手动 `/reload`，查看服务器日志与加载结果。对启动时加载的注册表，重新进入世界后验证实际内容。

## 实测记录（2026-11-04）

在 Minecraft 1.20.1 / Forge 47.2.0 / HTLib 1.2.0 测试世界中，已完成原版 `minecraft:damage_type` 的编辑、保存与加载验证：

- `/ide editor` 正常打开；`exhaustion` 输入 `abc` 时阻止保存且未生成文件，改为 `0.5` 后可保存。
- `message_id` 为 `igde_runtime`，`scaling` 为 `never`；可选 `effects` 启用后再禁用，生成的 JSON 正确省略该字段。
- `igde_test:sample` 保存至 `datapacks/igde_test/data/igde_test/damage_type/sample.json`。启用数据包并重新进入世界后，对带 `igde_test` 标签的猪执行以下命令，收到 `Applied 1.0 damage to Pig`，确认自定义伤害类型已被游戏加载。

```mcfunction
/damage @e[type=minecraft:pig,tag=igde_test,limit=1] 1 igde_test:sample
```

自动测试共 49 项：46 项通过，3 项因环境缺少符号链接创建权限而跳过。以上游戏内验证仅覆盖该环境下的原版伤害类型，不代表所有注册表均已通过测试。

最新客户端还完成了预选注册表回填、小窗口保存按钮及长状态提示换行的界面复测。同一资源 ID 覆盖保存后，JSON 更新为 `exhaustion: 0.75` 和 `message_id: "igde_runtime_updated"`，已有 `pack.mcmeta` 的 SHA-256 保持不变。游戏截图保存在开发运行目录 `run/screenshots/`。

## 许可证

项目使用 LGPL-2.1，详见 [LICENSE](LICENSE)。

