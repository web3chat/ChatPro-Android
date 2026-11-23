# 审批入群（群申请）后端开发文档

> 对应客户端：ChatPro Android `chat-ui` + `chat-core`（`GroupService` / `NetGroupDataSource`）
> 现状：客户端已按本文档调用，但生产环境 `POST /group/app/create-group-apply` 与
> `POST /group/app/get-group-applys` 均返回 **404**，导致「群主审批」的群无法申请加入。
> 本文档用于后端补齐这组接口。

---

## 1. 功能定义

群设置 `joinType` 有三个取值：

| joinType | 含义 | 客户端行为 |
|---|---|---|
| 0 | 可直接加入 | 调 `POST /group/app/join-group`，成功即进群 |
| 2 | 需群主/管理员审批 | 调 `POST /group/app/create-group-apply`，提交申请 |
| 其他 | 不允许加入 | 按钮置灰 |

审批入群的完整链路：

```
申请人(群外)               服务端                          群主/管理员
    |                        |                                 |
    |-- create-group-apply ->|  创建申请(PENDING)              |
    |                        |-- 通知(IM 消息/推送) ----------->|
    |                        |                                 |
    |                        |<-- get-group-applys ------------|
    |                        |--- 申请列表(含申请人资料) ------->|
    |                        |<-- accept-group-apply ----------|
    |                        |--- 加入群成员 + 发系统消息 ----->|
    |<-- 进群/系统消息 -------|                                 |
    |                        |<-- reject-group-apply ----------|
    |<-- 被拒绝通知 ----------|                                 |
```

---

## 2. 数据表设计

