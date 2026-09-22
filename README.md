# Every Sync 4.2.2

## 4.2.2 操作简化

两种传输的发送页增加“清空会话”：释放发送分块和复制进度，删除本次打开面板期间生成的已登记临时发送缓存（包括被重新生成替换的缓存）。保留源文件、接收会话、已保存文件、同步基线、待确认回执和备份。不扫描清理重启前的历史缓存；清空后未复制块需重新生成。

- 项目发送：HOST 使用“生成传输”；VM 使用“生成全量 / 生成增量”。一次操作完成扫描、生成和切块，不再单独刷新。
- VM 在发送页点击“接收回执”，直接读取并确认剪贴板回执。HOST 接收页“发送回执”复制已有成功接收回执；不扩展回执协议。
- 项目接收第一排为“读取剪贴板 / 应用变更 / 发送回执 / 清空会话”。收齐后同一个后台任务自动完成预览，仍需手动确认应用。取消手动文本输入。
- 文件发送第一排为“分块大小 KiB / 选择单个文件”，选定后自动生成；文件接收只保留“读取剪贴板 / 选择路径保存 / 清空会话”。
- 两页复制默认定位最小未复制块；手动翻页可重复制所选块，复制成功后恢复定位最小未复制块。状态位于第一排按钮下、块操作按钮上方，项目文件列表位于底部。
- 安装包：`dist/every-sync-4.2.2.zip`。下文旧按钮操作以本节为准；底层协议、基线与应用规则不变。

顶部页签切换 **项目传输 / 文件传输**。项目传输保留原全量/增量 Git 同步流程；文件传输是独立的单文件二进制复制，不需要角色或项目映射。

## 文件传输

1. 发送方：文件传输 → 发送 → **选择单个文件**（ZIP、JAR、图片、文本等均可）→ 选择分块大小 → **生成分块**。
2. 点击 **复制本块**，在另一端文件传输 → 接收 → **读取剪贴板**；按上一块/下一块逐块复制，也可手动粘贴文本。
3. 每个块的头部携带原始文件名、大小、会话 ID、分块编号和 SHA-256；不携带发送方目录或目标路径。
4. 收齐并通过整文件校验后，点击 **选择目标路径并保存**。可选择目录并改文件名；已有文件须确认覆盖（不自动备份原文件）。未收齐或校验失败不能保存。
5. 原始二进制字节完全保留，不做文本/换行转换，不解压、不执行文件。清理当前文件会话只删除接收缓存，不删除已保存文件。

两端操作完全相同，不依赖 Git、HOST/VM 设置或项目基线。仅需打开一个本地 IDEA 项目以存储接收缓存。一次一个普通文件，不再设置文件大小上限；不支持目录/符号链接。分块默认 128 KiB，可选 4–4096 KiB；此设置独立于项目传输。传输过程中编辑源文件不会更新已生成分块，需重新生成。

支持乱序、重复、重启续收：重启后粘贴原会话任意块即可加载进度。缓存位于当前 IDEA 工作区 `.vmsync/file-inbox/`，最多 32 会话（不再设置缓存总字节数上限），已加入运行目录的 `.gitignore`。不需要的旧缓存可手动清理。只接收可信来源，SHA-256 不提供加密或身份认证。

HOST 和 VM（外部 HOST 也可以是 Windows） 安装同一个 ZIP。VM 是权威端，各业务项目独立保存 Git 历史，不自动提交或归档外层工作区 Git。

## 安装配置

1. 两端 IDEA → Settings / Preferences → Plugins → 齿轮 → Install Plugin from Disk，选择 `dist/every-sync-4.2.1.zip`，按提示重启。
2. 打开右侧 Every Sync → **设置**，选择 HOST / VM，配置项目 ID、两端目录、Git 可执行文件、排除规则和分块大小。
3. **主页面选择项目并只读显示本端 VM / HOST，角色仍只在设置里配置。** 两端同一项目 ID 和排除规则必须一致。
4. 两端均需 Git。默认填 `git`；PATH 找不到时填可执行文件完整路径，不加参数。

目标 IDEA Community 2025.1.2（IC-251.26094.121）至 2025.3.x。插件使用 IDEA 的 Java 21，不改变业务 Java 8 SDK、pom.xml 或编译目标。不依赖 Maven/Gradle/Python/PowerShell。

