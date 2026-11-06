# Intergent

AI 引擎。架构见[蓝图](docs/ai-engine-architecture-blueprint.md)，v1.0 已冻结，改动规则见蓝图 17.3。开发顺序见[第一阶段计划](docs/phase-1-plan.md)。

## 环境

- Java 21 起
- Maven 3.8.7 起，`engine/verify.sh` 要求正好是 3.8.7
- Docker，数据库测试要用

## 构建和测试

```bash
cd engine
mvn verify
```

产物是 `engine/app/target/engine.jar`。

推送前运行完整检查，约 3 分钟。GitHub Actions 运行的是同一个脚本：

```bash
engine/verify.sh
```

## 开发数据库

```bash
deploy/dev/up.sh
```

这条命令启动引擎自己的 PostgreSQL，端口是 `127.0.0.1:15432`，容器名 `aie-postgres`，都和 Brix 分开。第一次运行会生成随机密码，写在 `deploy/dev/.env`，这个文件不进仓库。

角色和 schema 来自 [create-roles.sql](deploy/db/create-roles.sql)，按蓝图 7.6 建 `engine_owner`、`engine_app`、`engine_system`。真实单元也用这个脚本。

重新开始时清空数据：

```bash
docker compose -f deploy/dev/compose.yaml down -v
```

数据库测试不用这个容器。Testcontainers 每次起一个临时数据库，用同一个镜像和同一个脚本，端口只绑定 `127.0.0.1`。

建表用 `migrate` 角色。先构建 `engine.jar`，再运行：

```bash
deploy/dev/migrate.sh
```

模块写自己的 SQL，通过 `aie.ports.DatabasePort` 执行。适配器 `store-postgres` 按蓝图 5.5 保证：

- 开启事务必须给出租户，租户号只在这个事务里有效。系统路径用 `inSystemTx`，它不带租户，业务表读出来是空的。
- 事务不能嵌套。
- 事务结束后，或者换一个线程，`Tx` 就不能再用，否则语句会跑在事务外面。
- 事务有超时。

## 数据库迁移

迁移脚本放在所属模块的目录里，如 `engine/core/src/main/resources/db/migration/tenancy`。目录名就是模块名，新模块加目录就行，不用登记。每个模块有自己的历史表 `flyway_history_<模块>`，所以版本号互不影响，模块拆走时脚本和历史一起带走。

真实单元这样运行，部署时跑一次：

```bash
ENGINE_DATABASE_URL=jdbc:postgresql://数据库地址/engine ENGINE_OWNER_PASSWORD=... java -jar engine.jar --role=migrate
```

`migrate` 总是用 `engine_owner` 连接，地址里写别的用户也不起作用。密码只放环境变量，不写在地址或命令行里。

| 情况 | 结果 |
| --- | --- |
| 脚本出错 | 这个脚本整个回滚，退出码 1 |
| 已执行的脚本被改过 | 校验失败，退出码 1。要改就加一个新脚本 |
| 文件名不合规 | 报错，不会被跳过 |
| 一个脚本都没找到 | 报错，防止打包漏了脚本还显示成功 |
| 数据库里缺 `engine` schema | 报错。它由 `create-roles.sql` 创建，`migrate` 不建 |
| 表被别的事务锁住 | 等 10 秒就失败，否则请求会排在 `ALTER TABLE` 后面堵住 |
| 同时跑两个 `migrate` | 后一个等前一个，最多 10 秒，等不到就失败。重跑即可 |

**新表：** 照 `V1__create_tenants.sql` 写，这是蓝图 5.6 的模板：行级安全、`FORCE`、租户策略、授权。有序列时序列也要授权给 `engine_app`。

**改租户表的数据：** 表所有者也受 `FORCE` 限制，所以 `migrate` 连接设了 `row_security = off`，忘了解除时直接报错，不会静默更新 0 行。在同一个脚本里先 `NO FORCE`，改完再 `FORCE`。

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
| `migrate` | 运行数据库迁移，完成后退出 |
| `worker`、`admin` | 还没实现，打印提示后退出 |

