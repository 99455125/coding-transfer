# 可参考的公开项目

- [JetBrains/intellij-platform-plugin-template](https://github.com/JetBrains/intellij-platform-plugin-template)：官方插件工程模板，可参考构建、测试、发布布局。这版为保持独立 Java 构建，没有引入其 Gradle 工程。
- [java-diff-utils/java-diff-utils](https://github.com/java-diff-utils/java-diff-utils)：可参考文本 diff、unified diff 生成和解析。本版使用自己实现的严格单完整 hunk，不引入依赖或模糊补丁应用。
- [JetBrains Tool Windows 文档](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)：ToolWindowFactory、工具窗口内容和生命周期。
- [JetBrains Background Processes 文档](https://plugins.jetbrains.com/docs/intellij/background-processes.html)：后台任务、进度和异常处理。

未找到并验证可直接替代整套“VM 权威 + HOST 外层单 Git + 项目映射 + 纯字符串剪贴板”的现成插件。这些是组件级参考；本项目未复制上述仓库源代码，也没有打包其第三方运行库。
