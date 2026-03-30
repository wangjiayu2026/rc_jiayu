# Notify Dispatcher (v0)

一个内部通知投递服务：业务系统提交“对外 HTTP(S) 通知请求”，服务负责**持久化入队 + 异步投递 + 失败重试**，业务方不需要关心外部响应内容。

## 运行

需要 Java 17 + Maven

```bash
mvn -q -DskipTests package
java -jar target\notifyd-0.1.0.jar
```

默认监听端口 `8080`，使用 SQLite 文件 `./data/notify.db`（可通过环境变量配置）。

## 环境变量

- `NOTIFY_DB_PATH`: SQLite 路径（默认 `./data/notify.db`）
- `NOTIFY_HTTP_PORT`: HTTP 监听端口（默认 `8080`）
- `NOTIFY_WORKERS`: 投递 worker 数量（默认 `1`）
- `NOTIFY_MAX_ATTEMPTS`: 最大尝试次数（默认 `12`）
- `NOTIFY_BASE_BACKOFF_MS`: 基础退避（默认 `500`）
- `NOTIFY_MAX_BACKOFF_MS`: 最大退避（默认 `600000`，10 分钟）
- `NOTIFY_HTTP_TIMEOUT_MS`: 单次投递超时（默认 `5000`）
- `NOTIFY_POLL_INTERVAL_MS`: worker 轮询间隔（默认 `250`）
- `NOTIFY_JITTER_PERCENT`: 退避抖动比例（默认 `0.2`)
- `NOTIFY_DELIVERING_STUCK_MS`: 投递中卡死回收阈值（默认 `30000`），用于进程崩溃后把 `DELIVERING` 任务重置回 `RETRY`

## API

### POST /v1/notify

入队一个外部 HTTP 通知请求（服务端会异步投递）。

请求体（JSON）字段：
- `method`：HTTP 方法（默认 `POST`）
- `url`：目标地址（必填）
- `headers`：请求 Header（可选）
- `body`：请求 Body（可选）
- `body_base64`：`body` 是否为 Base64（默认 `false`）
- `timeout_ms`：单次投递超时（可选，默认使用服务配置）
- `idempotency_key`：幂等键（可选；相同键返回同一任务）

请求示例：

```bash
curl -X POST http://localhost:8080/v1/notify ^
  -H "Content-Type: application/json" ^
  -d "{\"method\":\"POST\",\"url\":\"https://httpbin.org/status/200\",\"headers\":{\"X-Test\":\"1\"},\"body\":\"hello\"}"
```

返回示例（任务已入队）：

```json
{"job_id":"j_xxx","status":"PENDING"}
```

### GET /v1/jobs/{job_id}

查询任务状态与重试信息。

## 关键目录

- `src/main/java/com/notifyd/api`: HTTP API
- `src/main/java/com/notifyd/storage`: SQLite 持久化与取任务
- `src/main/java/com/notifyd/dispatcher`: 投递 worker（重试/退避/死信）
- `src/main/java/com/notifyd/model`: 数据模型

