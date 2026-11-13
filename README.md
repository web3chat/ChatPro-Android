# 去中心化版本 Chat33Pro

## 构建

1. 安装 **JDK 11**（更高版本会报 AGP / Groovy 错误）
2. 按 [SECURITY.md](SECURITY.md) 生成各模块的本地配置
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

## 上游与许可

本项目基于 [txchat](https://github.com/txchat) 的 dtalk / im / imparse 项目（MIT License）继续开发，并保留原版权声明。新增和修改部分由 web3chat 维护。

上游协议与依赖（如 `dtalk.biz` protobuf 定义、原有 IM 客户端逻辑）仍按原样引用，未作修改。上游部分与新增、修改部分均适用 MIT License，许可全文见 [LICENSE](LICENSE)。

## 免责声明

本项目的全部内容，包括但不限于代码、文件和技术资料，只供学习、研究使用。请勿用于学习、研究以外的其他用途。

使用者应自行承担使用本项目产生的一切风险和责任；作者及维护者不对因使用本项目造成的任何直接或间接损失负责。

> ⚠️ **说明**：本仓库不包含任何服务端地址、私钥、签名库与第三方密钥。
> 这些配置只保留在本机，首次构建前请按 [SECURITY.md](SECURITY.md) 从各模块的
> `*.example.properties` 模板生成本地真实配置文件。
>
> 📄 **许可证**：[MIT License](LICENSE)（上游 txchat，新增与修改部分 web3chat）。
