# Every Sync — 架构与原理详解（面向优化决策）

版本基线：插件 4.2.3（`plugin.xml`），代码 2,178 行（主源码 1,860 + 测试 318）。
本文只描述**代码实际行为**，并标注每一处设计与它的代价，供优化取舍使用。

---

## 1. 本质：一句话与四条硬约束

> **在 VM（Windows，Git 权威端）与 HOST（macOS，AI 工作区镜像）之间，用「人手复制的剪贴板文本」作为唯一信道，做字节精确、单向、回执门控的项目与文件同步。**

四条不可协商的约束（来自 AGENTS.md 的历次修订）：

| 约束 | 含义 |
|---|---|
| **VM 是唯一业务 Git 权威** | HOST 的内层仓库只是镜像；外层工作区 Git 永不自动提交/推送 |
| **无网络、无 shell** | 只调固定的 Git 子命令；包内数据永远不能变成命令 |
| **字节精确** | 不做任何文本/换行/编码转换；差异判定用 `Arrays.equals` |
| **显式确认** | 生成≠接收≠应用；应用≠成功；只有**回执确认**才推进基线 |

这四条决定了后面几乎所有「看起来绕」的设计。

---

## 2. 分层与代码结构

```
src/main/java/dev/codingtransfer/
├── plugin/                    ← 唯一依赖 IntelliJ 平台的一层（UI + 线程）
│   ├── SyncToolWindow.java        ToolWindow 工厂，装配两个顶层页签
│   ├── SyncPanel.java             项目传输页（243 行，最大 UI 类）
│   ├── FileTransferPanel.java     文件传输页
│   ├── SettingsDialog.java        角色/映射/排除/分块大小
│   └── OpenSyncAction.java        Tools 菜单入口
└── core/                      ← 纯 Java，零 IDEA 依赖，可脱离 IDE 测试
    ├── Model.java                 数据模型 + SHA-256 + 路径/ID 校验谓词
    ├── Codec.java                 包体的二进制序列化（magic/version/字段）
    ├── Config.java                极简 YAML 子集解析 + 映射/排除 + policy 摘要
    ├── SafeFiles.java             扫描、原子写、路径与符号链接防护
    ├── TextPatch.java             整文件单 hunk 补丁的生成与应用
    ├── ClipboardTransport.java    项目包的分块/重组/收件箱
    ├── FileTransfer.java          单文件包的分块/重组/落盘
    ├── SyncEngine.java            工作区通路：生成/预览/应用/回滚
    ├── BundleSync.java            Git 通路：快照/增量/回执 状态链
    ├── GitReader.java             只读 Git 查询（HEAD、仓库根）
    ├── GitHistory.java            Bundle 生成/隔离校验/受控导入/事务
    └── BackupExport.java          崩溃后人工恢复的 CLI
```

**这个分层是整个代码库最重要的资产。** `core` 不 import 任何 `com.intellij`，所以 `./build.sh test` 能在无 IDE 的情况下跑 243 项断言。优化时请守住这条线——把逻辑放进 `core`，UI 只做编排。

### 职责边界（速查）

| 类 | 负责 | 明确不负责 |
|---|---|---|
| `Config` / `SafeFiles` | 映射解析、排除匹配、路径安全、扫描、原子写 | 任何传输语义 |
| `Model` / `Codec` | 数据模型、哈希、二进制编解码 | 业务规则 |
| `TextPatch` | UTF-8 文本检测、补丁生成/应用 | 是否该用补丁 |
| `ClipboardTransport` | 文本分块、头部校验、收件箱持久化、重组 | 应用变更 |
| `SyncEngine` | 差异计算、预览校验、备份、写入、回滚 | Git 对象导入 |
| `BundleSync` | pending/acknowledged 状态、目标重建、回执 | 文件写入 |
| `GitHistory` | Bundle 创建/校验、refs 映射、事务 | 业务文件 |

