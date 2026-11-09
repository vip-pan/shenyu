# CLAUDE.md

本文为 AI 编程助手在本仓库中的开发工作提供指导。

## 项目与本地环境

Apache ShenYu 是面向微服务的响应式 API 网关，采用 Maven 多模块构建。项目版本和构建配置以根目录 `pom.xml` 为准。

本地使用 SDKMAN（`sdk`）管理 JDK 和 Maven，**JDK 固定使用 21**。使用 SDKMAN 管理的 `java` 和 `mvn`，不使用 Maven Wrapper（`./mvnw`）或 `.mvn` 目录管理本地工具版本。本地运行 JDK 的约定不代表需要修改项目的编译目标版本。

```bash
# 当前终端尚未加载 SDKMAN 时，先初始化
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 从已安装版本中选择任意 JDK 21，将占位符替换为其 SDKMAN 标识
sdk use java <已安装的JDK21版本标识>

# 按项目要求选择已安装的 Maven 版本，替换下方版本占位符
sdk use maven <版本号>

# 构建前确认 Java 和 Maven 实际使用的 JDK 均为 21
java -version
mvn -version
```

新终端或独立执行进程中需要重新确认版本，不能假定上一次 `sdk use` 会跨进程生效。仅要求使用 JDK 21，不限定小版本或发行版；如未安装，应通过 SDKMAN 安装兼容的 JDK 21。

## 常用命令

```bash
# 安装默认构建模块集合，不执行测试，但仍编译测试源码
# Checkstyle 在 validate 阶段运行；RAT 在 verify 阶段运行
mvn clean install -DskipTests

# 快速构建单个模块及其依赖
# 跳过测试执行、Javadoc、RAT 和覆盖率；仍执行 Checkstyle
mvn -pl shenyu-admin -am clean install -DskipTests -Dmaven.javadoc.skip=true -Drat.skip=true -Djacoco.skip=true

# 执行指定模块及其依赖模块的测试
mvn -pl shenyu-common -am test

# 执行单个测试类或方法，允许依赖模块没有匹配的测试
mvn -pl shenyu-common -am test -Dtest=GsonUtilsTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl shenyu-common -am test -Dtest='GsonUtilsTest#testToJson' -Dsurefire.failIfNoSpecifiedTests=false

# 仅检查代码风格
mvn checkstyle:check

# 仅检查许可证头
mvn apache-rat:check
```

- `-am` 会包含 `shenyu-register-protocol` 等构建依赖，避免测试依赖本地预先安装的 ShenYu 构件。`-Dtest` 会作用于所有选中模块；使用 `-Dsurefire.failIfNoSpecifiedTests=false` 时，必须确认 Surefire 输出中确实执行了目标测试类或方法。
- **Checkstyle** 在构建生命周期的 `validate` 阶段运行，配置位于 `script/shenyu_checkstyle.xml`。检查严格，涉及 Javadoc、导入顺序、参数的 `final` 修饰等，应遵循周边代码风格。`-DskipTests` 不会跳过此检查，只有显式指定 `-Dcheckstyle.skip=true` 才会跳过。
- **Apache RAT** 在 `verify` 阶段运行，`test` 和 `package` 不会进入该阶段。新增源码必须包含 Apache 2.0 许可证头，模板位于 `script/checkstyle-header.txt`。`-DskipTests` 不会跳过 RAT，显式指定 `-Drat.skip=true` 才会跳过。提交 PR 前执行要求的检查。
- 发行包通过 `make build-admin`、`make build-bootstrap` 构建，详见 `Makefile`，对应模块位于 `shenyu-dist/*`。构建镜像时，使用根项目版本覆盖 Makefile 独立定义的 `VERSION` 默认值：

```bash
SHENYU_VERSION="$(mvn -q -N -DforceStdout help:evaluate -Dexpression=project.version)" &&
make build-all-image VERSION="$SHENYU_VERSION"
```

## 工作原则

- **同步维护助手配置。** 后续每次更新 `AGENTS.md` 时，必须同步更新 `CLAUDE.md`，保持两份文件的指导内容一致（文件标题除外）。除 codemap 相关内容外，说明使用中文。

