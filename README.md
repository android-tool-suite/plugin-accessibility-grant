# Android Tool Suite Accessibility Grant

Android Tool Suite 的“无障碍授权”外部插件。本仓库只包含这一个插件，并保留它从主体应用仓库拆出的相关提交历史。

插件通过 `shizuku_auth` 提供的受限 Capability 启用或停用用户选中的无障碍服务。

## 仓库边界

- 本仓库不包含宿主应用源码，也不直接引用宿主的 Gradle project。
- 插件是纯声明式 Web/Worker 工程，不包含旧 API1 Android 代码或原生 Provider。
- 插件产物、版本和发布流程均由本仓库独立管理。

## 构建

要求 JDK 17 和 Gradle 8.9 或更新版本，不需要 Android SDK。在本仓库构建并收集插件包：

```powershell
gradle clean collectArtifacts
```

输出文件为 `artifacts/accessibility-grant.atsplugin`。

## 发布通道

- 推送 `main` 并通过 CI 后，工作流保留 `debug-<完整提交 SHA>` 历史快照并更新滚动 `debug` 预发布；宿主调试仓库自动发现最新构建，Pages 发布中心可选择历史构建。
- 推送 `v<versionName>` 标签后，工作流构建并发布正式 Release。
- 两种发布都会生成 `release-metadata.json` 和 `SHA256SUMS.txt`，并通过 GitHub App 短时令牌发送事件通知插件索引更新。
- `data-compatibility.json` 声明当前构建可能写入的数据格式及可读取范围；修改持久化格式时必须同步递增并评估兼容范围，宿主据此决定是否允许历史版本降级。