---

## 3. 传输通道：剪贴板

### 3.1 为什么是剪贴板

因为要**跨 Windows VM ↔ macOS HOST 且不允许网络**。剪贴板是唯一由人手动、双向、无协议栈的信道。

代价也很明确：**每一次「块」都需要人手一次复制 + 一次粘贴**。这是整个插件最大的用户体验成本，也是优化空间最大的地方（见 §11）。

### 3.2 编码链

```
业务字节
  → Codec.encode:  DataOutputStream 二进制 schema
  → GZIP
  → Base64（ASCII 字符串）
  → 按 chunkKb 切分成 N 段
  → 每段包一层文本头部
```

- **头部**（`VMSYNC/1` 或 `VMSYNC/2`）是纯文本，字段固定且**必须完全相等**（`ClipboardTransport.parse` 用 `keySet().equals(...)` 逐字段校验，未知字段直接拒绝）。
- `SHA256` = 整个 GZIP 包的哈希；`CHUNK256` = **本块 Base64 文本**的哈希。两者独立。
- **关键细节**：项目包的 `chunkKb` 作用在 **Base64 字符串**上，而文件包的宽度是 `chunkKb*1024/4*3` 作用在**原始字节**上——后者刚好让 Base64 后等于 `chunkKb*1024`。所以两者的「块大小」语义其实一致，但实现路径不同（一处易错点）。
- 单块文本上限 **4 MiB**（`parse` 里 `data.length()<=4*1024*1024`），而 `chunkKb` 上限 4096 KiB = 4 MiB ——**正好顶到天花板**。再调大不会生效，只会被拒。

### 3.3 收件箱

`.vmsync/inbox/<UUID>/`：`metadata.txt`（头部指纹）+ `<n>.txt`（原始块文本）。
- 指纹 = 除 `PART`/`CHUNK256` 外的所有头部字段排序拼接 → 天然检测「混会话」。
- 乱序、重复（内容必须相同）、重启续收都支持。
- **最多 32 个会话**，无字节配额。
- 收齐后：Base64 解码 → 校验 `SHA256` → `Codec.decode` → 交叉核对 TYPE/PROJECT/HEAD → 核对「有无 git 字段」与协议版本一致 → 核对 `policy`。

---

## 4. 项目传输：三条通路 + 一个状态机

这是最容易讲混的部分。**「项目传输」不是一条通路，而是三条，方向不同、语义不同。**

| 通路 | 类型 | 协议 | 带 Git？ | 方向 |
|---|---|---|---|---|
| ① HOST 改工作区 → VM | `WORKTREE` | VMSYNC/1 | **不带** | HOST → VM |
| ② VM 全量 → HOST | `SNAPSHOT` | VMSYNC/2 | 带完整 Bundle | VM → HOST |
| ③ VM 增量 → HOST | `DELTA` | VMSYNC/2 | 带增量 Bundle | VM → HOST |
| ④ HOST 回执 → VM | `ACK` | VMSYNC/2 | 只带 ID，无文件 | HOST → VM |

**① 与 ②③ 的语义完全不对称，这不是巧合：**

- **① 是「对 VM 基线的声明」**：HOST 说「我认为你的某个文件是 X，我要把它变成 Y」。
  所以 VM 接收时必须逐个文件验证「我现在确实是 X」→ 这就是 `BASE_CONTENT_MISMATCH`。
  并且 ① **永不改动 VM 的 Git**（`payload.git()==null`，`checkHead` 只比对 HEAD 不写）。
- **②③ 是「VM 的权威状态」**：HOST 不辩论，直接**重建**成 `基线 + 增量` 的完整结果。
  所以 HOST 侧 `preview` **不做逐文件 before 校验**（`SyncEngine` L76 走 `manifest(target)` 分支），
  因为目标就是权威结果，HOST 本地任何未在增量中的修改**一律作废**、多余文件**删除**、排除文件**保留**。