- **明确管理端与网关端的讨论范围。** 后续开发与讨论须区分管理端（Admin）和网关端（gateway / bootstrap）。只有上下文中存在明确匹配的关键词、足以确定所属端时才继续；若缺少明确关键词或范围仍有歧义，必须先询问用户，不得自行猜测。
- **先理解，再编码。** 检查相关源码、配置、测试及当前 issue/PR 状态。说明影响行为、兼容性或范围的假设与取舍；优先根据仓库证据调查，仅在实质性歧义阻碍正确决策时询问。常规且可逆的决策自主处理，多步骤工作使用简短计划并明确可验证的检查项。
- **优先保持简单。** 复用现有插件、SPI、配置和可观测性机制，按行为完整性和维护成本选择最小完整方案。避免推测性的选项；有具体使用场景支持时，要求提供的扩展接口可以先只有一个实现。保留对非法输入、网络故障、取消、热加载竞态和资源归属的处理。
- **精确控制改动。** 遵循周边风格，删除因本次修改而失效的代码，不进行无关清理。除非任务明确要求改变，否则保留公共 SPI、插件名称、配置默认值和已支持的旧行为。沿共享 DTO、Admin 持久化/校验/事件、数据同步、网关消费端及 starter 追踪受影响的插件/选择器/规则契约，并配套修改和测试。涉及持久化或初始化数据时，检查维护中的 SQL 方言和升级路径。
- **以可验证的结果推进。** 明确成功标准；修复缺陷优先提供可复现的回归测试，重构保留外部可观察行为。先使用 SDKMAN 管理的 Maven 检查受影响模块及其依赖，再按风险和仓库/CI 要求扩大验证范围。区分行为测试、编译检查和使用 `-DskipTests` 的构建。响应式改动需检查订阅、完成、错误、取消、背压以及缓冲区/资源生命周期；确认取消行为符合资源归属，必要时覆盖原始上游调用。响应头、响应体完成和 WebSocket 会话终止是不同边界。文档改动需检查事实、链接和配置。报告检查结果及未执行的重要检查，继续遵守 Checkstyle、RAT/许可证和贡献要求。

## 架构

ShenYu 分为**控制面**与**数据面**，两者仅通过数据同步通道通信，网关不会读取数据库。

- **`shenyu-admin`**：控制面，基于 Spring MVC 和 MyBatis，提供管理界面，端口为 9095。持久化插件、选择器、规则和元数据；数据库初始化脚本位于 `db/`，默认使用 H2，支持 MySQL、PostgreSQL 和 Oracle。调用结构为 `controller` → `service` → `mapper`，由 `listener` 发布配置变更事件。
- **`shenyu-admin-listener`** 与 **`shenyu-sync-data-center`**：负责从管理端到网关的配置同步。每种通道对应一个子模块，包括 websocket（默认）、HTTP 长轮询、zookeeper、nacos、etcd、consul、apollo 和 polaris。网关订阅者更新内存缓存，配置变更无需重启即可生效。
- **`shenyu-bootstrap`**：数据面，轻量 Spring WebFlux 启动模块，端口为 9195。具体能力由类路径中的 `shenyu-spring-boot-starter-*` 组件组合而成。
- **`shenyu-web`**：网关运行时核心。`ShenyuWebHandler`（`shenyu-web/.../web/handler/ShenyuWebHandler.java`）为每个请求执行有序插件链。
- **`shenyu-plugin`**：网关功能通过插件实现。
  - `shenyu-plugin-api`：定义 `ShenyuPlugin`（响应式 `execute(exchange, chain)` 和 `getOrder()`）、`ShenyuPluginChain`、`ShenyuContext`。
  - `shenyu-plugin-base`：`AbstractShenyuPlugin` 根据缓存配置依次匹配选择器与规则，然后调用具体插件的 `doExecute`；`PluginDataHandler` 实现接收同步配置并更新各插件缓存。
  - 功能插件按类别组织，包括 `shenyu-plugin-proxy`（dubbo、grpc、spring-cloud、sofa、tars、websocket、mqtt 等）、`-security`、`-logging`、`-cache`、`-fault-tolerance`、`-ai`、`-mcp-server` 等。在 bootstrap 的 pom 中添加对应 starter 即可引入插件能力。