```sql
CREATE TABLE `group_apply` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `apply_id`     VARCHAR(64)  NOT NULL COMMENT '申请单号，对外暴露，建议雪花ID/ULID',
  `gid`          VARCHAR(64)  NOT NULL COMMENT '群ID（字符串，客户端以 string 传）',
  `server`       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '群所在服务端标识',
  `member_id`    VARCHAR(128) NOT NULL COMMENT '申请人地址',
  `inviter_id`   VARCHAR(128) NOT NULL DEFAULT '' COMMENT '邀请人地址，空表示本人扫码/链接申请',
  `apply_note`   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '申请备注',
  `apply_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审批 1通过 2拒绝 10忽略',
  `operator_id`  VARCHAR(128) NOT NULL DEFAULT '' COMMENT '处理人地址',
  `reject_reason` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '拒绝原因',
  `create_time`  BIGINT       NOT NULL COMMENT '创建时间(毫秒)',
  `update_time`  BIGINT       NOT NULL COMMENT '更新时间(毫秒)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_apply_id` (`apply_id`),
  -- 同一人对同一群只允许一条待审批申请（幂等/防刷）
  UNIQUE KEY `uk_pending` (`gid`, `member_id`, `apply_status`),
  KEY `idx_gid_status_time` (`gid`, `apply_status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入群申请';
```

> `uk_pending` 需要权衡：MySQL 唯一索引对 `apply_status` 也生效，历史已处理记录会占位。
> 如果同一个人可能多次申请，改用**部分索引不可用**时的替代方案：
> 新增 `pending_flag` 列（待审批=1，其余=NULL），唯一索引改为 `(gid, member_id, pending_flag)`，
> 因为 MySQL 唯一索引允许多个 NULL。

---

## 3. 通用约定

### 3.1 鉴权

沿用现有 `/group/app/**` 的签名/登录态中间件，与 `group-pub-info`、`join-group` 完全一致，
不需要新增鉴权方式。

### 3.2 响应格式

客户端按现有 `HttpResult` 解析：

```json
{ "result": 0, "message": "ok", "data": {} }
```

- `result = 0` 表示成功；非 0 时客户端直接 toast `message`
- `data` 为对象；列表接口为 `{ "applys": [...] }`

### 3.3 错误码建议

| result | message | 场景 |
|---|---|---|
| 0 | ok | 成功 |
| 1001 | 群不存在 | gid 无效 |
| 1002 | 你已在群内 | 申请人已是成员 |
| 1003 | 该群未开启审批入群 | joinType ≠ 2 |
| 1004 | 申请已提交，请等待审批 | 已存在待审批记录（幂等返回成功亦可） |
| 1005 | 申请不存在或已处理 | applyId 无效/已审批 |
| 1006 | 无审批权限 | 非群主/管理员 |
| 1007 | 群成员已满 | 通过时校验容量 |
| -1010 | 无效签名 | 沿用现有网关错误 |

> 错误码具体数值请与现有 `AppException` 映射表对齐，避免客户端提示错乱。

---

## 4. 接口明细

### 4.1 提交入群申请

```
POST /group/app/create-group-apply
Content-Type: application/json
```

**请求**

```json
{
  "id": "1234567890",        // 群ID，字符串
  "applyNote": "我是小王"     // 可选，备注，最长 255
}
```

**服务端校验**

1. 群存在（`gid` 有效，且 `server` 与请求网关一致）
2. 申请人不在群内，否则返回 1002
3. 群 `joinType == 2`，否则返回 1003（客户端此时应改调 join-group）
4. 无待审批记录；有则直接返回成功（幂等）或 1004
5. 申请人不是被拉黑/禁言状态

**处理**

- 插入 `group_apply`：`member_id = 登录用户`，`inviter_id` 取二维码/链接里的邀请人（没有则空），`apply_status = 0`
- 向群主 + 所有管理员发送通知（IM 消息 + 推送），内容示例：
  `小王 申请加入群聊，请前往「群管理-入群申请」处理`
- 返回 `data: {}`

**响应**

```json
{ "result": 0, "message": "ok", "data": {} }
```

---

### 4.2 拉取申请列表（群主/管理员）

```
POST /group/app/get-group-applys
```

**请求**

```json
{
  "id": "1234567890",
  "count": 50,
  "offset": 0
}
```

**校验**

- 请求者为群主或管理员，否则 1006
- `count` 限制 1~100，`offset >= 0`

**响应**

```json
{
  "result": 0,
  "message": "ok",
  "data": {
    "applys": [
      {
        "applyId": "a1b2c3",
        "id": "1234567890",
        "inviterId": "",
        "memberId": "16PmeytY8CU3AF4UB87xrqqwcshXpGxSg7",
        "applyNote": "我是小王",
        "operatorId": "",
        "applyStatus": 0,
        "rejectReason": "",
        "createTime": 1763000000000,
        "updateTime": 1763000000000
      }
    ]
  }
}
```

> 客户端 `GroupApplyTO.Wrapper` 只认 `data.applys`，**不要**返回 `{ "data": [...] }`。
> 字段名严格使用上面的 camelCase；`id` 是**群ID**，不是申请单号。

**排序**：`apply_status ASC`（待审批在前），`create_time DESC`。

**显示资料**：列表仅返回地址与备注，客户端会用 `contactManager` 自行补昵称/头像；
如需后端直接给昵称，可附加 `memberName` / `memberAvatar` 字段（客户端可忽略）。

---

### 4.3 同意申请

```
POST /group/app/accept-group-apply
```

**请求**

```json
{ "applyId": "a1b2c3", "id": "1234567890" }
```

**校验**

- 操作者是群主/管理员（1006）
- 申请存在且 `apply_status == 0`（1005）
- 申请人尚未在群内（1002）
- 群成员未满（1007）

**处理（事务内）**

1. `group_apply` 更新：`apply_status = 1`，`operator_id = 操作者`，`update_time`
2. 加入群成员（复用 join-group 的成员落库逻辑：成员表、成员数 +1、群成员缓存）
3. 广播系统消息（`Msg.NotificationType.JoinGroup`）：
   - `inviter` = **操作者地址**（群主/管理员）
   - `members` = [申请人地址]
   - 客户端会显示「群主 邀请了 小王 进入群聊」
4. 给申请人发送「申请已通过」通知（IM 消息 + 推送）
5. 返回 `data: {}`

---

### 4.4 拒绝申请

```
POST /group/app/reject-group-apply
```

**请求**

```json
{ "applyId": "a1b2c3", "id": "1234567890", "reason": "群已满" }
```

**处理**

1. `group_apply` 更新：`apply_status = 2`、`operator_id`、`reject_reason`、`update_time`
2. 通知申请人：`你的入群申请被拒绝`（可附带 reason）
3. 返回 `data: {}`

> `reason` 客户端暂未传，服务端需容忍缺省。

---

## 5. 与现有 join-group 的关系

客户端在 `create-group-apply` 失败时会**自动回退**到 `join-group`（见 `GroupViewModel.applyJoinGroup`）。
因此 `join-group` 在 `joinType == 2` 时的行为必须明确二选一：

| 方案 | join-group 行为 | 说明 |
|---|---|---|
| A（推荐） | 返回业务错误 `1003 该群需审批，请提交入群申请` | 语义清晰，客户端会展示该文案 |
| B | 自动创建一条待审批申请并返回成功 | 客户端会复查 `group-pub-info`：未进群则显示「等待审核」 |

**不要**在 joinType=2 时直接把用户拉进群，否则审批形同虚设。

---

## 6. 并发、幂等与边界

| 场景 | 要求 |
|---|---|
| 重复提交申请 | `uk_pending` 兜底；命中唯一键时返回成功（幂等），不产生第二条 |
| 两个管理员同时同意 | 事务内 `UPDATE ... WHERE apply_status = 0`，受影响行数为 0 则返回 1005 |
| 同意时申请人已自行进群 | 返回 1002，并置该申请为已处理 |
| 群被解散/申请人退群 | 申请置为 10（忽略），列表不再展示 |
| 群主修改 joinType | 已有待审批申请保留，继续可审批 |
| 申请超时 | 建议 7 天自动置为 10（忽略），由定时任务处理 |
| 申请数量过多 | 列表分页 + 索引 `idx_gid_status_time`；群管理页只显示前 50 条 |

---

## 7. 客户端字段对照

| 服务端列 | JSON 字段 | 客户端字段（`GroupApplyTO`） |
|---|---|---|
| apply_id | applyId | applyId（同 applyRecordId） |
| gid | id | id（同 applyGidStr） |
| member_id | memberId | memberId（同 applyMemberId） |
| inviter_id | inviterId | inviterId |
| apply_note | applyNote | applyNote |
| apply_status | applyStatus | applyStatus（0待审批 / 1通过 / 2拒绝 / 10忽略） |
| operator_id | operatorId | operatorId |
| reject_reason | rejectReason | rejectReason |
| create_time | createTime | createTime（毫秒） |
| update_time | updateTime | updateTime（毫秒） |

客户端判断待审批：`applyStatus == 0`。

---

## 8. 联调与自测

### 8.1 路由是否上线

```bash
# 期望不是 404（未签名时返回业务错误码即可，说明路由存在）
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  ${GROUP_API_BASE}/group/app/create-group-apply \
  -H 'Content-Type: application/json' -d '{"id":"1","applyNote":""}'

curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  ${GROUP_API_BASE}/group/app/get-group-applys \
  -H 'Content-Type: application/json' -d '{"id":"1","count":10,"offset":0}'
```

### 8.2 全链路用例

1. 群 A 设置 `joinType=2`，用非成员账号申请 → 返回成功，群主收到通知
2. 重复申请 → 不产生第二条记录
3. 群主拉列表 → 能看到申请人、备注、时间
4. 群主同意 → 申请人收到「已通过」，进群，群内出现「群主 邀请了 申请人 进入群聊」
5. 群主拒绝（带原因） → 申请人收到拒绝通知，状态变 2
6. 非群主/管理员拉列表 → 1006
7. 已处理的 applyId 再次同意 → 1005
8. 直接把 `join-group` 打到 joinType=2 的群 → 按第 5 节方案 A/B 返回

### 8.3 客户端验证点

- 扫码 → 群资料页显示「申请加入」按钮
- 点击后不再出现「网络请求错误」
- 提交成功后按钮变「等待审核」且置灰
- 群主在「群管理 → 入群申请」看到列表并能同意/拒绝
- 同意后申请人重启 App 能看到自己已在群内

---

## 9. 上线顺序

1. 后端先上 4 个接口（灰度环境验证）
2. 客户端无需发版即可生效（路径与字段已按本文档实现）
3. 若后端暂时只实现 `create-group-apply`，`get-group-applys` 仍 404：申请人能提交但群主看不到，
   **不建议**单独上线其中一个接口

## 10. 待确认

- [ ] 这组接口是否已在其他网关/分支实现，路径是否与本文档一致
- [ ] 群成员上限、黑名单、禁言等约束在 accept 时是否复用 join-group 的校验
- [ ] 通知走 IM 系统消息还是推送，文案是否要支持多语言
- [ ] `inviterId` 为空时系统消息的展示文案（客户端当前显示「X 通过扫码加入了群聊」）