### 4.1 状态机（回执门控）

```
VM: 生成全量/增量
      └→ bundle-state/pending/<id>.gz   （待确认目标，重新生成会覆盖）

HOST: 接收 → 预览 → 显式确认应用
      └→ baselines/<id>.gz              （基线只在「HOST 接受 VM 快照」时推进）
      └→ 复制回执

VM: 接收回执 → 确认
      └→ bundle-state/acknowledged/<id>.gz  （这才是下一次增量的起点）
```

**为什么这么绕**：`生成 ≠ 复制 ≠ 对方收到 ≠ 应用成功`。只有第 5 步（回执确认）能推进基线。
`BundleSync.acceptReceipt` 会校验回执的 `snapshot/parent/head` 必须等于**最近生成的 pending**，
且 `parent` 必须等于上一份 acknowledged 的 snapshot —— 这样回执无法被伪造或错序接受。

**代价（你已经被咬过一次）**：HOST 的发送清单是 `diff(baseline, hostWorktree)`。
基线不推进 → **同一批文件会一直显示为待发送**。而 ① 通路**永远不会推进基线**，
所以 HOST→VM 的改动在 VM 提交并回传新快照之前，会一直挂在列表上。

### 4.2 增量基线为什么用 UUID 而不是提交号

`BundleSync.target` 要求 `baseline.git().snapshot().equals(payload.git().parent())`。
`snapshot` 是**每次生成的随机 UUID**，`parent` 来自 VM 的 acknowledged 状态。
→ 这样「基线」是一个**不可伪造的会话标识**，而不是可以指向任意提交的哈希。
分支、回退、分叉都被统一成「已确认的 Git 对象集合」，而不是「分支名」。

---

## 5. 文件传输（独立子系统）

**与项目传输完全解耦**：不读 `Config`、不要角色、不碰 Git、不要项目映射。

- 协议 `VMSYNC-FILE/1`，头部含 `FILENAME`（单个 basename）、`SIZE`（64 位）、`CHUNK-BYTES`、`PARTS`、`SHA256`。
- **发送方先把文件快照到** `.vmsync/file-outgoing/<session>/content.bin`，再用 `RandomAccessFile` **按需惰性读取**每一块 → 发送端不再把整个文件读进内存（与项目传输相反）。
- 接收端：`file-inbox/<session>/<n>.part`，收齐后拼装 → 整文件 SHA-256 → `verified.bin`。
- **保存路径只由接收方本地选择**，包内绝不携带任何目标路径（`FILENAME` 只作为对话框默认名，且被正则清洗）。
- 覆盖保护：`destination()` 在弹确认框**之前**捕获目标文件哈希；`save()` 时再比一次，变了就拒绝（防 TOCTOU）。
- 上限 32 会话；单块 ≤ 4 MiB；`CHUNK-BYTES` ≥ 3072 且能被 3 整除（Base64 对齐）。

**这是整个插件里设计最干净的一块**——值得作为项目传输重构的参照。

---

## 6. 发送端 / 接收端逐步流程

### 6.1 HOST 发送（通路①，`SyncEngine.generate` + `ClipboardTransport.split`）

```
1. readBaseline(id)          → 取 .vmsync/baselines/<id>.gz，校验 type/project/policy
2. manifest(base)            → 基线的 path→bytes 映射
3. SafeFiles.scan(root)      → 全量读取当前工作区（排除 .git/.vmsync/exclude）
4. same(scan, scan)          → 二次扫描确认扫描期间无变化
5. diff(before, current)     → Arrays.equals 逐文件比较
6. 逐变更选编码              → after==null → DELETE
                               两边都是 UTF-8 文本 → PATCH
                               否则 → RAW
7. 每条目 before=hash(旧) after=hash(新)
8. Codec.encode → GZIP → Base64 → 切块
```

### 6.2 VM 接收（`ClipboardTransport.receive` + `SyncEngine.preview`）

