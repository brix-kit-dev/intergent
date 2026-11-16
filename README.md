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

**打包后的 jar：** `mvn verify` 打包之后，`PackagedJarIT` 会像运维那样用 `java -jar` 真的运行这个 jar，连一个全新的 PostgreSQL：
- `migrate` 应用的脚本数，每个模块都和源码里一样多；再跑一次，一个也不应用。
- `admin create-tenant` 在标准输出只打印新租户号。
- `api` 通过启动自检，`/healthz` 返回 200，没带密钥的调用得到 401；收到 SIGTERM 后退出，退出码 143。

其他测试用的是 Maven 的类路径，看不出 jar 里缺了什么，比如 Flyway 在嵌套 jar 里找不到脚本。所以它排在打包之后的 integration-test 阶段，`mvn test` 不跑它。它用 JUnit 标签 `packaged-jar` 和打包前的测试分开，守卫会检查这个执行项还在。单独跑：

```bash
cd engine
mvn verify -pl app -am -Dtest=PackagedJarIT -Dsurefire.failIfNoSpecifiedTests=false
```

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
- `insideTx()` 回答当前线程在不在事务里，不占连接。事务里不能调外部服务（`A-14`），所以网关调模型前先问它，见[网关](#网关)。
- 事务结束后，或者换一个线程，`Tx` 就不能再用，否则语句会跑在事务外面。
- 事务有超时。

## 数据库迁移

迁移脚本放在所属模块的目录里，如 `engine/core/src/main/resources/db/migration/tenancy`。目录名就是模块名，新模块加目录就行，不用登记。每个模块有自己的历史表 `flyway_history_<模块>`，所以版本号互不影响，模块拆走时脚本和历史一起带走。现在有两个模块：`tenancy` 和 `conversation`，后者的脚本在 `engine/modules/src/main/resources/db/migration/conversation`，也从 `V1` 编号。

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
| 数据库接受了连接却一直不回应 | 登录等 30 秒就失败，退出码 1，地址里的参数改不了这个时限。它只管登录，已经开始的 `ALTER TABLE` 再慢也不会被打断 |

**新表：** 照 `V1__create_tenants.sql` 写，这是蓝图 5.6 的模板：行级安全、`FORCE`、租户策略、授权。有序列时序列也要授权给 `engine_app`；`GENERATED ALWAYS AS IDENTITY` 的列不用，比如消息表的顺序号，插入时数据库自己取号。带 `tenant_id` 的表还要加一条样例行，见[租户隔离](#租户隔离)。

**整数列：** 按取值范围选 `smallint`、`integer` 或 `bigint`，`Row.number` 都能读。别的类型一律报错，`numeric` 存的是整数也一样，因为下一行可能带小数；要读就在查询里转成 `bigint`。`V3__create_idempotency_keys.sql` 里“只能用 `bigint`”的注释已经过时，脚本执行过就不能改，所以留着；那一列 `response_status` 已由 `V14__integer_idempotency_status.sql` 改成 `integer`。

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
| 只追加的表，比如 `conversation_events` | 改和删都没有权限，自己的行也一样 |
| 只能由表自己的规则改的表，比如 `conversation_messages` | 直接改被表拒绝，删没有权限，自己的行也一样 |
| 写别的租户的行，或把自己的行改给别的租户 | 被行级安全拒绝 |
| `TRUNCATE` | 没有权限。行级安全管不到它，只能靠授权挡住 |
| 没有租户上下文 | 读不到、改不到、写不进 |
| 租户号是空串的行 | 没有上下文时同样碰不到 |

没有上下文的情况在新连接和复用的连接上各跑一次。两种连接状态不同：复用的连接上这个变量是空串，新连接上没有这个变量。

**新表：** 在测试的 `SAMPLES` 里加一条样例行，父表排在子表前面。不加时这个测试类全部失败，所以新表漏不掉。只追加的表用 `Sample.appendOnly` 加，只能由表自己的规则改的表用 `Sample.refusingChanges` 加，测试就改为检查改和删都被拒绝。

这个测试只看得到样例行用到的值。红线 T-1 和 T-5 的迁移部分由目录检查 [CatalogCheck.java](engine/adapters/store-postgres/src/test/java/aie/adapters/storepg/CatalogCheck.java) 管。它在所有脚本跑完后读数据库目录，所以后面的脚本撤掉前面的保护也逃不掉：

| 对象 | 要求 |
| --- | --- |
| 名单之外的表 | 有 `NOT NULL` 的 `tenant_id`；行级安全开启并 `FORCE`；策略和模板逐字一致；给 `engine_app` 的授权不超过增删改查，不对 PUBLIC 开放 |
| 表上的其他策略 | 只能是给 `engine_system` 的只读策略 |
| 租户表之间的外键 | 必须把 `tenant_id` 对到 `tenant_id`，比如 `FOREIGN KEY (tenant_id, application_id) REFERENCES tenancy_applications (tenant_id, application_id)`。数据库检查外键时不管行级安全，只按父表的号连的话，一行就能挂到别的租户的父行下面 |
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
- **测试里的密钥：** `AdminRoleTest` 用 `QuietOutput` 接住命令打印的内容，不转给控制台，所以 CI 日志里没有新发的密钥。Spring 的 `OutputCaptureExtension` 边接边转，不能用在这里，`QuietOutputTest` 会拦住改回去。测试失败时，`QuietOutput` 才把接住的内容打出来，密钥的机密部分换成 `<masked>`。断言失败时，报错里如果比对的正是密钥，仍会带出它，这时 `verify.sh` 会报出来，见[构建输出里不许有密钥](#ci-和依赖规则)。
- **连接：** 和 `api` 一样先过启动自检，再用 `engine_app` 连接。地址里写的用户和会话参数都不起作用。
- **单元码：** 新租户的单元码取自 `ENGINE_CELL_CODE`，必须是 1 到 8 位小写字母和数字，因为它会写进密钥。开发服务器不是单元，用 `dev`。
- **API 版本：** 新应用锁定当前版本 `2026-11-05`。
- **终端用户：** 宿主的用户在引擎里叫终端用户，不用开通。引擎不管账号，只存宿主给的外部用户号；每个应用里，一个外部用户号对应一个随机生成的 `eu_...` 号，其他记录都只引用这个号，因为外部用户号常常是手机号（蓝图 7.1、7.5）。两个请求同时带来同一个新外部用户号，也只会建出一个终端用户。
  - **匿名访客：** 没有外部用户号。每换一次匿名令牌，就新建一个没有外部用户号的终端用户，所以同一个访客的不同会话对不上号。按外部用户号查找时永远找不到它们；查找必须带外部用户号，不带直接拒绝。
  - **已知不足：** 匿名终端用户每个会话一行，会越积越多。按蓝图 7.5 清理过期的匿名终端用户是第 27 点；在这之前，第 26 点的限流能挡住刷量。
- **会话令牌：** 宿主后端用密钥给终端用户换一个短期令牌，再交给浏览器（蓝图 6.5）。
  - **内容：** 令牌是 HS256 签名的 JWT，写明租户、应用、终端用户、身份等级、权限范围和宿主绑定的上下文。宿主给了语言时，也写进去。
  - **号码格式：** 租户号、应用号、终端用户号分别以 `ten_`、`app_`、`eu_` 开头，后面是 1 到 60 位字母和数字，引擎自己生成的号后面是 26 位。签发和校验用同一份格式：会话里的号不合格式就签不出令牌，令牌里的号不合格式就验不过，所以不会签出一张注定验不过的令牌。
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
- **站点键：** 放在宿主网页里，浏览器用它换匿名令牌（蓝图 6.5）。它是公开的，但和密钥一样只存哈希，因为蓝图 7.6 规定按哈希查找。浏览器用 `POST /v1/anonymous-sessions` 拿它换令牌，见[接口约定](#接口约定)。这些规则 2026-11-09 获批：
  - **格式：** 形如 `aie_site_test_dev_` 加 40 位随机字母和数字，前面是应用的环境和租户的单元码。它和服务端密钥的格式不会互相混淆。明文只在发放时打印这一次，丢了就再发一把。
  - **来源：** 每把站点键列出 1 到 10 个确切的来源，比如 `https://shop.example.com`，只有从这些网站来的请求才能换令牌。来源必须是 https，只有开发用的 `http://localhost` 和 `http://127.0.0.1` 例外。不支持通配符和路径；国际化域名要写成 punycode。来源按浏览器发送的写法保存：小写，去掉默认端口和末尾的斜杠，重复的只留一个。数据库的检查约束也会拦住不像来源的值。
  - **吊销：** 用 `revoke-site-key` 吊销。那一行保留，记下吊销时间；已经吊销的不能再吊销一次。
  - **查找：** 先看格式，格式不对就不查数据库。格式对的，按哈希调用系统函数 `tenancy_find_site_key`，同时带上请求的来源，来源按浏览器的写法读。结果有三种：允许，就是来源在名单里；来源不对，包括请求没有来源或来源是 `null`；不认识，就是没发过或已吊销。匿名会话接口对这三种分别是签发令牌、回 403、回 401。
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
| `engine/adapters` | 适配器（L0），每个适配器是它的子模块。现有 `store-postgres` 和 `model-fake` | `ports` |
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

**文本扫描：** 有些红线从类的依赖关系上看不出来，守卫就直接读源码的文字来查（蓝图 16.1）。范围和名单在 2026-11-09 获批，写在 [TextRules.java](engine/guard/src/main/java/aie/guard/text/TextRules.java)，名单以后随需要增删。

| 规则 | 禁止什么 |
| --- | --- |
| `A-3` | 适配器以外的正式代码里出现模型名，比如 `gpt-4o`、`claude-sonnet-4-5`。换模型应当只改网关的配置 |
| `A-4` 表 | 模块的迁移新建的表、视图、序列、函数、索引、类型不带模块前缀（如 `tenancy_`）；模块的 SQL 碰到别的模块的东西，包括迁移里的外键和函数体，以及模块 Java 代码里的 SQL 字符串。跨模块只能走对方的 `api` 包 |
| `A-5` | 正式代码里按地区判断：字符串正好是 `cn`、`jp`、`us`；`Locale` 里代表国家的常量，比如 `Locale.JAPAN`；名字或字符串里以 `region` 开头的词，比如 `getRegion`、`REGION_PACK`。地区之间的差别应当放在区域包里 |
| `A-6` | 适配器以外的正式代码里出现宿主或行业的概念：宿主名 `brix`，以及 `cart`、`checkout`、`sku`、`invoice`、`shipment`、`booking`、`reservation`、`hotel`、`patient`、`student`、`loan` 这些只属于某个行业的词。宿主的世界只能作为数据进来，比如会话的上下文 |
| `A-13` 租户 | 数据库适配器以外的正式代码写 `app.tenant_id`：用 `SET`、`RESET` 改它，包括函数和角色上的 `SET`；用 `set_config`，它能按运行时才知道的名字改任何设置；把这个名字当字符串用在 `current_setting` 以外的地方。租户只由适配器在事务开始时写入（蓝图 5.5），在租户事务里改它就换成了别的租户 |

- **读什么：** 引擎每个模块 `src/main/java` 下的 Java 和 `src/main/resources` 下的 SQL，只看名字和字符串。注释、测试、文档、配置文件和对外契约都不读，所以注释里可以举例。守卫自己也不读，因为名单就写在它里面。
- **模型名怎么认：** 按常见模型家族的开头认，不分大小写：`gpt-`、`chatgpt`、`o1-`/`o3-`/`o4-`、`claude-`、`gemini-`、`gemma`、`llama`、`mistral`、`mixtral`、`codestral`、`qwen`、`deepseek`、`command-r`、`text-embedding-`。家族名必须在词首，所以 `ollama` 不算；驼峰写法的名字逐个词看，所以 `QwenClient` 也算。
- **地区怎么认：** 地区代码不分大小写，只认整个字符串，所以 `jp1`、`us-east-1` 不算。获批的名单里原本还有 `"eu"`，但它正是终端用户号的前缀（`eu_...`），所以拿掉了；真要按欧洲判断，多半要写到 `region`，仍会被抓住。`Locale` 的语言常量（比如 `JAPANESE`）不算。`A-5` 连适配器也查；以后做区域包时，只给加载区域包的地方开例外。
- **宿主和行业词怎么认：** 不分大小写，复数也算，但必须是完整的词，所以 `cartridge` 不算 `cart`、`Brixton` 不算 `brix`。`order` 故意不放进名单，因为 Spring 的写法和普通英语里到处都是。`A-6` 和 `A-3` 一样不查适配器，因为对接外部系统的适配器可以用那个系统的词。
- **模块和前缀怎么认：** 模块就是迁移所在的文件夹名，或者 `aie.core`、`aie.modules` 下面的包名，比如 `tenancy`。前缀就是“模块名加下划线”，所以以别的模块名加下划线开头的名字都算那个模块的。策略和触发器的名字只在各自的表里有效，不要求前缀。只查模块自己的迁移和代码；模块以外的代码（比如启动入口）不算跨模块。Java 里的名字是代码不是 SQL，只看字符串。现在只有 `tenancy` 一个模块，跨模块的那半条用两个模块的样例来测。
- **租户怎么认：** 只许读，不许写。`current_setting('app.tenant_id', true)` 可以，策略就是这样读的；`FROM tenancy_applications app WHERE app.tenant_id = ?` 里的 `app.tenant_id` 是表的列，也不算。Java 的字符串当 SQL 读，SQL 里的字符串也当 SQL 读（两个单引号还原成一个），因为 `EXECUTE` 会执行它。`pg_settings` 视图里没有 `app.tenant_id` 这种自定义设置，改它改不到租户，所以不算。只有 `adapters/store-postgres` 例外，以后加别的数据库适配器时也要给它开例外。拼字符串、换写法存心绕过的，扫描抓不全，要靠代码审查。
- **报什么：** 每处一行，写明文件、行号和认出的内容，比如 `A-3: core/src/main/java/aie/core/Pick.java:2: model name gpt-4o`、`A-5: core/src/main/java/aie/core/Route.java:3: country constant Locale.JAPAN`、`A-4: core/src/main/resources/db/migration/tenancy/V2__b.sql:1: reaches events_outbox of module events`、`A-13: core/src/main/java/aie/core/tenancy/internal/Switch.java:5: writes app.tenant_id with SET`。
- **测试：** `TextRulesTest` 在临时目录里摆出样例文件，逐条看算不算；`EngineTextTest` 在真实的引擎上跑，并确认扫描确实读到了各个模块和迁移脚本，不会因为什么都没读而通过；它还确认数据库适配器里真有写租户的地方，所以 `A-13` 的例外指的是对的文件夹。

## 模型端口和模拟模型

模型只经 `aie.ports.ModelPort` 调用，只有网关能用它（`A-2-ports`）。端口里没有任何厂商的概念：

- **一次调用：** `generate(请求, 输出)` 一直阻塞到回答结束，文本一段一段交给输出。请求写明模型号、到目前为止的对话（最后一句是用户说的）、整个回答最多用多久，以及一个取消信号。
- **怎么结束：** 完成、取消、失败（包括超时）都作为结果返回，带上到那时为止的用量。失败还写明已经交出了几段，网关据此判断能不能换模型重试，因为只能在第一段之前重试（蓝图 8.6）。抛异常只说明请求本身写错了，或者异常来自输出。
- **类型都在端口里面：** 请求、对话、用量、结果都嵌套在 `ModelPort` 里。`A-2-ports` 按这个名字匹配，所以它们一起受管。`EngineArchitectureTest` 会顺着端口的方法检查这一点。

模拟模型是适配器 `engine/adapters/model-fake`，模型号 `fake`。它在进程里回答，数据不出进程，每次回答都一样：

- **回答：** 编了号的几段：`Piece 1 of 8. `、`Piece 2 of 8. `……少一段或者多一段，一眼就能看出来。默认 8 段，每段之前等 50 毫秒。
- **用量：** 字符数当作 token 数。对话的所有轮次算输入，生成的文字算输出。
- **取消和超时：** 等待时每 10 毫秒看一次取消信号和时间，所以取消通常在 10 毫秒内生效，机器忙时会晚一些。

测试和演示可以在最后一句用户输入里加一个方括号，控制它怎么回答：

| 写法 | 意思 |
| --- | --- |
| `[fake pieces=3]` | 回答 3 段，可以是 1 到 1000 |
| `[fake delay=2s]` | 每段之前等 2 秒，写成 `200ms` 或 `2s`，最多 60 秒 |
| `[fake fail-after=2]` | 交出 2 段后失败；写 `0` 就是第一段之前失败 |
| `[fake hang-after=1]` | 交出 1 段后卡住，直到被取消或者超时 |

几项可以写在同一个方括号里，如 `[fake pieces=20 delay=1s]`。一句话里只能有一个这样的方括号，写错了回答直接失败，原因里写明错在哪。`[fakery]` 这样的写法不算指令。

## 网关

模块要用模型时只说任务，不说模型。网关 `aie.core.gateway` 按任务找路由，再从路由找模型，调用 `ModelPort`。别的模块只经它的公开接口 `aie.core.gateway.api.Gateway` 调用（`A-4`），只有网关能用模型端口（`A-2-ports`）：

- **一次调用：** `answer(请求, 输出)` 一直阻塞到回答结束。请求写明任务名、到目前为止的对话（最后一句是用户说的）和取消信号，文本一段一段交给输出。
- **怎么结束：** 完成、取消、失败（包括超时）都作为结果返回，带上到那时为止的用量。失败还写明已经交出了几段。模型给的失败原因只进网关的日志，和任务、模型号、段数写在一行；结果里没有它，所以到不了用户那里。
- **写错了就抛异常：** 没有路由的任务、格式不对的请求（没有对话、最后一句不是用户说的、缺取消信号），都是调用方的代码写错了。网关直接抛 `IllegalArgumentException`，不调模型。
- **事务里不调（`A-14`）：** 在数据库事务里调用时，网关在做任何事之前就抛 `IllegalStateException`：不做输入检查，不调模型。否则回答多长，这个事务就占着一条数据库连接多久。每次调用，网关都问一次连接池的 `insideTx()`。这个方法只看当前线程，所以在事务里把调用交给另一个线程去做，它发现不了。
- **类型自己定义：** 请求、对话、用量、结果都嵌套在 `Gateway` 里，不露出端口的类型。否则别的模块经网关间接拿到端口类型，`A-2-ports` 却看不见。`EngineArchitectureTest` 检查网关公开包里的每个类，泛型、父类型和抛出的异常都算。

网关靠两张表工作：

| 表 | 内容 | 现在 |
| --- | --- | --- |
| 注册表 | 模型号 → 适配器 | 只有 `fake` |
| 路由表 | 任务 → 模型号列表和超时，列表顺序就是优先级 | `customer_chat` → `fake`，一次回答最多 2 分钟 |

- **超时：** 每次调用都带上路由配的超时（`A-9` 的一部分）。路由的超时必须大于零，至少要有一个模型。
- **只用第一个模型：** 备用和重试到 M3 才有。
- **启动时就检查：** api 角色先打开数据库连接池，再构造网关，因为网关要问连接池当前线程在不在事务里。路由引用了注册表里没有的模型时，启动被拒绝，退出码 1，连接池随之关闭。
- **检查的位置：** 输入检查在选模型之前，输出检查在每段交给调用方之前。现在都直接放行，测试锁住了先后顺序；M3 换成信任层。
- **以后：** 两张表现在写在 `engine/app` 的 `Models` 里。接入真实模型时（M3），它们搬进区域包。

## 会话

会话模块 `aie.modules.conversation` 是第一个 L3 模块。别的模块只经它的公开接口调用（`A-4`），也就是 `aie.modules.conversation.api` 里的 `Conversations` 和 `Messages`：

- **表：** `conversation_conversations`，一行一个会话，记会话号（`conv_` 开头）、租户、应用、终端用户号、语言和创建时间。表带行级安全，迁移脚本放在模块自己的目录里。
- **归属：** 一个会话属于一个应用的一个终端用户。按会话号找时只在调用方的应用里找，所以别的租户、同租户的别的应用都找不到。应用分测试环境和正式环境，测试环境的密钥因此读不到正式环境的会话。
- **不跨模块：** 终端用户归 tenancy 管。这张表只存终端用户号，不建外键（`A-4`）。建会话时用的终端用户号，由 tenancy 的 `EndUsers` 给出。
- **语言：** 规则和会话令牌一样：不超过 35 个字符的语言标记，用标准写法，比如 `ja-JP`。可以不给。格式不对时，`Conversations` 抛 `IllegalArgumentException`。
- **建会话：** `POST /v1/conversations`，回答 201 和会话。
  - 用服务端密钥调用时必须给 `external_user_id`（1 到 255 个字符），因为服务端密钥本身不指明用户。引擎经 tenancy 找到这个终端用户，第一次见到时新建，再在调用方的应用里建会话。
  - 语言可以不给。给了就按标准写法存，`ja-jp` 会存成 `ja-JP`；格式不对回答 400。
  - 带同一个 `Idempotency-Key` 重试，拿到的还是同一个会话。带 `On-Behalf-Of` 时，终端用户和会话都建在下级租户的应用下。
  - 浏览器令牌到 M2-12 才接受，在那之前回答 401。
  - 找终端用户和建会话是两个短事务，因为一个事务不跨模块（`A-4`）。第二个事务失败时，第一个事务建的终端用户会留下。这没有坏处，同一个用户号下次找到的还是它。
- **读会话：** `GET /v1/conversations/{id}`，回答 200 和会话，内容和建会话时的回答一样。
  - 只在调用方的应用里找。别的租户的会话、同租户别的应用的会话（比如用测试环境的密钥读正式环境的会话）、不存在的会话，都回答同样的 404，不透露会话在不在。带 `On-Behalf-Of` 时，在下级租户的应用里找。
  - 会话号要符合契约：`conv_` 后面跟 1 到 59 个英文字母或数字，总长不超过 64。不符合的回答 400，不查库。必须整个会话号都符合，结尾多一个换行也不行。路径里带 NUL 的请求，Tomcat 自己就回答 400，到不了引擎。
  - 浏览器令牌同样到 M2-12 才接受。
- **消息：** 表 `conversation_messages`，一行一条消息，经公开接口 `aie.modules.conversation.api.Messages` 存和读。
  - 用户消息一存就是完成状态。文字 1 到 32000 个字符，和契约一致，按字符算：一个 emoji 也只算一个。带 NUL 字符的文字 PostgreSQL 存不了，`Messages` 直接拒绝。
  - 助手消息一建是进行中，文字为空，同一个事务里写入它的第一个事件 `message.started`。它只能靠自己的结束事件结束，数据库不允许它一建就是已结束。进行中的助手消息，读出来的文字是目前为止的增量拼起来的。
  - 顺序：每条消息有一个数据库给的顺序号，读会话的消息时按它排。消息号排不出先后：同一毫秒里建的两条消息，消息号只有后面的随机部分不同。
  - 外键指向会话表，把租户号对上租户号。同一模块里的外键 `A-4` 允许。
  - 引擎不能直接改消息，也不能删，消息只随它的事件变。引擎有这张表的修改权限，只是为了写事件时锁住消息；表自己会拒绝任何直接修改。M2 不删数据，清理在 M4。
  - 和读会话一样只在调用方的应用里：别的应用、别的租户的会话，存不进消息，也读不到。
  - 表自己也守这些规则，绕过 `Messages` 直接写 SQL 也一样。
- **事件日志：** 表 `conversation_events`，一条回答的事件按序号排成一份日志（蓝图 6.4），流式回答只是它的一个读取方。收 `message.started`、`message.delta` 和下面三种结束事件。
  - 序号由数据库给：写事件时先锁住这条回答，再取下一个号，从 1 开始，一个不跳。号和事件在同一个事务里给出，事务回滚号也收回。不用 PostgreSQL 的序列，因为序列发出的号回滚后不收回。写入方自己填的号会被换掉。
  - 不漏读：锁一直持有到事务结束，所以同一条回答的事件按序号先后提交。读取方按“序号大于 N”往下读，不会遇到小序号晚提交、被跳过的情况。续传靠的就是这一条。
  - 第一个事件一定是 `message.started`，也只有第一个是。增量的文字不能为空。只有助手消息有事件。
  - 只追加：引擎只能读和新增，不能改，也不能删。
  - `Messages.addDelta` 写一个增量；`Messages.eventsAfter` 读序号大于 N 的事件，按序号排，可以限定条数。和消息一样只在调用方的应用里。
- **结束：** 每条回答恰好有一个结束事件：`message.completed` 带用量，`message.cancelled` 带已生成的文字和用量，`error` 带错误码 `model_failed`（模型中途失败）或 `stream_interrupted`（生成方崩溃，由读取方补写）。
  - 同一个事务里，数据库把结果写进消息：状态、整段文字（由增量拼成）、用量、错误码和结束时间。所以事件日志以后被清理，消息照样完整。
  - `message.cancelled` 不给文字时，数据库填上已生成的增量；给了但对不上，就拒绝。
  - 结束之后，这条回答不能再写任何事件，消息也不会再变。
  - 两方抢着结束同一条回答，只有一方成功。`addDelta`、`complete`、`cancel` 和 `fail` 都返回 `Messages.Written`：`Added` 带序号；`AlreadyEnded` 带回答是怎么结束的，抢输的一方和结束后才来的写入都拿到它；`CancelRequested` 表示回答还在进行中，但有人要取消它，见下面的取消请求；`NotFound` 表示调用方的应用里没有这条回答。
  - 数据库只保证最多一个结束事件。至少一个要靠生成方写完，或者租约过期后由读取方补写（M2-11），一个不再说话的生成方，数据库自己发现不了。
- **取消请求：** 表 `conversation_cancel_requests`，记下哪条回答要取消、什么时候要的（蓝图 6.4）。取消请求可能落到任何一个实例，回答却只在其中一个实例上生成，所以请求记在数据库里，规则也由数据库来守。
  - `Messages.requestCancel` 记下请求，返回 `Messages.CancelRequest`：`Recorded` 表示请求已记下，之前已经记过的也算；`AlreadyEnded` 带回答是怎么结束的，这时什么都不记；`NotFound` 表示调用方的应用里，这个会话没有这条回答。用户消息、别的应用或别的租户的消息，都算找不到。
  - 一条回答最多一个请求，再要一次什么都不改。表带行级安全，只追加：引擎只能读和新增，不能改，也不能删。外键指向消息表，把租户号对上租户号。
  - 请求记下之后，这条回答不再收增量，结束事件也只收 `message.cancelled`。`addDelta`、`complete` 和 `fail` 都拿到 `Written.CancelRequested`，什么都不写。`cancel` 照常写 `message.cancelled`，带已生成的增量和用量。
  - 先到先得：请求和事件一样，先锁住这条回答再检查。回答已经结束，请求就不记，回答保持原样。请求先记下，回答就只能以 `message.cancelled` 结束，即使模型随后正好完成或失败。
  - 表自己也守这些规则，绕过 `Messages` 直接写 SQL 也一样：已经结束的回答和用户消息不收请求，有请求的回答只收 `message.cancelled`。
  - 生成方怎么得知请求、怎么结束回答，见下面“生成”里的取消。取消接口在 M2-9-3，在那之前还没有接口记请求。
- **提问：** `Messages.ask` 在一个事务里存用户消息、建回答、写 `message.started`。中途失败什么都不留，不会有没有回答的问题，也不会有没有开始事件的回答。
  - 提问先锁住会话这一行，同一个会话的提问排队写入，所以每个问题后面紧跟着它自己的回答。锁用的是 `FOR NO KEY UPDATE`：两个提问不能同时拿到它，别的新消息的外键检查却不受影响。
  - 会话不在调用方的应用里，什么都不存。文字的规则和用户消息一样。
- **给模型的历史：** `Messages.historyFor` 给出写一条回答时模型能看到的消息：这条回答之前的消息，按顺序排。
  - 用户消息都算，已完成的回答也算。已取消的回答有文字就算，因为用户已经看到了这些字。
  - 失败的、还在生成的、没有文字的回答都不算。
  - 最多带最近 50 条（`Messages.MAX_HISTORY`），第一条一定是用户消息。M3 接上真模型后，改成按 token 数截断。
  - 只给调用方应用里的回答。问题的号、未知的号、别的会话的号，都拿不到历史。
- **生成：** 接口 `Answers` 负责回答。`ask` 先像 `Messages.ask` 一样存问题、建回答，再给这条回答开一个虚拟线程，在后台生成。
  - 生成时在任何事务之外经网关调模型（`A-14`），任务是 `customer_chat`（`Answers.TASK`），路由表按这个名字找模型。模型每给一段，就用一个短事务写一条 `message.delta`；空的片段不写。
  - 模型完成，写 `message.completed`，带用量；模型失败或超时，写 `error`（`model_failed`）。
  - 生成方自己出了问题，比如写库失败，就先停下模型，再写 `error`（`stream_interrupted`）。连这也写不进去，就留给 M2-11 的租约。
  - 写的时候发现回答已经被别人结束了，就停下模型，不再写。
  - **取消：** `Answers.cancel` 先像 `Messages.requestCancel` 一样把请求记进数据库，结果也一样。只有请求记下了，才停这条回答的模型；回答已经结束或者找不到，什么都不动。
    - 生成方就在本实例上：立即让模型停下，不用等下一段。
    - 生成方在别的实例上：它写下一段增量时被拒收（`CancelRequested`），由此得知，再让模型停下。模型一般每秒出好几段，所以几乎马上就停。
    - 停下之后写 `message.cancelled`：文字是日志里已有的增量；用量是模型报的实际用量，被拒收、没进日志的那一段也算在内，因为它也花了钱。
    - 请求先到，模型随后正好完成或失败，照样写 `message.cancelled`，用模型报的用量，不算模型的错。实例停机时有请求的回答也一样，不写 `stream_interrupted`。
    - 模型一直不出新段时，别的实例上的请求传不到它。M2-11 的租约每 5 秒续一次，到时顺带检查；在那之前，要等到路由时限（2 分钟）或停机。
    - 生成方自己出了问题（比如写库失败）时，模型的用量随异常丢了，回答又只收 `message.cancelled`，所以什么都不写，留给 M2-11 的读取方结束。用量怎么记在 M2-11 里定。
  - 每个实例同时最多生成 64 个回答（`Answers.MAX_RUNNING`）。满了，提问在存任何东西之前就被拒绝（`Busy`），发消息接口据此回答 429。
  - 等回答的一方断开，不影响生成。
  - 停机时（`close`）不再接新的提问。正在生成的回答最多再等 10 秒，还没结束的就停下，写 `error`（`stream_interrupted`），有取消请求的写 `message.cancelled`，再给它们最多 5 秒写完。停下之前模型已经完成的回答，照样算完成。
- **发消息：** `POST /v1/conversations/{id}/messages`，不要流式时等回答结束，回答 200 和这条回答：状态、整段文字、用量；失败的回答带 `code`（比如 `model_failed`），因为回答在调模型之前就已经存下了。
  - 最多等 120 秒（`aie.http.AnswerWait`）。到时还没结束，就返回 `status: in_progress` 和目前的文字，回答照样往下生成。`customer_chat` 的路由时限也是 2 分钟，所以现在只有模型一直卡到时限，才会遇到返回 `in_progress`。
  - 存任何东西之前先检查请求：会话号的格式；`text` 必须是 1 到 32000 个字符，不能含 NUL；`locale` 的格式；`data_class` 只能是契约列的四个值。不合格回答 400。`locale` 和 `data_class` 现在只查格式，不存也不用，到 M3 拼提示词和信任层时才用上。
  - 会话不在调用方的应用里：404，和读会话一样。
  - 实例满了（同时 64 个回答）：429 `rate_limited`，带 `Retry-After: 5`，什么都不存。因为 429 不占用 `Idempotency-Key`，等过 `Retry-After` 可以用同一个键再发。
  - **流式：** `Accept` 里写明 `text/event-stream`（权重大于 0）就发流，即使同时也写了 JSON；只写 `*/*`、`application/json` 或不带 `Accept` 的拿 JSON。只写 `text/*` 这类既不是 JSON、也没写明流的，回答 406 `invalid_request`，在存任何东西之前。
    - 流从第 1 个事件 `message.started` 一直发到结束事件，用下面的事件流，回答的 `Content-Type` 是 `text/event-stream`，带 `Cache-Control: no-cache` 和 `X-Accel-Buffering: no`，免得代理把流攒着不发。
    - 检查不过的（400、404、429）在流开始前回答普通的 problem。流开始后回答本身出错，是日志里的 `error` 事件；读日志出错，按第 34 点切断连接，不编造结束事件。
    - 流式请求暂时不收 `Idempotency-Key`：带了就回答 400 `invalid_request`，在存任何东西之前。同一个键接上同一个流是 M2-10。
  - 带同一个 `Idempotency-Key` 重试，拿到同样的回答，不会再生成一次；还在生成时重试，回答 409 `idempotency_key_in_use`。接上同一个流是 M2-10。
  - 等回答的一方断开，不影响生成。api 停机时，正在生成的回答按“生成”里说的处理。
  - 只有服务端密钥能调用，`On-Behalf-Of` 照常。浏览器令牌在 M2-12。
- **事件流：** `aie.http.EventStream` 从日志里读出一条回答的事件，按 SSE 写出：`id` 是序号，`event` 是类型，`data` 是契约里这种事件的 JSON。`error` 事件用问题格式，`model_failed` 带 502（上游的模型失败了），`stream_interrupted` 带 503（生成方不在了），客户端只看 `code`。
  - 每 200 毫秒查一次日志，一次最多读 100 个，读满了马上接着读。15 秒没发过东西，就发一行注释 `: heartbeat` 保持连接。
  - 同一实例里不用等满 200 毫秒（M2-7-4）：每个事件写进去、事务提交之后，`DatabaseMessages` 就叫醒等着这条回答的流（`EventSignals`，实现是 `LocalEventSignals`）。流在每次查日志之前先领提醒，所以查完之后才写进来的事件，叫醒的正是它在等的那个提醒，不会漏。跨实例的流照旧每 200 毫秒查一次，提醒丢了也一样：它只是省时间，不影响读到什么。
  - 发到结束事件就关闭。客户端断开就停，回答照样生成。读日志出错时往外抛，由调用它的一方处理（M2-7-2）。
  - 最长开 5 分钟，到时还没结束就关闭，不写结束事件，客户端用最后收到的序号续传。在 M2-11 的租约之前，生成方崩溃的回答一直不会结束，所以要有这个上限。
  - 流式发消息和续传都用它，在请求自己的线程上发：流式发消息从第 1 个事件发起，续传从客户端收到的最后一个事件之后发起。
- **续传：** `GET /v1/conversations/{id}/messages/{message_id}/events`，按 SSE 发出这条回答在 `Last-Event-ID` 之后的事件，一直发到结束事件（蓝图 6.4）。消息号在流式发消息的第一个事件 `message.started` 里。
  - 不带 `Last-Event-ID` 就从第 1 个事件发起，所以已经结束的回答会从头重放到结束事件。还在生成的回答照常接着发，心跳、5 分钟上限、客户端断开不影响生成，都和上面的事件流一样。
  - 不重不漏，落到哪个实例都一样：事件日志在数据库里，序号连续、不回退。生成方所在的实例一写入就叫醒读取方；别的实例每 200 毫秒查一次，最多慢 200 毫秒。
  - `Last-Event-ID` 只认流发出过的 `id`：从 1 开始的十进制数，不带符号，也不带前导零。空值当作没带，因为 SSE 规范里“上一个 id”为空就是还没收到过事件。别的写法回答 400；比这条回答已有的最后一个序号还大的也回答 400：这样的 `id` 引擎不可能发过，照收的话，流会一直等一个永远不来的序号。
  - 客户端已经收到结束事件，又带着它的 `id` 来续传：回答 200，一个事件也不发，立即结束，不用契约里没有的 204。作废内容时增量会被删掉、结束事件留下（蓝图 6.4），所以客户端收到过的增量不在了，后面的结束事件照样发给它。
  - 检查都在流开始之前，不通过就回答普通的 problem：会话号、消息号格式不对是 400（消息号是 `msg_` 后面跟 1 到 60 个英文字母或数字，总长不超过 64）；`Accept` 不接受 `text/event-stream` 是 406；消息不存在、属于别的应用或别的租户、或者是用户消息（用户消息没有事件），都回答同样的 404。
  - `HEAD` 只回答状态和响应头，不读日志，也不占着连接等。
  - 只有服务端密钥能调用，`On-Behalf-Of` 照常。浏览器令牌在 M2-12。事件日志过了保留期之后的 410 `stream_expired`，和清理一起在 M4 做；同时开着的流还没有数量上限，记在第 40 点。
- **还没有的：** 读消息和取消的接口在之后的点里做，在那之前回答 404。

## 对外契约

[contracts/openapi.yaml](contracts/openapi.yaml) 是公开 API 的唯一源头，用 OpenAPI 3.1 写。现在有公共约定、身份和对话，其他资源做到时再加。目前实现了 `POST /v1/api-keys`、`DELETE /v1/api-keys/{key_id}`、`POST /v1/sessions` 和 `POST /v1/anonymous-sessions`；其他 `/v1` 路径带有效密钥时返回 404。

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

**破坏性差异：** 基线是 `contracts/baseline.yaml`。按蓝图 6.2，新增字段、可选参数、事件类型和枚举值都不算破坏性，删除、改名算。收紧请求的限制也算，键名的限制（`propertyNames`，比如上下文的键名）也包括在内。含义变了从文件上看不出来，要靠评审。

**没写的规则：** 和 JSON Schema 一样，没写就是什么都收。没写 `type` 就是任何类型；没写 `items`、`additionalProperties`，或者写成 `true`，就是什么值都收；写成 `false` 就是什么都不收。所以：
- 请求里原来没写的地方新写上规则，算收紧，比如对象加上 `additionalProperties: false`、数组加上 `items`。
- 响应里去掉类型，或者去掉数组元素、其他属性的规则，算放宽，也报。
- 响应里原来是 `false` 的地方放开，等于新增字段，不算。
- 键名总是字符串，所以键名规则里写不写 `type: string` 都一样。

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

**跨域预检：** 浏览器从宿主的网页调用引擎时，要先发 `OPTIONS` 预检请求，问能不能跨域。现在只有 `/v1/anonymous-sessions` 回答预检，因为浏览器在还没有令牌时只调用这一个接口。其他浏览器接口的规则到 M2 再定，它们的预检和普通请求一样要密钥，得到 401。

- **放行：** 来源只要是站点键可能列出的那种，就回 204，并带上 `Access-Control-Allow-Origin`（原样返回请求的来源）、`Access-Control-Allow-Methods: POST`、`Access-Control-Allow-Headers: Content-Type, Idempotency-Key, Engine-Version, traceparent`，缓存 600 秒。预检请求没有正文，看不到站点键，所以这一步对哪个网站都放行；真正的 POST 只对它那把站点键列出的来源放行，见下面的“换匿名令牌”。
- **拒绝：** 来源不合格（比如 `null`、http 的正式域名、带路径），或者问的不是 POST，回 403 `forbidden`，不带跨域头。
- **顺序：** 预检排在认证前面，因为预检请求不带凭证。过滤器的顺序是：`Request-Id`、`traceparent`、预检、认证、版本、幂等。

**认证：** `/v1` 下的请求都要带 API 密钥，只有 `POST /v1/anonymous-sessions` 例外，它用站点键。写法是 `Authorization: Bearer aie_test_dev_...`，这是 RFC 6750 的格式。密钥用 `admin` 发放，见[开通租户](#开通租户)。认证排在版本检查前面，因为以后版本要按调用方的应用决定：

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

**换匿名令牌：** 宿主网页里的组件用 `POST /v1/anonymous-sessions` 给还没登录的访客换一个令牌。它不带 API 密钥，带的是站点键，见[开通租户](#开通租户)一节的“站点键”。成功返回 201，回答的格式和换会话令牌一样，身份等级是 `anonymous`。

```js
const answer = await fetch("https://引擎地址/v1/anonymous-sessions", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ site_key: "aie_site_live_jp1_...", locale: "ja-JP" }),
});
```

- **检查：** 引擎先读请求体里的 `site_key`，再对照浏览器发来的 `Origin`，都在 API 密钥检查的位置上做。没有 `site_key` 回 400；站点键不认识或已吊销回 401；站点键认识但名单里没有这个来源，或者请求没带来源，回 403。这三种回答都不带跨域头，网页读不到。
- **令牌：** 每次都新建一个匿名终端用户。令牌没有权限范围和上下文，同样 1 小时过期；`locale` 照换会话令牌的规矩写进去。
- **跨域：** 通过检查的请求，回答里带 `Access-Control-Allow-Origin`（原样返回来源）、`Vary: Origin` 和 `Access-Control-Expose-Headers: Request-Id`，所以之后哪怕是 400，网页也能读到原因。不带 cookie。
- **重试：** 带同一个 `Idempotency-Key` 重试，按站点键所属的应用隔离，拿回同样的回答，只是没有令牌。
- **限流：** 还没有，见第 26 点。

**`Engine-Version`：** `/v1` 下的请求可以传日期版本，如 `Engine-Version: 2026-11-05`。现在只有这一个版本，不传时也用它；以后按应用锁定的版本走。传别的版本返回 400 `invalid_request`，不会悄悄用另一个版本回答。版本写在 `EngineVersionFilter` 和契约的 `info.version`，测试保证两边一致。

**`Idempotency-Key`：** `/v1` 下的 POST 可以带这个头，重试时同一件事不会做两遍。它排在认证和版本检查之后，所以知道调用方的租户和应用；键按租户和应用隔离，保留 24 小时。判断写在 `aie.core.tenancy.api.IdempotencyKeys`，HTTP 这一层是 `IdempotencyFilter`：

| 同一个键再来一次 | 结果 |
| --- | --- |
| 第一次 | 拿到键，执行请求 |
| 上一次还在处理 | `idempotency_key_in_use`（409） |
| 上一次已经完成 | 原样返回上一次的结果，失败也一样 |
| 上一次回答了 429 `rate_limited` | 当作新键：429 表示这次什么都没做，所以结果不存，键也放开。等过 `Retry-After` 再用同一个键发，会真的执行 |
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
| 实例同时生成的回答已满 | 429 | `rate_limited`，带 `Retry-After` |
| Tomcat 自己拒绝：路径非法、请求行错误、缺 `Host`、请求头过大 | 400 | `invalid_request` |
| 引擎内部出错 | 500 | `internal_error` |

**读不了的请求体：** 正文不是合法的 JSON、没有正文，或者字段的类型不对、取值不在契约列出的范围里时，返回 400 `invalid_request`，`detail` 写明是哪个字段、应该是什么，比如 `identity_level must be one of: unverified, verified`、`scopes[1] must be a string`。它不重复调用方发来的值，也不转述解析器的原话，因为原话里有引擎内部的类名。键值表的键名是调用方自己起的，所以写成 `each value in context`。写在 `aie.http.UnreadableBodies`。

错误在三个地方转换，这样无论在哪一层失败，格式都一样：

| 在哪里失败 | 谁来转换 |
| --- | --- |
| 控制器和 Spring MVC | `ProblemAdvice` |
| 过滤器抛异常或调用 `sendError` | `ProblemErrorController` |
| 请求还没到程序，Tomcat 自己拒绝 | `ProblemReportValve`，这种请求的 `Request-Id` 也在这里生成 |

500 不告诉调用方任何内部信息。原因写在日志里，用 `Request-Id` 能查到。`internal_error` 的来历见 [ADR-0001](docs/adr/0001-internal-error-code.md)。

**一个失败只记一条：** 日志里一个失败只有一条 ERROR，带 `Request-Id` 和 trace ID。
- 控制器里的失败由 `ProblemAdvice` 记。
- 过滤器抛出的由 `RequestIdFilter` 接住，交给错误页，由 `ProblemErrorController` 记。要是让它冒到 Tomcat，Tomcat 会先记一条不带 ID 的，告警就会算两次。
- 回答已经开始发送后才出错的（第 34 点），没法再变成 problem，也不能再往里写任何东西，否则回答就乱了。不管失败发生在过滤器还是控制器（比如流），都由 `RequestIdFilter` 记一条 ERROR，带 `Request-Id` 和 trace ID，然后切断连接：
  - 控制器里的失败，`ProblemAdvice` 原样抛出，不记也不写，交给 `RequestIdFilter`。
  - `RequestIdFilter` 记完日志，抛出 Tomcat 的 `ClientAbortException`，Tomcat 把它当成客户端走了：不再记日志，也不转错误页，并且直接关掉连接，不发最后的结束块。客户端因此知道回答断了；流式的客户端没收到结束事件，就该续传（蓝图 6.4）。

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

**发布日期也照样重试：** 构建完成后，`verify.sh` 从 Maven Central 读每个版本的发布日期，检查是否发布满 7 天。遇到上面那几个状态码或者连接出错时，同样每 5 秒重试一次，最多重试 10 次；404 这类回答不会变，不重试。只要有一个文件重试 10 次还读不到日期，就当 Maven Central 停摆了：别的文件不再发请求，没读到日期的都报出来，检查失败。不这样的话，Maven Central 停摆时要等大约半小时才失败，先撞上 CI 的 25 分钟上限。重试的次数和间隔必须和上面 mvn 的设置一致，由 `mvn verify` 检查。

**构建输出里不许有密钥：** `verify.sh` 把 mvn 的输出照常显示，同时另存一份。构建结束后查这一份，构建失败也查。只要里面有 API 密钥或站点键格式的字符串，检查就失败，并列出前 5 行，密钥的机密部分换成 `<masked>`。
- 测试编的假密钥也算，因为看日志的人分不出真假。
- `QuietOutput` 打过码的不算。
- 密钥的格式写在 `verify.sh` 里，`BuildLogKeysTest` 用 `ApiKeys`、`SiteKeys` 真发的密钥核对它，`mvn verify` 检查这几行没被删掉。

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