HOST 目录支持绝对路径、工作区相对路径、`../`、`~/`、`.`；无目录名/层级限制。VM 目录必须是真实独立 Git 根，例如 `D:/ideaprojects/ri/ri-user`。只操作选定映射目录；多个映射不能重复或嵌套。不要把整个外层 AI 工作区登记为业务项目，否则它本身就是同步目标。设置保存在打开的工作区 `.vmsync/config.yaml`。

插件 ID 保持 `com.shie.tools.git-change-export`。升级 3.x 后先发送新的**全量 Bundle**；旧文件快照不能成为 Git 增量基线。旧数据不自动删除。

## 首次全量同步

1. VM 选择项目 → 发送 → 刷新变更 → **生成全量 Bundle**。
2. 确认整个 Git 历史允许传输。包包含 HEAD、整个 refs 可达历史，以及未排除的当前工作区文件（包含未提交、未跟踪文件）。
3. VM 复制本块，HOST 同项目接收页读取剪贴板，逐块传完。默认 128 KiB；Windows App 截断时调小并重新生成全部分块。
4. HOST 预览并确认应用。受管理本地修改被覆盖，多余文件被删除，排除文件保留。
5. HOST 点击 **复制成功接收回执**，在 VM 同项目接收页读取并点击 **确认成功接收回执**。

只有第 5 步完成，VM 增量基线才推进。生成、复制、预览不代表对方成功接收。回执可重复确认；重新生成会替换本项目待确认目标，因此不要在旧包没传完时生成新包。

## 日常增量同步

1. VM 开发或切换分支 → 刷新变更 → **生成增量 Bundle**。
2. HOST 收齐、预览、应用。
3. HOST 成功回执传回 VM 并确认，继续下一轮。

基线是**该项目最近收到成功回执的整个 refs 和 VM 工作区状态**，不是分支名或外层 Git 提交。切换分支、回退提交、分支分叉均按已确认 Git 对象和工作区基线计算。仅工作区变化、无新 Git 对象时，仍可发送文件增量和 refs 信息。

HOST 最终状态始终是：**保存的 VM 完整基线 + 本次 VM 增量**，不与 HOST 草稿合并。即使某文件不在增量中，HOST 对它的未排除修改也作废；额外文件删除。暂存区重建为 VM HEAD，VM 未提交内容作为工作区变化保留。接收预览列出完整恢复结果。

漏收增量、缺失基线、Git 对象丢失或排除规则变化会拒绝应用；可重新全量初始化。目前每个项目是一条确认链，不支持同一发送状态同时服务多个独立接收端。

## 刷新变更与缺块提示

VM 刷新按整个 refs 展示：每个 ref 自上次已确认 Bundle 基线以来的 committed 文件变化，以及当前分支未 commit 的 working tree 文件变化。其他分支无须 checkout，点击文件才读取 diff 内容；同一文件的 committed 和 working tree 变化分别展示。HOST 刷新保持原逻辑，不展示其他分支 committed 变化。刷新列表只用于展示，不决定 Bundle 的对象增量范围。

设置中的 chunk size 输入后失焦即提交并保存该数值，最大 4096 KiB，不必点击上下按钮；文件传输页的数值失焦即生效。其他设置仍使用原保存流程。

两种传输均显示**最小未复制块号 / 最小未粘贴块号**，按实际成功复制、已接收块计算，不是简单的“当前块号 + 1”。从中间开始仍提示遗漏的前面块；全部完成显示无缺块。重新生成发送包会重置复制记录；接收进度沿用已有持久化分块。

## HOST → VM

保留原工作区变更传输：HOST 刷新/生成包 → VM 预览/应用。VM HEAD 和目标文件原文必须匹配，否则拒绝。成功只改 VM 工作区，不动 VM HEAD/暂存区，不自动提交。由你审查、测试、提交，再从 VM 全量/增量同步回来。

## 内层 Git 历史

各业务目录有自己的 `.git`，全量应用时缺少则创建。不复制原始 `.git` 文件，而是导入验证后的 Git Bundle。