```
receive:
  解析头部 → 方向校验 → 项目校验 → 会话指纹 → 落盘 → 计算最小缺失块
  收齐 → Base64 解码 → SHA256 → Codec.decode → 交叉核对 → policy 核对
preview（同一后台任务内）:
  1. 角色/方向校验
  2. checkHead: VM 当前 HEAD == payload.head()      → 否则 BASE_MISMATCH
  3. SafeFiles.scan(root)                            → VM 实时工作区
  4. 逐条目: hash(VM 文件) == entry.before()          → 否则 BASE_CONTENT_MISMATCH
            解码（DELETE/RAW/PATCH）
            hash(结果) == entry.after()               → 否则 CHECKSUM_ERROR
  5. diff + preflight（大小写冲突、目录/文件冲突）
  6. 再 checkHead 一次
→ 返回 Preview（含完整 before/after 映射），等用户点「应用变更」
```

### 6.3 应用（`SyncEngine.applyLocked`）——顺序是安全的关键

```
1. 校验映射根未变
2. checkHead + same(before, scan)        ← 预览后又验一次
3. preflight
4. 写备份：before.gz / after.gz / target.txt / previous-baseline.gz / changes.tsv
5. status.txt = PREPARED                  ← 第一处写入之前的日志点
6. 再验 same(before) + checkHead
7. （DELTA）BundleSync.target 再验一次
8. GitHistory.begin                       ← 导入 Bundle、备份 HEAD/index/refs
9. 逐文件: 写前 Arrays.equals(now, before) → 否则 STALE_PREVIEW
            原子写 / 删除
10. checkHead + same(after, scan)         ← 全量复核
11. git.commit()                          ← 更新 refs、read-tree、symbolic-ref HEAD
12. （仅 HOST）baseline := target          ← 基线推进的唯一位置
13. status.txt = APPLIED
失败 → 反向回滚已写文件 + git.rollback()，status.txt 记录 restored / 需人工恢复
```

**并发保护**：`.vmsync/locks/apply.lock` 上的 `FileChannel.tryLock`，按**目标目录**加锁（两个不同 IDEA 工作区映射到同一目录也会互斥）。

**已知非原子点**：`PREPARED` 状态下进程被杀 = 工作区半写、既没完成也没回滚。这是设计上承认的缺口，靠备份目录人工恢复。

---

## 7. Git 事务（`GitHistory`）

### 7.1 命令硬化

每条 Git 命令都被强制加上：

```
--no-optional-locks
-c core.hooksPath=<.vmsync/disabled-hooks>   ← 且要求该目录必须为空
-c core.fsmonitor=false  -c core.splitIndex=false  -c core.sparseCheckout=false
-c gc.auto=0  -c maintenance.auto=false  -c protocol.allow=never
-C <root>
```

外加：清空所有 `GIT_*` 环境变量、`GIT_TERMINAL_PROMPT=0`、`LC_ALL=C`、120 秒超时、不用 shell、输出流并发读取防死锁。**这是防御「包内数据变成命令」的核心**。

### 7.2 Bundle 与隔离校验

- 生成：`git bundle create <out> HEAD --all`，对每个 acknowledged ref 和基线 head 追加 `^<hash>` 做排除；**增量可能得到空 Bundle**（`Refusing to create empty bundle` 被容忍 → 返回 `new byte[0]`）。
- 校验：在 `git-staging/<uuid>/verify.git`（临时 bare 仓库）里 `bundle verify` → `bundle unbundle`，再逐个 `cat-file -e` 验证 head 与每个 ref 可达。
- **增量隔离的巧妙点**：给 bare 仓库写 `objects/info/alternates` 指向真实仓库的 `.git/objects`，于是「本地前置对象是否齐全」被 Git 自己验证，而全量包**不允许**借用接收方对象。

### 7.3 refs 映射

