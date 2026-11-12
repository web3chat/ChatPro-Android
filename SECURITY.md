# 配置说明

仓库里只提交 `*.example.properties` 模板。**服务端地址、私钥、签名库、第三方密钥
都不在仓库中**，克隆后需要按本文件在本地生成真实配置文件才能跑起来。

## 一、必须准备的文件

按分类把模板复制成同名真实文件，然后在右侧对应位置填值。

### 1. 基础环境

| 要创建的文件 | 模板 | 说明 |
|---|---|---|
| `local.properties` | `local.example.properties` | `sdk.dir` 指向本机 Android SDK；需要 NDK 时填 `ndk.dir` |

### 2. 服务端与业务配置（App 运行必需）

| 要创建的文件 | 模板 | 关键项 |
|---|---|---|
| `chat-core/src/main/assets/chat-pro.properties` | `chat-pro.example.properties` | `BASE_URL`、`GO_URL`、`CENTRALIZED_SERVER`、`SQUARE_SERVER` 等各服务地址；`NO_BALANCE_PRIVATE_KEY`（代扣私钥） |
| `business/src/main/assets/base.properties` | `base.example.properties` | App 名称、官网域名 |
| `redpacket/src/main/assets/redPacket-*.properties` | `redPacket-*.example.properties` | 红包合约地址、RPC 节点 |
| `oss/oss-chat/src/main/assets/oss-base.properties` | `oss-base.example.properties` | OSS 服务 appId |
| `oss/oss-aliyun/src/main/assets/oss-*.properties` | `oss-*.example.properties` | 阿里云 OSS endpoint / bucket / 鉴权服务 |
| `oss/oss-huaweiyun/src/main/assets/oss-*.properties` | `oss-*.example.properties` | 华为云 OBS 配置（用不到可不配） |
| `function/lib-push/src/main/assets/push-*.properties` | `push-*.example.properties` | 友盟 / 小米 / 华为推送密钥与 appId （用不到可不配） |

> `chat-pro.properties` 是整个 App 的中枢配置。**缺少它 App 能编译，但运行时读不到
> 服务端地址，界面会不可用**。

### 3. 签名（可选）

| 要创建的文件 | 模板 | 说明 |
|---|---|---|
| `sign/sign.properties` | `sign/sign.example.properties` | `KEY_ALIAS` / `KEY_PASSWORD` / `STORE_PASSWORD` |
| `sign/chat.jks` | 无（用自己的签名库） | 文件名需与 `flavor.gradle` 里一致 |

不配签名也能构建，产出的是未签名包；`flavor.gradle` 检测到签名库不存在时会跳过签名配置。

### 4. 可选凭据

| 要创建的文件 | 模板 | 说明 |
|---|---|---|
| `local.secrets.properties` | `local.secrets.example.properties` | Bugly mapping 上传的 appid / appkey（不配则打包时跳过上传）；`DEFAULT_CIPHER_PASSWORD` 为本地加密默认口令（不配则使用占位值） |