`/healthz` 给运维探活用，不属于公开 API。

默认只绑定 `127.0.0.1:18080`，这是蓝图 12.6 的要求。换端口用 `--server.port=18081` 或环境变量 `SERVER_PORT`。

| 退出码 | 含义 |
| --- | --- |
| 0 | 正常结束 |
| 1 | 启动失败，如端口被占用；迁移失败 |
| 2 | 角色参数不对：没给、给了多个或不认识 |
| 3 | 角色还没实现 |
| 143 | 收到 SIGTERM，处理完请求后退出 |

## 目录

| 目录 | 层 | 可依赖 |
| --- | --- | --- |
| `contracts` | 公开 API 的契约 | 无 |
| `deploy` | 数据库脚本、开发环境 | 无 |
| `engine/ports` | 能力契约（L1） | 无 |
| `engine/core` | 平台核心（L2） | `ports` |
| `engine/modules` | 能力模块（L3） | `core`、`ports` |
| `engine/http` | 接口层（L4） | `modules`、`core` |
| `engine/adapters` | 适配器（L0），每个适配器是它的子模块。现有 `store-postgres` | `ports` |
| `engine/app` | 启动入口 | 引擎内所有层 |
| `engine/guard` | 架构守卫、依赖检查和契约检查，只在构建时运行 | 不进运行时 |

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
- **规则依赖的名字：** `A-4-tx`、`T-5` 按名字匹配 `aie.ports.Tx` 和 `inSystemTx`，改名时有测试失败。
- **SDK 和产品：** 它们还没有代码，`A-18-outside`、`A-18-sdk` 目前只在样例上运行。

## 对外契约

[contracts/openapi.yaml](contracts/openapi.yaml) 是公开 API 的唯一源头，用 OpenAPI 3.1 写。现在有公共约定、身份和对话，其他资源做到时再加。接口还没有实现，`/v1` 下的路径目前都返回 404。

改接口时先改这个文件。`engine/http` 构建时从它生成 Java 接口和数据类型，包名是 `aie.http.api`。实现类写成生成接口的实现，契约一变，对不上的代码就编译不过。

`mvn verify` 检查下面这些规则：

| 规则 | 禁止什么 |
| --- | --- |
| `S-10` | 接口没写 `x-callers`；`x-callers` 和凭证对不上，比如浏览器令牌能调服务端接口；接口不要凭证，匿名会话除外 |
| `A-7` | 和基线相比有破坏性差异 |
| `A-8` | POST 不接受 `Idempotency-Key` |
| `A-11` | 路径像模型直通，如 `/v1/chat/completions`；请求里有 `model`、`temperature` 这类模型设置 |
| 蓝图 6.2 | 路径不在 `/v1` 下；缺 `Engine-Version` 或 `traceparent`；响应没有 `Request-Id`；错误不是 `application/problem+json`；列表用页码；ID 没有类型前缀；字段不是蛇形命名；时间不是 `date-time`；响应里用了封闭枚举 |
| 蓝图 6.4、6.10 | 事件或错误码和蓝图的表不一致 |
| `Q-3` | 契约里出现产品名 |

这些规则写在 `engine/guard/src/main/java/aie/guard/contract`，测试样例是 `engine/guard/src/test/resources/contract/legal.yaml`。

**破坏性差异：** 基线是 `contracts/baseline.yaml`。按蓝图 6.2，新增字段、可选参数、事件类型和枚举值都不算破坏性，删除、改名算。收紧请求的限制也算。含义变了从文件上看不出来，要靠评审。

**更新基线：** 基线只有两种情况才改。发布新的日期版本时，把 `openapi.yaml` 复制过去，让新增的内容也受保护。确实要做破坏性变更时，先得到批准，再更新基线。