| VM 侧 | HOST 侧 |
|---|---|
| `refs/heads/feature` | `refs/remotes/vm-sync/feature` + `refs/heads/vm-work/feature` |
| `refs/tags/v1` | `refs/tags/vm-sync/v1` |
| 其他 `refs/*` | `refs/vmsync/refs/*` |
| 当前 HEAD | `refs/heads/vm-work/<分支>` 或 `vm-work/detached` |
| 应用前 HEAD | `refs/vmsync/recovery/<UUID>` |

更新用 `git update-ref --stdin` 的 `start / prepare / commit` → 天然的 CAS 事务。
`read-tree <head>` 重建 index **但不检出工作区**（所以 HOST 的工作区由插件自己写，Git 不插手）。
普通 HOST 分支保留；`vm-work/*` 与 `vm-sync*` 是插件的命名空间。

---

## 8. 存储布局（`.vmsync/`）

| 目录 | 内容 | 敏感度 |
|---|---|---|
| `config.yaml` | 角色、映射、排除、分块大小 | 低 |
| `baselines/<id>.gz` | **HOST 的 VM 基线**（SNAPSHOT 包体） | 高 |
| `bundle-state/pending/<id>.gz` | VM 待确认目标 | 高 |
| `bundle-state/acknowledged/<id>.gz` | VM 已确认基线 | 高 |
| `inbox/<uuid>/` | 项目包分块 | 高（含源码） |
| `file-inbox/<uuid>/` | 文件分块 + `verified.bin` | 高 |
| `file-outgoing/<uuid>/content.bin` | 文件发送快照 | 高 |
| `git-outgoing/<uuid>/history.bundle` | 待发 Bundle | 高 |
| `git-staging/<uuid>/verify.git` | 隔离校验仓库 | 中 |
| `backups/<uuid>/` | before/after/变更清单/Git 元数据 | 高 |
| `locks/apply.lock` | 应用互斥锁 | — |
| `disabled-hooks/` | 必须保持为空 | — |

全部自动写入 `.vmsync/.gitignore`。`BackupExport` CLI 可把 `before.gz` 导出到新目录做人工恢复。

---

## 9. 安全边界（明确承认的缺口）

- SHA-256 只做**完整性**校验，**不提供加密或身份认证**——只接收可信来源。
- 排除规则**只过滤工作区**，不能剔除 Git 历史中的文件（Bundle 可能含被排除路径的历史版本）。
- 剪贴板历史可能保留源码。
- 不复制 hooks、Git 配置、reflog、LFS、子模块。
- 不支持：符号链接、junction、嵌套仓库、linked worktree、浅仓库、文件↔目录替换、merge/rebase 进行中。
- 断电/进程终止**不是跨文件原子事务**，无一键回滚按钮。

---

## 10. 关键设计决策 → 代价 → 优化杠杆

