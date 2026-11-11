# 去中心化版本 Chat33Pro

> ⚠️ **开源说明**：本仓库不包含任何服务端地址、私钥、签名库与第三方密钥。
> 这些配置只保留在本机，首次构建前请从各模块的 `*.example.properties` 模板
> 生成本地真实配置文件。

## 构建

1. 安装 **JDK 11**（更高版本会报 AGP / Groovy 错误）
2. 按各模块 `assets` 下的 `*.example.properties` 模板生成本地配置（如 `chat-pro.properties`）
3. 将 `walletapi.aar`（约 82 MB 的钱包 SDK）放入 `chat-core/libs/` 目录，仓库不包含该文件
4. 编译：`./gradlew :app:compileChat33proDebugKotlin`

当前保留的渠道：`chat33pro`、`chatphone`、`cip`、`ttt`、`nchat`。

## 创建新渠道项目步骤

1. 在 [common.gradle](./common.gradle) 的 `productFlavors` 中新增一项（dimension 统一为 `version`）：

    ```groovy
    newProject {
        dimension "version"
    }
    ```

2. 在 [app/build.gradle](./app/build.gradle) 的 `productFlavors` 中新增同名项，配置 `versionCode`、`versionName` 与 `applicationId`
3. 在 `chat-core/src/<渠道名>/java/com/fzm/chat/W.kt` 中新增该渠道的 `W` 类，把 `appType` 指向 `WConfig` 中对应的常量
4. 在 `app/src/<渠道名>/res` 中放入该渠道的图标与 `app_name` 字符串
5. 在 `business`、`lib-push`、`chat-core`、`chat-oa`、`oss`、`wallet` 模块的 `assets` 中补充对应的 `*.properties`
