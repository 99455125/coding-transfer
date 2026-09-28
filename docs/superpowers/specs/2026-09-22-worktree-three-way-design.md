# Every Sync 4.3.0：HOST Change Set 与 VM 三方合并

## 来源及目标

延续用户提供的已确认交接方案及四个已有修改文件，不另行重设计。VM 是唯一 Git 权威端；HOST 是不提交的 AI 开发工作区。

HOST → VM 只发送当前分支未提交 Git 修改，允许 VM 已经 commit、pull、merge 或切换分支。不同内容必须合并或由用户解决，不能静默覆盖。

VM → HOST 保留现有权威状态同步：SNAPSHOT / DELTA / ACK、完整目标重建、refs 和基线机制均不改变。

## 当前接手状态

Model、Codec、ClipboardTransport 已部分加入 V3 WORKTREE、BASE 字节和来源分支；GitHistory 已加入 trackedChanges。SyncEngine 仍使用旧 baseline 差异与严格 HEAD 校验；冲突解决尚未接入。已有改动需要一起审查和测试，不视为已完成。

## HOST 生成

- 使用当前项目仓库 HEAD 到最终 Working Tree 的 tracked 修改集合，等价于 git diff HEAD --no-renames。
- 包含 staged 和 unstaged 的最终结果、新增已纳入索引的文件、修改和删除；rename 为 delete + add。
- 不包含普通 untracked、untracked ignored、.git、.vmsync 及插件 exclude。已跟踪但匹配 .gitignore 的文件仍属于 Git tracked 修改。
- BASE 来自发送时 HEAD，THEIRS 来自最终工作区。切换分支本身不算修改。
- 记录来源 HEAD 和 branch；detached branch 为空。生成前后复核 HEAD、分支、变更集合和文件内容，变化则拒绝本次生成。

## 传输协议

沿用已有 V3 初步实现：WORKTREE 条目携带 BASE 原文字节（null 表示不存在）、THEIRS 原文字节或删除标记、前后 SHA-256；来源 HEAD/branch 仅是来源信息。

新版 VM 明确拒绝旧 WORKTREE 包并要求两端升级。SNAPSHOT / DELTA / ACK 的 V2 编码和兼容读取不变。保留分块、校验和与缓存机制。BASE + THEIRS 会增加修改包体积。

## VM 合并决策

BASE = HOST HEAD 内容，OURS = VM 预览时实际文件内容，THEIRS = HOST 最终内容。不存在与零字节文件严格区分。

| 条件 | 结果 |
| --- | --- |
| OURS 等于 THEIRS | 无操作 |
| OURS 等于 BASE | 使用 THEIRS，包括新增或删除 |
| 双方文本独立修改且可无冲突合并 | 合并结果进入预览 |
| Add/Add 不同内容 | 显式冲突解决 |
| HOST Delete、VM 已修改 | 显式保留或删除，不能自动删除 |
| HOST Modify、VM 已删除 | 显式保持删除或恢复文件 |
| 文本重叠修改 | IDEA 三方合并 |
| 二进制冲突 | IDEA binary merge 或明确选边，不转码、不默认覆盖 |

自动合并只在临时文件/内存处理内容，不执行仓库 merge、commit、reset、checkout，不改变 VM HEAD/index。Git merge-file 如用于内容合并，只操作插件临时文件，不是 git merge。

## 冲突 UI 与应用事务

收齐 → 校验 → 自动合并 → 依次解决冲突 → 最终预览 → 用户确认应用。

IDEA 合并输入顺序为左 OURS、中 BASE、右 THEIRS；可编辑合并输出与真实业务文件隔离。新增、删除和二进制冲突必须明确保留、删除或选边；不能把 null 默默变成空文件。

任一冲突取消，整次不调用 apply，不写任何业务文件。所有冲突确认后才启用应用。合并输出仍要经过路径、文件数量与排除规则检查。

取消 WORKTREE 的来源 HEAD 相等门槛。保留现有工作区 stale 检查、目标锁、写前备份、每文件写前比较和失败回滚。预览后单纯 HEAD 变化不阻断，实际文件变化仍拒绝，避免覆盖合并期间的外部编辑。

## 修改范围

- core/Model.java、Codec.java、ClipboardTransport.java：完成及校验 V3 接手改动。
- core/GitHistory.java：tracked HEAD/工作区读取及受控内容合并接口。
- core/ThreeWayMerge.java：新增合并分类、无冲突结果、冲突数据；不依赖 IDEA。
- core/SyncEngine.java：HOST 生成、VM 合并预览、已解决结果与现有事务衔接。
- plugin/MergeDialogs.java：新增 IDEA 文本/二进制冲突解决适配。
- plugin/SyncPanel.java：接收后合并流程、取消处理、确认文案。
- 相关测试、README、architecture、protocol、VALIDATION、AGENTS 与 4.3.0 版本号。

不修改独立文件传输、设置、角色、清缓存、Bundle 对象生成导入、refs、ACK 及无关代码。不碰 .workbuddy-ai 下其他 agent 的记录。

## 验证和交付

使用临时目录的独立仓库验证 staged/unstaged、untracked 排除、分支切换、HEAD 推进、文本自动合并、重叠冲突、Add/Delete、二进制、取消、stale 和回滚。复测 VM → HOST 权威覆盖、refs、ACK 以及独立文件传输。

用户于 2026-09-22 明确允许仅在临时测试仓库执行 Git 写操作。实际业务仓库及本项目不 commit、建分支、reset、merge 或 push。

运行 ./build.sh test 和 ./build.sh，交付 Every Sync 4.3.0 ZIP 及 SHA-256。运行 IDEA 的真实合并窗口若无法自动验证，需要明确标为人工验收项；不得以编译通过替代 UI 验证。两端同时升级。