| # | 决策 | 收益 | 代价 | 优化杠杆 |
|---|---|---|---|---|
| 1 | 剪贴板作为唯一信道 | 跨平台、无网络、无协议栈 | 每块一次人工复制+粘贴 | ★★★ 可选第二信道（共享目录/落盘交接），复用同一分块帧 |
| 2 | **全内存字节数组模型** | 简单、可整体校验 | 峰值内存 ≈ 包体 ×3~4（raw + gzip + base64 String + 块列表） | ★★★ 流式编码（gzip 直接写进分块 sink）+ 清单落盘 |
| 3 | **PATCH 携带完整旧文+完整新文** | 人可读的统一 diff | **条目数据 ≈ 2×，且 gzip 后仍 ≈ 2×**（实测 RAW 省 50.8%） | ★★★ 见 §11.1，可直接删除 PATCH |
| 4 | 字节精确、不转换换行 | 语义清晰、无隐式改写 | CRLF/LF 差异被当真实变更（你正在踩的坑） | ★★ 可选「规范化 EOL」模式，但会动摇基线与备份语义 |
| 5 | 基线只在 HOST 接受 VM 快照时推进 | 回执门控不可伪造 | HOST→VM 的改动长期显示为「待发送」 | ★★ 明确区分「已发送」与「待发送」两种列表状态 |
| 6 | 发送清单 = 变更文件（非整项目） | 传输量小 | git 回滚等本地回退会撞上 before 校验 | ★★ 接收端加显式「覆盖本地改动」开关 |
| 7 | 备份先行 + PREPARED 日志 | 可人工恢复 | 非跨文件原子；无 UI 回滚入口 | ★★ 加一键回滚（数据已在 backups/ 里） |
| 8 | `read-tree` 不带 `-u` | 不触发工作区检出、不破坏 HOST 未提交内容 | index stat 缓存与工作区不匹配 → `git status` 假阳性（91 vs 16） | ★ 应用后顺手 `update-index --refresh`（当前被明确禁止，需你决定） |
| 9 | 扫描时 `noLinks` 逐级遍历 | 彻底防符号链接逃逸 | 每文件 O(深度) 次系统调用 | ★ 缓存已校验的目录 |
| 10 | 应用前后各做一次全量 `same()` | 极强的陈旧检测 | 一次 apply 把项目**完整读 4 遍** | ★ 按 (size,mtime,hash) 分层短路 |
| 11 | 每项目单条接收链 | 状态机简单、不可伪造 | 不能一对多 | 需要多 HOST 时才动 |
| 12 | 排除规则参与 policy 哈希 | 两端配置不一致立刻拒绝 | 改 exclude 必须两端同时改 | 文档化即可 |
| 13 | `hash(null) == "-"` | 统一表示「不存在」 | **文件缺失也报「已有不同修改」**，误导排查 | ★ 区分 ABSENT / MODIFIED 两种报错 |

---

## 11. 优化候选清单（按 收益/风险 排序）

### 11.1 ★★★ 删除 PATCH 编码 —— 最高性价比

**证据**（`EncCompare.java` 实测，16 条目真实发送，chunkKb=2048）：

| | PATCH | RAW | 差 |
|---|---|---|---|
| 条目数据 | 3,848,861 B | 1,893,233 B | **−50.8%** |
| `Codec.encode` 后 | 509,577 B | 258,566 B | **−49.3%** |
| 剪贴板块数 | 1 | 1 | 0 |

**为什么 PATCH 从来不会更小**：`TextPatch.create` 生成的补丁同时包含**完整旧文和完整新文**。
对 300 KB 文件改 1 行，gzip 的 32 KB 窗口够不到跨文件的重复，于是补丁 ≈ 2× 原文。

**而它想提供的安全检查是冗余的**：`before` 哈希**本来就随每个条目发送**，接收端在
`SyncEngine.preview` L81 / `BundleSync.target` L58 无条件校验，与编码无关。
`TextPatch.apply` 再验一遍旧文，等价于同一个断言。

**结论**：PATCH 可以整体移除，只发 RAW。
- 数据量减半、gzip 效率提升、`Codec` 与 `TextPatch` 各去掉一条分支和一类解析 bug。
- **向后兼容**：RAW 是既有编码，老接收端照常接受。这是**纯协议兼容的简化**。
- `TextPatch.text()` 仍需保留（UI diff 与 UTF-8 判定用）。

### 11.2 ★★★ 流式编码，去掉全内存模型

`Codec.encode` 现在是：`ByteArrayOutputStream`（全部原始字节）→ GZIP → `ByteArrayOutputStream` → `String`（Base64）→ `List<String>` 分块。峰值 ≈ 包体 3~4 倍。

