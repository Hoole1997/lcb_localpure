# Novi Launcher SDK 接入信息

> 本文档由 `novi-sdk-tool` 自动生成。AI 修改代码时应优先读取同目录的 `novi-sdk.lock.json`。

## 构建信息

- 包名：`com.sonicpure.local.audio.tool`
- SDK 分支：`v2.0.3.1`
- SDK 标识：`release`
- Jenkins Build：#1830
- 正式依赖：`com.launcher.unity:com.sonicpure.local.audio.tool-release:1.0.3`
- AAR SHA-256：`2e853f7b2a566bb4ad9cc51b81380100dc93f62b913b556a8c4b6e6ca7ef096c`
- 映射源码：`app/src/google/java/com/example/lcb/app/LcbApp.kt` (`google`)

## 类映射

| 原类 | 正式 SDK 类 |
|---|---|
| `org.oksp.launcher.App` | `com.sonicpure.local.audio.tool.Gb1j0c8gtf8a89n70qeu` |

## 核心方法映射

| SDK 方法 | 正式 SDK 方法 | AAR 字节码验证 |
|---|---|---|
| `appShowAd` | `scanmetasmartlitetool` | 通过 |
| `setNetworkEventListener` | `scanmetasmartlitetool` | 通过 |
| `openMainActivity` | `syncmemory` | 通过 |
| `getLauncherActivityClass` | `autocleantooltool` | 通过 |
| `getAppActivityClassArray` | `deeprestorecorepanel` | 通过 |

## AI 修改约束

1. 只修改配置指定渠道中的 Launcher Application。
2. `appShowAd` 必须保留 `(Activity, String, Int)` 参数。
3. `setNetworkEventListener` 与 `appShowAd` 可能映射为同名重载，不得合并调用。
4. 修改后必须通过 Local 标识的正式源码编译验证。
5. 编译验证后必须恢复 Google/Official 配置。
