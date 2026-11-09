# Intergent

AI 引擎。架构见[蓝图](docs/ai-engine-architecture-blueprint.md)，v1.1 已冻结，改动规则见蓝图 17.3。开发顺序见[第一阶段计划](docs/phase-1-plan.md)。

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

推送前运行完整检查，约 4 分钟。GitHub Actions 运行的是同一个脚本：

```bash
engine/verify.sh
```

## 开发数据库

```bash
deploy/dev/up.sh
```

这条命令启动引擎自己的 PostgreSQL，端口是 `127.0.0.1:15432`，容器名 `aie-postgres`，都和 Brix 分开。第一次运行会生成随机密码和会话签名密钥，写在 `deploy/dev/.env`，这个文件不进仓库。以前生成的 `.env` 里没有签名密钥，再运行一次就会补上；已经有的密钥不会被换掉，否则已经发出去的令牌会全部失效。

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

**新表：** 照 `V1__create_tenants.sql` 写，这是蓝图 5.6 的模板：行级安全、`FORCE`、租户策略、授权。有序列时序列也要授权给 `engine_app`。带 `tenant_id` 的表还要加一条样例行，见[租户隔离](#租户隔离)。

**系统表和系统函数：** 先写进名单 [SystemObjects.java](engine/app/src/test/java/aie/app/SystemObjects.java)，再照 [V1__templates.sql](engine/adapters/store-postgres/src/test/resources/migrations/templates/sample/V1__templates.sql) 写，这是蓝图 7.6 的模板。系统函数有四个容易错的地方：

- **用 PL/pgSQL：** `migrate` 关了 `row_security`，`LANGUAGE sql` 的函数读 `FORCE` 表时建不起来。PL/pgSQL 建函数时不检查里面的 SQL，所以每个系统函数都要有调用它的测试。
- **先撤销 PUBLIC 权限，再移交所有者：** 移交之后 `engine_owner` 再撤销不报错，但不起作用。
- **移交时临时授权：** 新所有者要有 schema 的 `CREATE` 权限才能移交，移交后马上收回，否则目录检查失败。
- **输出列和表里的列同名时写上表名：** `RETURNS TABLE` 的列在函数体里也是变量，不写表名，调用时会报列名有歧义。

**改已有的系统函数：** 函数归 `engine_system` 所有，`engine_owner` 改不了它。照 [V6__revoke_api_keys.sql](engine/core/src/main/resources/db/migration/tenancy/V6__revoke_api_keys.sql) 写：先临时授予 schema 的 `CREATE`，`SET ROLE engine_system` 后用 `CREATE OR REPLACE` 改写，再 `RESET ROLE`，收回授权。`CREATE OR REPLACE` 保留所有者和 `EXECUTE` 授权，但 `SECURITY DEFINER` 和 `search_path` 要重新写上，漏了目录检查会失败。函数读到新列时，记得给 `engine_system` 加这一列的读权限。

现有三个系统函数，`engine_system` 只能读它们用到的列，而且只能读不能写：

| 函数 | 做什么 | 读哪些列 |
| --- | --- | --- |
| `tenancy_find_api_key` | 按哈希找到正在使用的密钥属于哪个租户和应用，已吊销和已过期的都找不到 | 密钥表六列 |
| `tenancy_find_lower_applications` | 判断一个租户是不是调用方的直接下级，并列出它在某个环境的应用，给 `On-Behalf-Of` 用 | 租户表两列、应用表三列 |
| `tenancy_find_site_key` | 按哈希找到正在使用的站点键属于哪个租户和应用，再说明请求的来源在不在它的名单里。来源名单本身不出数据库。已吊销的找不到，和没发过的一样 | 站点键表六列 |
测试函数自己固定的 `search_path` 时，要先给 `engine_app` 临时放开建临时表的权限。[create-roles.sql](deploy/db/create-roles.sql) 已经收回了这个权限，这是第一层防护；不放开的话，测到的只是这一层。

**改租户表的数据：** 表所有者也受 `FORCE` 限制，所以 `migrate` 连接设了 `row_security = off`，忘了解除时直接报错，不会静默更新 0 行。在同一个脚本里先 `NO FORCE`，改完再 `FORCE`。

## 租户隔离

红线 T-2 由 [TenantIsolationTest.java](engine/app/src/test/java/aie/app/TenantIsolationTest.java) 检查，CI 每次都跑。它用真实脚本建库，从数据库目录里找出所有带 `tenant_id` 的表，再以 `engine_app` 经 `DatabasePort` 逐张验证：

| 情况 | 结果 |
| --- | --- |
| 租户读数据 | 只看到自己的行，上级租户也看不到下级的行 |
| 不带条件地改或删 | 只影响自己的行，别的租户不受影响 |
| 写别的租户的行，或把自己的行改给别的租户 | 被行级安全拒绝 |
| `TRUNCATE` | 没有权限。行级安全管不到它，只能靠授权挡住 |
| 没有租户上下文 | 读不到、改不到、写不进 |
| 租户号是空串的行 | 没有上下文时同样碰不到 |

没有上下文的情况在新连接和复用的连接上各跑一次。两种连接状态不同：复用的连接上这个变量是空串，新连接上没有这个变量。

**新表：** 在测试的 `SAMPLES` 里加一条样例行，父表排在子表前面。不加时这个测试类全部失败，所以新表漏不掉。

这个测试只看得到样例行用到的值。红线 T-1 和 T-5 的迁移部分由目录检查 [CatalogCheck.java](engine/adapters/store-postgres/src/test/java/aie/adapters/storepg/CatalogCheck.java) 管。它在所有脚本跑完后读数据库目录，所以后面的脚本撤掉前面的保护也逃不掉：

| 对象 | 要求 |
| --- | --- |
| 名单之外的表 | 有 `NOT NULL` 的 `tenant_id`；行级安全开启并 `FORCE`；策略和模板逐字一致；给 `engine_app` 的授权不超过增删改查，不对 PUBLIC 开放 |
| 表上的其他策略 | 只能是给 `engine_system` 的只读策略 |
| 物化视图 | 不允许，行级安全管不到它 |
| 所有表 | 所有者是 `engine_owner` |
| `SECURITY DEFINER` 函数 | 在名单里；`search_path` 固定为 `engine, pg_temp`；不对 PUBLIC 开放；所有者是 `engine_system` |
| `engine_system` | 平时不能在 schema 里建对象 |
| 名单 | 名单上的表和函数必须存在 |

## 启动

一个程序包，多种角色，用 `--role` 选择。`api` 启动时要连数据库，所以先按[开发数据库](#开发数据库)建好表，再运行：

```bash
deploy/dev/api.sh
```

```bash
curl http://127.0.0.1:18080/healthz
```

真实单元这样运行：

```bash
ENGINE_DATABASE_URL=jdbc:postgresql://数据库地址/engine ENGINE_APP_PASSWORD=... ENGINE_SESSION_KEY=... java -jar engine.jar --role=api
```

`api` 总是用 `engine_app` 连接，地址里写的用户和会话参数都不起作用。

**会话签名密钥：** `ENGINE_SESSION_KEY` 是必填项，用来签发会话令牌。它至少要有 256 位随机数，写成 base64，可以用 `openssl rand -base64 32` 生成。开发环境里，这把密钥由 `up.sh` 生成，`api.sh` 读出来交给 `api`。

`api` 在连数据库之前就检查这把密钥。下面任何一种情况都会拒绝启动，退出码 1：

- 没给密钥；
- 不是 base64；
- 不到 256 位；
- 旧密钥和新密钥相同。

报错里会写出变量名和实际位数，但不会写出密钥本身。

**换签名密钥：** 一个 `api` 时按这几步换：

1. 把现在的密钥挪到 `ENGINE_SESSION_PREVIOUS_KEY`，再给 `ENGINE_SESSION_KEY` 生成一把新的，然后重启 `api`。从这时起，新令牌用新密钥签发；旧密钥只用来校验，所以之前发出去的令牌照样能用。
2. 等满 1 小时，旧令牌就都过期了。这时去掉 `ENGINE_SESSION_PREVIOUS_KEY`，再重启一次。

开发环境里，这两个值都写在 `deploy/dev/.env`。

有多个 `api` 时，滚动重启的那段时间里，还没重启的实例不认新密钥。要避免这一点，就多加一步：先把新密钥作为 `ENGINE_SESSION_PREVIOUS_KEY` 发给所有实例，等它们都认得以后，再按上面两步切换。

**连接池：** `api` 持有一个连接池，最多 10 条连接。请求跑在虚拟线程上，同时进来的请求再多，数据库连接也不会超过 10 条，多出来的请求排队等空闲连接。租户号只在一个事务里有效，同一条连接上的下一个事务不带租户。`api` 停止时关掉所有连接；启动失败时也一样，比如端口被占用。

**启动自检：** `api` 先检查 `engine_app`，通过了才打开端口，这是蓝图 7.6 和红线 T-5 的要求。用错角色时程序照常运行，只是隔离悄悄失效，所以下面任何一条成立都拒绝启动，退出码 1：

| 情况 | 为什么拒绝 |
| --- | --- |
| 是超级用户，或有 `BYPASSRLS` | 行级安全对它不起作用 |
| 拥有表 | 所有者能解除 `FORCE` |
| 是 `engine_system` 的成员 | 这个角色能跨租户读 |
| 连不上数据库、密码不对、一直没有回应（约 20 秒后放弃） | 查不了就不能当成合规 |

`worker`、`admin` 实现时也要先过这道检查。

| 角色 | 现状 |
| --- | --- |
| `api` | 先检查会话签名密钥，再过启动自检、建连接池，然后提供 HTTP 服务，目前有 `/healthz`、新建和吊销密钥 |
| `migrate` | 运行数据库迁移，完成后退出 |
| `admin` | 开通租户和应用，完成后退出，见[开通租户](#开通租户) |
| `worker` | 还没实现，打印提示后退出 |

`/healthz` 给运维探活用，不属于公开 API。

默认只绑定 `127.0.0.1:18080`，这是蓝图 12.6 的要求。换端口用 `--server.port=18081` 或环境变量 `SERVER_PORT`。

| 退出码 | 含义 |
| --- | --- |
| 0 | 正常结束 |
| 1 | 启动失败，如端口被占用；会话签名密钥不合格；启动自检不通过；迁移失败 |
| 2 | 角色参数不对：没给、给了多个或不认识；`admin` 的命令或选项不对 |
| 3 | 角色还没实现 |
| 143 | 收到 SIGTERM，处理完请求后退出 |

## 开通租户

租户、应用和第一把密钥不走公开 API，用 `admin` 角色在命令行开通，这是蓝图 12.5 的要求。开发环境先按[开发数据库](#开发数据库)建好表，再运行：

```bash
TENANT=$(deploy/dev/admin.sh create-tenant)
APP=$(deploy/dev/admin.sh create-application --tenant="$TENANT")
read -r KEY_ID API_KEY <<< "$(deploy/dev/admin.sh create-key --tenant="$TENANT" --application="$APP")"
```

| 命令 | 选项 | 输出 |
| --- | --- | --- |
| `create-tenant` | `--parent=<租户号>`：上级租户，可以在别的数据库或单元 | 新租户号，如 `ten_01M6...` |
| `create-application` | `--tenant`：必填；`--environment=test\|live`：默认 `test`；`--data-class`：默认 `internal` | 新应用号，如 `app_01M6...` |
| `create-key` | `--tenant`、`--application`：都必填 | 一行：密钥号和密钥，如 `key_01M6... aie_test_dev_...` |
| `create-site-key` | `--tenant`、`--application`、`--origins`：都必填；`--origins` 用逗号隔开，如 `https://shop.example.com,http://localhost:3000` | 一行：站点键号和站点键，如 `site_01M6... aie_site_test_dev_...` |
| `revoke-site-key` | `--tenant`、`--site-key=<站点键号>`：都必填 | 吊销了的站点键号 |

- **输出：** 标准输出只有新 ID，脚本可以直接接住。出错写到标准错误，退出码 1；命令或选项写错，退出码 2。
- **连接：** 和 `api` 一样先过启动自检，再用 `engine_app` 连接。地址里写的用户和会话参数都不起作用。
- **单元码：** 新租户的单元码取自 `ENGINE_CELL_CODE`，必须是 1 到 8 位小写字母和数字，因为它会写进密钥。开发服务器不是单元，用 `dev`。
- **API 版本：** 新应用锁定当前版本 `2026-11-05`。
- **终端用户：** 宿主的用户在引擎里叫终端用户，不用开通。引擎不管账号，只存宿主给的外部用户号；每个应用里，一个外部用户号对应一个随机生成的 `eu_...` 号，其他记录都只引用这个号，因为外部用户号常常是手机号（蓝图 7.1、7.5）。两个请求同时带来同一个新外部用户号，也只会建出一个终端用户。
  - **匿名访客：** 没有外部用户号。每换一次匿名令牌，就新建一个没有外部用户号的终端用户，所以同一个访客的不同会话对不上号。按外部用户号查找时永远找不到它们；查找必须带外部用户号，不带直接拒绝。
  - **已知不足：** 匿名终端用户每个会话一行，会越积越多。按蓝图 7.5 清理过期的匿名终端用户是第 27 点；在这之前，第 26 点的限流能挡住刷量。
- **会话令牌：** 宿主后端用密钥给终端用户换一个短期令牌，再交给浏览器（蓝图 6.5）。
  - **内容：** 令牌是 HS256 签名的 JWT，写明租户、应用、终端用户、身份等级、权限范围和宿主绑定的上下文。宿主给了语言时，也写进去。
  - **语言：** 宿主给的 `locale` 放在 `loc` 里，以后建对话时默认用它（M2）。`loc` 只用标准的 BCP 47 写法，比如 `ja-jp` 写成 `ja-JP`、旧代码 `iw` 写成 `he`；最长 35 个字符，和契约的 `Locale` 一样，`SessionTokensTest` 核对两边的上限。`loc` 不是标准写法的令牌，一律当作无效。
  - **期限：** 固定 1 小时过期。
  - **校验：** 只看签名和过期时间，不查表（蓝图 7.6）。只认 HS256。头部写了别的算法（包括 `none`）、内容被改过、密钥不认识或令牌已过期，一律当作无效，而且不说明原因。
  - **轮换：** 头部的 `kid` 是签名密钥 SHA-256 的前 8 字节。轮换时用新密钥签发，旧密钥签的令牌在过期前照样能验过。
  - **机密：** 浏览器能读出令牌的内容，所以上下文里不放机密。
  - **签名密钥：** 由环境变量给出，见[启动](#启动)。
  - **大小：** 浏览器把令牌放在请求头里发送，很多服务器把一个请求头限制在 8 KB。所以契约限制了写进令牌的内容，这些限制在 2026-11-09 获批：
    - 权限范围最多 20 个，每个 1 到 64 个字符；
    - 上下文最多 10 项，键名 1 到 40 个字符，值最多 200 个字符；
    - 权限范围和上下文的键名只能用字母、数字和 `_ . : -`；
    - 权限范围和上下文合起来，写成紧凑的 UTF-8 JSON 不超过 2048 字节。

    这样令牌最大约 3.1 KB。A-7 拦住这些限制被收紧，`SessionLimitsTest` 拦住它们被放宽。
  - **换令牌：** 用 `POST /v1/sessions`，见[接口约定](#接口约定)。超过上面大小的请求返回 400；签发时还会再查一遍，所以哪条路径都签不出更大的令牌。
- **密钥：** 形如 `aie_test_dev_` 加 40 位随机字母和数字。前面是应用的环境和租户的单元码，不查库就能看出来。数据库只存 SHA-256 哈希，检查约束挡住任何不像哈希的值。明文只在发放时打印这一次，丢了只能再发一把。一个应用可以有多把密钥，不用的可以吊销，见[接口约定](#接口约定)。
- **站点键：** 放在宿主网页里，浏览器用它换匿名令牌（蓝图 6.5）。它是公开的，但和密钥一样只存哈希，因为蓝图 7.6 规定按哈希查找。用它换令牌的 `POST /v1/anonymous-sessions` 还没接上，在第 12 点后面几步做。这些规则 2026-11-09 获批：
  - **格式：** 形如 `aie_site_test_dev_` 加 40 位随机字母和数字，前面是应用的环境和租户的单元码。它和服务端密钥的格式不会互相混淆。明文只在发放时打印这一次，丢了就再发一把。
  - **来源：** 每把站点键列出 1 到 10 个确切的来源，比如 `https://shop.example.com`，只有从这些网站来的请求才能换令牌。来源必须是 https，只有开发用的 `http://localhost` 和 `http://127.0.0.1` 例外。不支持通配符和路径；国际化域名要写成 punycode。来源按浏览器发送的写法保存：小写，去掉默认端口和末尾的斜杠，重复的只留一个。数据库的检查约束也会拦住不像来源的值。
  - **吊销：** 用 `revoke-site-key` 吊销。那一行保留，记下吊销时间；已经吊销的不能再吊销一次。
  - **查找：** 先看格式，格式不对就不查数据库。格式对的，按哈希调用系统函数 `tenancy_find_site_key`，同时带上请求的来源，来源按浏览器的写法读。结果有三种：允许，就是来源在名单里；来源不对，包括请求没有来源或来源是 `null`；不认识，就是没发过或已吊销。等匿名会话接口接上（12-5），这三种分别是签发令牌、回 403、回 401。
  - **防护的边界：** 来源校验只能防别的网站嵌入这把站点键，防不了脚本伪造来源，所以匿名令牌的权限最低。按站点键限流是第 26 点，引擎对外公开前必须做完。

真实单元这样运行：

```bash
ENGINE_DATABASE_URL=jdbc:postgresql://数据库地址/engine ENGINE_APP_PASSWORD=... ENGINE_CELL_CODE=jp1 java -jar engine.jar --role=admin create-tenant
```

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

[contracts/openapi.yaml](contracts/openapi.yaml) 是公开 API 的唯一源头，用 OpenAPI 3.1 写。现在有公共约定、身份和对话，其他资源做到时再加。目前实现了 `POST /v1/api-keys`、`DELETE /v1/api-keys/{key_id}` 和 `POST /v1/sessions`。建匿名会话先返回 404，等第 12 小点实现；其他 `/v1` 路径带有效密钥时也返回 404。

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

**破坏性差异：** 基线是 `contracts/baseline.yaml`。按蓝图 6.2，新增字段、可选参数、事件类型和枚举值都不算破坏性，删除、改名算。收紧请求的限制也算。含义变了从文件上看不出来，要靠评审。A-7 还不比对 `propertyNames`，所以上下文键名的限制再收紧时，它报不出来。

**更新基线：** 基线只有两种情况才改。发布新的日期版本时，把 `openapi.yaml` 复制过去，让新增的内容也受保护。确实要做破坏性变更时，先得到批准，再更新基线，而且只改获批的那几处。经批准改过两次：
- 2026-11-08：`CreatedApiKey.key` 不再总是出现，因为重放新建密钥的回答时不带明文。
- 2026-11-09：`CreateSessionRequest` 的权限范围和上下文收紧了，具体大小见[开通租户](#开通租户)一节的“会话令牌”；`Session.token` 不再总是出现，因为重放时不带令牌。

**枚举：** 响应里写 `x-extensible-enum`，不写 `enum`，这样新增值不会让客户端出错。请求里可以用 `enum`。

**流式事件：** 写在 `x-stream-events`。事件名就是 SSE 的 `event`，它的内容是 `data` 里的 JSON。

**流式接口：** 生成的方法只覆盖 JSON。`text/event-stream` 那一路要手写，第 7 点做。

## 接口约定

这些约定在 `engine/http` 实现，对所有路径生效，不只是 `/v1`。

**`Request-Id`：** 每个响应都带一个请求号，包括 Tomcat 自己拒绝的请求，如 `req_01M6T3X5Q1GRQ53EE6MPP5WVKP`。调用方报问题时报这个号，日志里搜它就能找到这个请求：

```text
2026-11-07T13:21:07.503+09:00  INFO 4041939 --- [omcat-handler-1] [req_01M6T3X5Q1GRQ53EE6MPP5WVKP] [4bf92f3577b34da6a3ce929d0e0e4736] aie.http.Xxx : ...
```

请求号总是引擎自己生成，客户端传来的不用，否则能伪造日志，或者把问题指到别人的请求上。

**`traceparent`：** 调用方按 W3C Trace Context 传这个请求头，它的 trace ID 就会进日志，就是上面第二个方括号，这样宿主和引擎的日志能对起来。没传时这个方括号是空的。

- **值不合法时忽略：** 请求照常处理，这是 W3C 的规定。两个 `traceparent` 头也算不合法。
- **响应不返回：** 响应只带 `Request-Id`。
- **传给外部调用：** 现在引擎还没有外部调用，等 M3 接 OpenTelemetry 时做。

**认证：** `/v1` 下的请求都要带 API 密钥，写法是 `Authorization: Bearer aie_test_dev_...`，这是 RFC 6750 的格式。密钥用 `admin` 发放，见[开通租户](#开通租户)。认证排在版本检查前面，因为以后版本要按调用方的应用决定：

| 情况 | HTTP | `code` |
| --- | --- | --- |
| 没带密钥，或用的不是 `Bearer` | 401，带 `WWW-Authenticate: Bearer` | `unauthorized` |
| 密钥格式不对、查不到、已吊销或已过期 | 401，带 `WWW-Authenticate: Bearer error="invalid_token"` | `unauthorized` |
| 带了两个 `Authorization` 头 | 400 | `invalid_request` |
| `On-Behalf-Of` 不是一个租户号，或带了两个 | 400 | `invalid_request` |
| `On-Behalf-Of` 指的不是这个密钥所属租户的直接下级 | 403 | `forbidden` |
| 下级租户在密钥的环境里没有应用，或有不止一个 | 403 | `forbidden` |
| 查找时数据库出错 | 500 | `internal_error`，不会放行 |

- **怎么查：** 先看格式，格式不对就不查数据库。格式对的，按 SHA-256 哈希调用系统函数 `tenancy_find_api_key`，找到租户和应用。之后的处理都在这个租户的事务里做。
- **调用方：** `/v1` 的处理代码从请求属性 `aie.http.caller` 读调用方，里面有密钥号、租户号和应用号。
- **密钥不进日志：** 不管密钥对不对，请求成功还是失败，日志里都没有它。
- **不需要密钥：** `/healthz` 和 `/v1` 以外的路径。

**代下级租户操作（`On-Behalf-Of`）：** Brix 这类平台型宿主用自己的密钥，加上 `On-Behalf-Of: <下级租户号>`，替它的下级租户调用。这和 Stripe Connect 的做法一样：

- **算哪个应用：** 请求按这个下级租户、以及它和密钥同环境的那个应用来处理。测试密钥对应测试应用，正式密钥对应正式应用。
- **不猜：** 同环境的应用没有或不止一个，就返回 403，不会挑一个。
- **只算直接下级：** 下级的下级、无关租户、自己的租户，都不行。
- **密钥号：** 请求里的密钥号仍是平台自己的，以后审计能看出是谁代为操作。
- **其他功能照常：** 新建、吊销密钥和幂等记录，都记在下级租户的应用名下。

**吊销密钥：** `DELETE /v1/api-keys/{key_id}` 吊销同一个应用的密钥，成功返回 204，吊销后马上失效，再用就是 401：

- **能吊销哪些：** 同一个应用的密钥，包括这次请求用的这把，最后一把也行。密钥泄露时必须能马上停掉；之后要用，再让 `admin` 发一把。
- **读作不存在：** 别的应用或别的租户的密钥、不存在的、已经吊销或已经过期的，都返回 404 `not_found`。
- **记录保留：** 密钥那一行不删，记下吊销时间，以后能追查。

**新建密钥：** `POST /v1/api-keys` 给调用方的应用再发一把密钥，返回 201，明文只在这次回答里出现。新旧密钥可以同时用，所以轮换的做法是：先建新的，换上，再吊销旧的。

- **名称：** 可以不写，最多 100 个字符，按字符算，不按字节。
- **环境和单元码：** 跟着调用方的应用和租户，和 `admin` 发的密钥一样。
- **重试：** 带同一个 `Idempotency-Key` 重试，拿回同一个密钥号，但没有明文，因为明文从不保存。第一次的回答丢了的话，吊销这个密钥号，换一个新的 `Idempotency-Key` 再建一把。
- **过期时间：** `expires_at` 可以不写，写了必须是将来的时间，否则返回 400。过了这个时间，密钥和吊销了一样返回 401，那一行仍然保留。不写就一直有效，直到被吊销。

**换会话令牌：** 宿主后端用 `POST /v1/sessions` 给自己的一个用户换短期令牌，再交给浏览器。请求只有 `external_user_id` 是必填的。成功返回 201，回答里有令牌、过期时间、终端用户号、身份等级和权限范围。

```bash
curl -s -X POST http://127.0.0.1:18080/v1/sessions -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"external_user_id":"host-user-1","scopes":["orders:read"],"context":{"order_id":"A-1001"},"locale":"ja-JP"}'
```

- **终端用户：** 同一个应用里，同一个外部用户号第一次出现时新建终端用户，以后每次都是同一个。
- **身份等级：** 不写就是 `verified`，也可以写 `unverified`。`anonymous` 属于站点键，这里返回 400。
- **检查：** 外部用户号、权限范围、上下文、语言和合计大小，任何一项不合格都返回 400 `invalid_request`，并说明是哪一项，见[开通租户](#开通租户)一节的“会话令牌”。检查都在新建终端用户之前，所以被拒的请求不留下终端用户。
- **语言：** 按标准写法写进令牌，比如 `ja-jp` 写成 `ja-JP`。
- **代下级租户：** 带 `On-Behalf-Of` 时，终端用户和令牌都属于下级租户的那个应用。
- **重试：** 带同一个 `Idempotency-Key` 重试，拿回同样的回答，只是没有令牌，因为令牌从不保存。第一次的回答丢了的话，换一个新的 `Idempotency-Key` 再换一个。

**`Engine-Version`：** `/v1` 下的请求可以传日期版本，如 `Engine-Version: 2026-11-05`。现在只有这一个版本，不传时也用它；以后按应用锁定的版本走。传别的版本返回 400 `invalid_request`，不会悄悄用另一个版本回答。版本写在 `EngineVersionFilter` 和契约的 `info.version`，测试保证两边一致。

**`Idempotency-Key`：** `/v1` 下的 POST 可以带这个头，重试时同一件事不会做两遍。它排在认证和版本检查之后，所以知道调用方的租户和应用；键按租户和应用隔离，保留 24 小时。判断写在 `aie.core.tenancy.api.IdempotencyKeys`，HTTP 这一层是 `IdempotencyFilter`：

| 同一个键再来一次 | 结果 |
| --- | --- |
| 第一次 | 拿到键，执行请求 |
| 上一次还在处理 | `idempotency_key_in_use`（409） |
| 上一次已经完成 | 原样返回上一次的结果，失败也一样 |
| 请求内容不同 | `idempotency_key_reused`（422），不管上一次完成没有 |
| 上一次的进程中途死了，5 分钟没有完成 | 这一次接管，重新执行 |
| 超过 24 小时 | 当作新键 |

- **算不算同一个请求：** 比较方法、路径和请求体的 SHA-256。同一个键用在别的接口上，也算请求不同。
- **重放：** 状态码和正文都原样返回，`Request-Id` 是这次请求自己的。
- **哪些不管：** 没带这个头的 POST 照常执行；别的方法带了这个头也不理会。在认证、版本检查这些地方就被拒的请求，不占用这个键，改好以后可以用同一个键再发。
- **头和请求体的限制：** 头为空、超过 255 个字符或带了两个，返回 400 `invalid_request`。带这个头的请求体最多 1 MiB，超过返回 413，因为算指纹要把整个请求体读进内存。
- **不存的字段：** 回答里有密钥这类秘密时，控制器在请求属性 `IdempotencyFilter.NOT_STORED` 里写上这些字段。存下来的那份去掉它们，重放时也就没有了。现在用到它的是新建密钥的 `key`。
- **还没做：** 过期记录的清理是第 20 点，在那之前表只增不减；流式请求在 M2 做。

结果和业务数据不在同一个事务里，因为事务不跨模块。进程刚做完业务、还没存结果就死了的话，接管的那次会再执行一遍。 存结果的那一步失败时，回答照样发给调用方，并记一条错误日志；5 分钟后的重试会再执行一遍。

**错误格式：** 所有错误都是 `application/problem+json`，带稳定的 `code`。客户端看 `code`，不要解析 `title` 和 `detail`：

```json
{"type":"about:blank","title":"Method Not Allowed","status":405,"code":"invalid_request","detail":"Method 'POST' is not supported."}
```

| 情况 | HTTP | `code` |
| --- | --- | --- |
| 没带密钥，或密钥无效 | 401 | `unauthorized` |
| 没有权限，比如代一个不是下级的租户操作 | 403 | `forbidden` |
| 路径不存在 | 404 | `not_found` |
| 请求格式不对：参数、JSON、方法、媒体类型、请求体太大 | 400、405、406、413、415 | `invalid_request` |
| 同一个 `Idempotency-Key` 的请求还在处理 | 409 | `idempotency_key_in_use` |
| 同一个 `Idempotency-Key` 配了不同的请求 | 422 | `idempotency_key_reused` |
| Tomcat 自己拒绝：路径非法、请求行错误、缺 `Host`、请求头过大 | 400 | `invalid_request` |
| 引擎内部出错 | 500 | `internal_error` |

错误在三个地方转换，这样无论在哪一层失败，格式都一样：

| 在哪里失败 | 谁来转换 |
| --- | --- |
| 控制器和 Spring MVC | `ProblemAdvice` |
| 过滤器抛异常或调用 `sendError` | `ProblemErrorController` |
| 请求还没到程序，Tomcat 自己拒绝 | `ProblemReportValve`，这种请求的 `Request-Id` 也在这里生成 |

500 不告诉调用方任何内部信息。原因写在日志里，用 `Request-Id` 能查到。`internal_error` 的来历见 [ADR-0001](docs/adr/0001-internal-error-code.md)。

**ID：** `aie.core.Ids` 生成蓝图 6.2 的 ID：类型前缀、时间、随机数。时间在前，所以后生成的排在后面；随机部分让人猜不到相邻的 ID。

## CI 和依赖规则

每次推送，GitHub Actions 运行 [ci.yml](.github/workflows/ci.yml)。它只做一件事：用固定哈希的 JDK 和 Maven 运行 `engine/verify.sh`。

`verify.sh` 从空的 Maven 仓库开始构建，再检查这次构建实际下载了什么，所以 CI 不用缓存。

GitHub 和 Maven Central 偶尔会有几分钟返回 5xx 或超时，所以下载 JDK 和 Maven 时会自动重试：出错后每 30 秒重试一次，最多重试 10 分钟，每次下载最长 5 分钟。工作流里的下载都必须用 curl 并带 `--retry`，由 `mvn verify` 检查，以后新加的下载也不会漏掉重试。

构建时从 Maven Central 下载依赖也会重试。Maven 3.8.7 默认遇到第一个 503 就放弃，所以 `verify.sh` 给 mvn 加了几项设置：
- 遇到 408、429、500、502、503 或 504 时，每 5 秒重试一次，最多重试 10 次；
- 连接断开时，最多重试 3 次；
- 连接池里的连接满 25 秒就不再复用，因为执行机的网络会掐断闲置大约 4 分钟的连接。

这些设置写在 `verify.sh` 里，由 `mvn verify` 检查。它们没有放进 `.mvn`，因为 `.mvn` 目录会让构建失败。

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
- 下载故障超过 10 分钟时，重试也救不回来，要等恢复后重新运行。没有用另外两种办法兜底：
  - 缓存：会掩盖干净构建到底下载了什么；而且缓存在校验之前就会解压，构建一旦被攻破，缓存就可能被投毒。
  - 带令牌下载：Actions 的令牌能读这个私有仓库的代码。
- 下载依赖时如果连接卡住不动，Maven 默认要等 30 分钟才算超时，会先撞上 CI 的 25 分钟上限。
- 许可检查读的是 POM 里写的许可，不分析 jar 里的代码。
- 引擎镜像还没有，S-9 现在检查的是 `engine.jar`。写镜像时要把基础镜像一起考虑。