改成：`GZIPOutputStream` 直接写入一个「按 chunkKb 切块并即时生成头部」的 sink，逐块产出字符串。
- 峰值内存降到 ≈ 单块大小 + 当前文件。
- 与 §11.1 叠加效果最好（RAW 本身就是单份数据）。
- 注意：`SHA256` 是整个包体的哈希，需要边写边更新 digest（可行，无需缓存全量）。
- 风险：中等。`Codec.decode` 侧同理可改为流式，但接收端有 4 MiB 上限约束，收益较小。

### 11.3 ★★ 降低人工复制粘贴成本

当前每次块都要人手两下。可选：
- 共享目录/落盘交接通道（复用同一分块帧，零协议改动）；
- 或对 `chunkKb` 更友好的默认值与「复制全部剩余块」辅助。

### 11.4 ★★ 接收端「覆盖本地改动」显式开关

`SyncEngine.preview` L81 的 before 校验是唯一防止静默覆盖 VM 并发修改的护栏，**不该默认移除**。
但它可以做成接收端的一个显式复选框：列出受影响文件 → 用户确认 → 跳过该条 before 校验。
保留默认安全，同时给出你在 git 回滚场景需要的逃生口。

### 11.5 ★★ 一键回滚

`backups/<uuid>/` 已含 `before.gz`（SNAPSHOT 包体）+ `changes.tsv` + `git-*.before`。
`BackupExport` 已能导出。补一个 UI 入口即可，无需新数据模型。

### 11.6 ★ 消除 index 假阳性

`read-tree`（不带 `-u`）留下的 stat 缓存不匹配会让 `git status` 多报 72 条。
应用收尾时执行一次 `git update-index --refresh`（或 `--really-refresh`）即可消失。
**但 AGENTS.md 明确禁止自动 reset/add**——这需要你点头才能加。

### 11.7 ★ 报错区分度

`hash(null) == "-"` 导致「接收方文件不存在」也报 `BASE_CONTENT_MISMATCH: VM 文件已有不同修改`。
改成 `BASE_FILE_ABSENT` / `BASE_CONTENT_MISMATCH` 两种消息，排查成本立刻下降。

### 11.8 ★ 性能微调

- `SafeFiles.state()` 每次调用都读+写 `.vmsync/.gitignore`，而它被 `baseline()` 等高频路径调用 → 加进程内缓存。
- `SafeFiles.same()` 在 apply 中触发两次全项目读取 → 见 §10 #10。

---

## 12. 当前验证状态（诚实清单）

**本轮实测（2026-09-22）：`./build.sh test` 全绿，退出码 0，共 242 项断言。**

| 分区 | 断言数 |
|---|---|
| core | 134 |
| Bundle | 37 |
| File | 47 |
| Refs | 24 |
| **合计** | **242** |

- 全部使用一次性临时仓库（`/private/var/folders/.../coding-transfer-test-*`），不触碰业务仓库、不 push。
- `VALIDATION.md` 的 2026-09-21 条目记录过一条失败断言
  `VM accepts remote HOST absolute mapping without resolving it` —— 本轮实测中该断言**已不存在**
  （core 计数从 135 降到 134），套件已恢复全绿。`VALIDATION.md` 那段是过期记录，建议同步更正。
- **仍未验证**（`VALIDATION.md` 的 "Not yet verified" 一节，本轮无变化）：
  真实 IDEA GUI 布局与交互、Windows junction/文件系统行为、Windows App 剪贴板重定向与可靠最大块大小、
  JetBrains Plugin Verifier、断电/进程被杀场景的人工恢复演练。
- 编译通过 ≠ 运行认证。242 项断言覆盖的是 `core` 的语义，不是 IDE 集成。

---

## 13. 一句话总结

> 这个插件的核心价值是**「用最不可靠的信道，做最保守的同步」**：字节精确、回执门控、备份先行、Git 命令全面硬化。
>
> 它的主要成本集中在三处：**全内存模型**、**PATCH 的 2× 冗余**、**逐块人工复制**。
>
> 前两处都能在**不破坏任何安全语义、且保持协议兼容**的前提下消除——这是最值得先做的优化。