**枚举：** 响应里写 `x-extensible-enum`，不写 `enum`，这样新增值不会让客户端出错。请求里可以用 `enum`。

**流式事件：** 写在 `x-stream-events`。事件名就是 SSE 的 `event`，它的内容是 `data` 里的 JSON。

**流式接口：** 生成的方法只覆盖 JSON。`text/event-stream` 那一路要手写，第 7 点做。

## CI 和依赖规则

每次推送，GitHub Actions 运行 [ci.yml](.github/workflows/ci.yml)。它只做一件事：用固定哈希的 JDK 和 Maven 运行 `engine/verify.sh`。

`verify.sh` 从空的 Maven 仓库开始构建，再检查这次构建实际下载了什么，所以 CI 不用缓存。

| 红线 | 规则 | 什么时候检查 |
| --- | --- | --- |
| S-8 | 依赖和插件都写死版本，传递依赖也算；只从 Maven Central 下载 | `mvn verify`，由 Maven Enforcer 检查 |
| S-8 | 构建下载的每个文件都记在 `engine/dependency-lock.txt`，哈希一致 | `verify.sh` |
| S-8 | 每个版本都发布满 7 天，日期从 Maven Central 读取 | `verify.sh` |
| S-8 | CI 工具固定到提交、digest 或哈希，发布满 7 天；工作流只读，不用密钥，不能跳过检查 | `mvn verify` |
| S-8 | `deploy` 下 Compose 文件的镜像固定到 digest，发布满 7 天，端口只绑定 `127.0.0.1` | `mvn verify` |
| S-8 | 有 `package.json` 时，工作区必须有锁文件、固定的 pnpm 和 7 天等待，不许依赖运行构建脚本 | `mvn verify` |
| S-9 | `engine.jar` 里每个第三方 jar 都至少有一个允许的许可 | `verify.sh` |

这些规则写在 `engine/guard/src/main/java/aie/guard/supply` 和 `engine/pom.xml`。POM 里的跳过开关和 `.mvn` 目录也会让构建失败，否则一个配置就能关掉检查。

**合并：** 分支的 CI 通过后才合并到 `main`。

**改依赖：** 改完 `pom.xml` 后运行下面的命令，再把 `engine/dependency-lock.txt` 一起提交：

```bash
engine/verify.sh --update-lock
```

锁文件和 Maven 版本有关，所以 `verify.sh` 要求正好是 Maven 3.8.7。

**新版本未满 7 天：** 检查会失败，等够 7 天再合并。紧急安全补丁经人工批准后，把版本写进 `engine/release-age-exceptions.txt`，写明批准日期、批准人和原因。

**许可：** `engine/license-policy.txt` 列出允许的许可名称，不在名单里的名称都算失败。POM 列出多个许可时可以任选一个，所以 Logback（EPL-2.0 或 LGPL-2.1）和 `jakarta.annotation-api`（EPL-2.0 或 GPL-2.0 加类路径例外）都按 EPL-2.0 使用。

**升级 CI 工具：** 改 `ci.yml` 里的固定值，并在同一行注释里写 `released YYYY-MM-DD`。这个日期由改的人填写，构建不会联网核对。

**前端：** 还没有 `package.json`，pnpm 规则目前只在样例上运行。规则里的设置名按 pnpm 12 写，选定 pnpm 版本时要核对一遍。`minimumReleaseAgeExclude` 现在是禁用的，所以 npm 的紧急补丁还没有例外通道，到时和前端一起定。

**已知限制：**

- `runs-on: ubuntu-24.04` 的宿主镜像由 GitHub 更新，固定不了。JDK 和 Maven 按哈希下载，不用它自带的。任务不放在容器里跑，因为数据库测试的端口只绑定本机，容器里访问不到。
- 许可检查读的是 POM 里写的许可，不分析 jar 里的代码。
- 引擎镜像还没有，S-9 现在检查的是 `engine.jar`。写镜像时要把基础镜像一起考虑。