| 内容 | HOST 引用 |
| --- | --- |
| VM 分支 feature | refs/remotes/vm-sync/feature |
| 每个 VM 分支对应的可切换本地分支 | refs/heads/vm-work/<VM 分支> |
| VM 标签 v1 | refs/tags/vm-sync/v1 |
| 其他 refs，例如 refs/remotes/origin/main | refs/vmsync/refs/remotes/origin/main |
| 当前工作分支 | vm-work/<VM 分支>；游离 HEAD 用 vm-work/detached |
| 应用前 HEAD 恢复点 | refs/vmsync/recovery/<UUID> |

IDEA Git Log 选择对应内层仓库看真实历史。若新建内层仓库未出现在列表，在 Settings → Version Control → Directory Mappings 添加该业务目录为 Git（插件不自动改 IDEA 的 VCS 映射）。全量和增量应用都会更新全部 vm-work/* 本地分支；查看其他 VM 分支内容时切换到对应 vm-work/<分支名>。原有普通 HOST 分支保留；vm-work/* 和 vm-sync 镜像命名空间供插件使用，不要手动占用。VM 删除的分支会移除对应 vm-work/* 分支和镜像引用，删除的标签也随同步移除。外层 Git 不自动保存、不 push。

## 边界与安全

- **排除规则只过滤工作区文件，不能剔除 Git 历史中的文件。** Bundle 可能包含被排除路径的历史版本；须授权整个仓库历史传输。
- 默认排除 .idea/**、target/**、build/**、node_modules/**、*.log；不自动读取 .gitignore。.git / .vmsync 不作为工作区文件传输。
- 不复制 VM hooks、Git 配置（含 remote 连接配置）、reflogs、LFS 媒体、子模块仓库；refs/remotes、refs/stash 等引用及其可达对象属于整个 refs 同步范围；Git 同步命令禁用 hooks，不执行包内命令，无网络传输。
- 一次一个项目，不支持勾选部分文件。rename 为删除+新增；空目录/权限位不复制。符号链接、junction、嵌套仓库、linked worktree、浅仓库、文件↔目录替换不支持；先结束 merge/rebase 等操作。
- 已移除单文件、工作区、Bundle、编码/压缩包及收件缓存的固定字节数上限。文件传输使用磁盘快照和流式校验/保存；项目传输保留既有内存数据模型，实际能力受 JVM 内存、Java 数组/协议长度表示范围和磁盘空间影响，不承诺任意体积都能处理。原有路径校验、文件数量和会话数量规则不变。
- 分块可乱序、重复、重启续收。SHA-256 只检测损坏，不加密/认证。剪贴板历史可能保留源码。
- 本地 HOST 临时仓库自动测试不等于真实 Windows IDEA / Windows App 剪贴板验收。

## 备份和恢复

应用前 `.vmsync/backups/<UUID>/` 保存 before.gz、after.gz、changes.tsv、target.txt、旧基线、Git HEAD/index/refs。异常时尽力恢复文件及 Git 元数据；若外部并发修改，不强行覆盖并报备份路径。请暂停外部编辑、构建和 Git 操作。

有项目锁及模态进度，但**断电/进程终止不是跨文件原子事务**。导入对象、恢复引用、首次初始化的空 .git 可能保留，不做破坏性清理。当前没有主动一键回滚按钮。

人工恢复文件时解压插件获得 JAR，用 Java 21：

```text
java -cp /path/to/coding-transfer.jar dev.codingtransfer.core.BackupExport /path/to/backup/before.gz /path/to/NEW-recovery-directory
```

只导出到不存在的新目录，不自动恢复 Git；结合 changes.tsv、git-*.before 人工核对。

.vmsync 中 baselines、bundle-state、inbox、file-inbox、file-outgoing、backups、git-staging、git-outgoing 含敏感数据，已加入本目录 .gitignore，不要强制提交。通过“本地记录”查看并手动清理已不需要的旧记录；不要删除正在使用的 baselines / bundle-state。收件箱限制 32 会话；清理会话只删分块，不删代码、基线、备份。

## 构建

```sh
cd /Users/dasensen/IdeaProjects/coding-transfer
./build.sh test
./build.sh
```

使用 IDEA 自带 JDK 21 和本地官方 IC-251.26094.121 SDK 缓存。其他机器可设 PLUGIN_JDK / PLUGIN_SDK，不自动下载依赖。产物 dist/every-sync-4.2.1.zip 和 .sha256。

详见 [architecture.md](architecture.md)、[protocol.md](protocol.md)、[VALIDATION.md](VALIDATION.md)。
