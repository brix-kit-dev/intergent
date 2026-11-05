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
| `engine/guard` | 架构守卫，只在构建时运行 | 不进运行时 |

内部包名以 `aie` 开头，和目录对应，如 `aie.core`、`aie.http`。

## 架构守卫

`mvn verify` 的最后一步由 `engine/guard` 检查蓝图第 16 节里归 ArchUnit 管的红线。违反时构建失败，报错里写明红线编号。

| 规则 | 禁止什么 |
| --- | --- |
| `A-1` | 内层依赖外层；启动入口以外依赖适配器；`aie` 下的类不属于任何一层 |
| `A-2-ports` | `gateway`、适配器、启动入口以外使用模型类端口 |
| `A-2-sdks` | 适配器以外使用模型厂商 SDK 或 Spring AI |
| `A-4-public` | 使用其他模块 `api` 包以外的类，启动入口除外 |
| `A-4-tx` | `events` 以外的模块在 `api` 包里出现事务对象 |
| `A-13` | 适配器和启动入口以外使用 JDBC、`@Transactional` 或数据库驱动 |
| `A-15` | `trust` 依赖 `gateway` |
| `A-17` | 引擎里适配器以外直接发 HTTP |
| `A-18-engine` | 引擎依赖 SDK 或产品 |
| `A-18-outside` | SDK 或产品依赖引擎 |
| `A-18-sdk` | SDK 依赖产品 |
| `T-5` | `tenancy`、`events`、适配器以外开系统事务 |

这些规则都写在 [ArchitectureRules.java](engine/guard/src/main/java/aie/guard/ArchitectureRules.java)。

- **检查范围：** 从磁盘读取 `engine` 下每个模块编译出的类。有源文件却没有编译结果时构建失败，所以新模块漏不掉。
- **规则本身的测试：** `engine/guard/src/test/java/sample` 是一份模拟的引擎代码。不带标记的类必须全部通过，带 `@Violates` 的类必须正好违反它声明的规则。没有违规样例的规则会让测试失败。
- **第三方类的替身：** `src/test/java` 下 `aie` 和 `sample` 以外的目录都是空壳，只为让样例能编译。
- **跳过的测试：** `DatabasePort` 建好前，构建里有 1 个跳过的测试。它确认 `A-4-tx`、`T-5` 依赖的名字真实存在。
- **SDK 和产品：** 它们还没有代码，`A-18-outside`、`A-18-sdk` 目前只在样例上运行。
