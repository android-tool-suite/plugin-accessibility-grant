# Android Tool Suite Accessibility Grant

Android Tool Suite 的“无障碍授权”外部插件。本仓库只包含这一个插件，并保留它从主体应用仓库拆出的相关提交历史。

插件通过宿主提供的 Shizuku UserService 启用或停用用户选中的无障碍服务，依赖宿主内置插件 `shizuku_auth`。

## 仓库边界

- 本仓库不包含宿主应用源码，也不直接引用宿主的 Gradle project。
- 编译时只消费版本化的 `com.androidtoolsuite:plugin-sdk` AAR。
- 插件产物、版本和发布流程均由本仓库独立管理。

## 构建

要求 JDK 17、Android SDK 35、Gradle 8.9 或更新版本。首次本地构建前，在主体应用仓库发布 SDK：

```powershell
gradle -p ..\..\app :plugin-sdk:publishToMavenLocal
```

然后在本仓库构建并收集插件包：

```powershell
gradle clean collectArtifacts
```

也可以直接消费主体仓库内的临时 Maven 仓库：

```powershell
gradle -p ..\..\app :plugin-sdk:publishReleasePublicationToPluginSdkRepository
gradle -PatsSdkRepository=..\..\app\plugin-sdk\build\repository clean collectArtifacts
```

输出文件为 `artifacts/accessibility-grant.atsplugin`。

## 发布通道

- 推送 `main` 并通过 CI 后，工作流更新滚动 `debug` 预发布，宿主调试仓库随后可自动发现该构建。
- 推送 `v<versionName>` 标签后，工作流构建并发布正式 Release。
- 两种发布都会生成 `release-metadata.json` 和 `SHA256SUMS.txt`，并通过 `REGISTRY_DISPATCH_TOKEN` 事件通知插件索引更新。
