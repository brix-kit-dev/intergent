# Intergent

AI 引擎。架构见[蓝图](docs/ai-engine-architecture-blueprint.md)，v1.0 已冻结，改动规则见蓝图 17.3。

## 环境

- Java 21 起
- Maven 3.8.7 起

## 构建和测试

```bash
cd engine
mvn verify
```

产物是 `engine/app/target/engine.jar`。

## 启动

一个程序包，多种角色，用 `--role` 选择：

```bash
java -jar engine/app/target/engine.jar --role=api
```

```bash
curl http://127.0.0.1:18080/healthz
```

| 角色 | 现状 |
| --- | --- |
| `api` | 提供 HTTP 服务，目前只有 `/healthz` |
| `worker`、`migrate`、`admin` | 还没实现，打印提示后退出 |

`/healthz` 给运维探活用，不属于公开 API。

默认只绑定 `127.0.0.1:18080`，这是蓝图 12.6 的要求。换端口用 `--server.port=18081` 或环境变量 `SERVER_PORT`。

| 退出码 | 含义 |
| --- | --- |
| 0 | 正常结束 |
| 1 | 启动失败，如端口被占用 |
| 2 | 角色参数不对：没给、给了多个或不认识 |
| 3 | 角色还没实现 |
| 143 | 收到 SIGTERM，处理完请求后退出 |

## 目录

| 目录 | 层 | 可依赖 |
| --- | --- | --- |
| `engine/ports` | 能力契约（L1） | 无 |
| `engine/core` | 平台核心（L2） | `ports` |
| `engine/modules` | 能力模块（L3） | `core`、`ports` |
| `engine/http` | 接口层（L4） | `modules`、`core` |
| `engine/adapters` | 适配器（L0），每个适配器是它的子模块，目前还没有 | `ports` |
| `engine/app` | 启动入口 | 引擎内所有层 |

内部包名以 `aie` 开头，和目录对应，如 `aie.core`、`aie.http`。