- **`shenyu-spi`**：自定义 SPI 扩展机制。接口使用 `@SPI`，实现使用 `@Join`，注册文件位于 `META-INF/shenyu/`。用于负载均衡器、条件匹配器等，`shenyu-loadbalancer` 也通过该机制接入。
- **`shenyu-client`** 与 **`shenyu-register-center`**：负责客户端 API 注册。后端服务通过 `@ShenyuSpringMvcClient` 等注解声明接口，经 http、zookeeper、nacos 等注册通道向管理端注册元数据和 URI。管理端通过 `shenyu-disruptor` 异步事件流水线消费注册信息。
- **`shenyu-common`**：共享模型（`PluginData`、`SelectorData`、`RuleData`、`MetaData`）、枚举、常量和工具。配置语义定义于此，改动可能同时影响管理端和网关端。

典型配置链路：管理界面/API 修改配置 → 写入数据库并发布变更事件 → 数据同步通道 → 网关订阅者更新缓存 → `AbstractShenyuPlugin` 使用新的选择器/规则匹配后续请求。

## 测试模块

- 单元测试位于各模块中，使用 Surefire 执行。
- `shenyu-integrated-test` 和 `shenyu-e2e` 是基于 Docker 的集成/端到端测试套件，在 CI 中执行，不属于常规本地构建。
- `shenyu-examples` 提供示例后端服务，用于手动验证网关行为。

## GitNexus 代码分析

本仓库已由 GitNexus MCP 服务建立索引，仓库名为 `shenyu`。查找跨管理端与网关端的执行链路时，优先使用 `query`/`context`；重构广泛使用的符号（例如 `shenyu-common` 中的符号）前，使用 `impact` 分析影响。技能文档位于 `.claude/skills/gitnexus/`。

<!-- codemap:start -->
## Codemap MCP

**[Important!!!] Whether in the parent agent or in a subagent, always use codemap MCP tools instead of Grep/Read tools. Only fall back to Grep/Read when codemap is unavailable.**

### Tools

| Tool | Use case |
|------|----------|
| `search_code` | Fuzzy keyword or concept search (FTS + vector). For exact name lookup, use find_symbol. |
| `find_symbol` | Exact/prefix/suffix name lookup. Use when you know the symbol name. |
| `get_symbol_detail` | Get a symbol's location, signature, docs, and body. |
| `get_call_chain` | Trace upstream callers and/or downstream callees. |
| `get_type_hierarchy` | Class/interface parent and child hierarchy. |
| `get_dependencies` | Find all symbols that reference a given symbol. |
| `get_graph_stats` | Graph statistics: file/symbol/edge counts. |
| `search_knowledge` | Business rules and architecture docs from codeindex. |
| `query_cypher` | Count/aggregate structural queries: "how many functions in X?", "which class has most methods?", duplicate names, cross-cutting analysis. |


**Name resolution:** Pass `symbol_name` — simple name (`parse_config`) or qualified (`ClassName.method`). No module prefix needed. `ClassName:method` (Lua) and `ClassName::method` (C++/Rust) forms resolve automatically. **Symbol ID:** `filepath:kind:scopedName` (e.g. `player.py:method:Player.attack`, `models.py:class:Outer.Inner`)

**Slash commands:** `/codemap-exploring`, `/codemap-debugging`, `/codemap-impact-analysis`

### Rules

- **After `get_symbol_detail`: edit immediately.** Do NOT re-Read the same file.
- **Use `search_code` first**, not broad `find_symbol` prefix queries.
- **Use batch queries:** `search_code({matches: ["A", "B"]})`, `find_symbol({symbol_name: ["X", "Y"]})`.
- **For obvious single-file bugs: skip codemap.** Error → Read → Edit.
- **Counting or aggregation questions** (how many, which has most, rank by): use `query_cypher`, NOT read/grep.
- **Framework call gaps:** use `codemap-rule-authoring` for evidenced event/dispatch/routing conventions; validate the rule and inferred calls.
- **If a subagent is needed, use `general`, not `explore`** (`explore` does not support MCP and cannot call codemap tools).

<!-- codemap:end -->
