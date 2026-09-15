# 更新日志

本文件记录 `top.wcpe.taboolib.ioc` Gradle 插件的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.0.8] - 2026-09-15

### 新增

- **编译期 AOP 织入（可选，默认关闭）**：新增 `taboolibIoc { weaving(true) }` 开关（等价项目属性 `taboolib.ioc.weaving=true`）与 `weaveTaboolibIocAop` 任务（挂在 `jar` / `assemble` / `build` / `taboolibMainTask` 之前）。开启后被切点命中的 public 实例方法在**构建期**被 ASM 改写为转发到 `AopWeavingRuntime`，原方法体搬到合成方法 `xxx$ioc$original`：
  - **具体类（不实现任何接口）也能被切面命中，且不创建任何代理**；
  - 织入后的类被加上 `WovenTarget` 标记接口（随字节码一并 relocate，天然 relocate 安全），运行期容器见到它即跳过代理，避免通知执行两次；
  - **运行期入口零反射**：woven 方法传入的 key（**方法名 + 描述符**）在**构建期**算好，运行期按表查缓存，不再每次调用反射解析方法或拼接字符串 —— 实测该缺陷会让真机稳态从 63.5 ns/op 劣化到 886 ns/op；
  - 幂等；不改写 `static` / `private` / `abstract` / `native` / 合成方法与构造器；带 `@NoAspect` 的类与方法跳过；
  - 只用 ASM（`asm` + `asm-tree`，**构建期依赖**，不进消费者插件 jar）。

## [0.0.7] - 2026-09-13

### 新增

- 静态诊断新增 AOP 规则组（5 条）：
  - `aop-target-not-proxied`：被通知的 Bean 未实现任何接口，JDK 动态代理无法包装（WARNING）；
  - `aop-factory-bean-interface-return`：`@Bean` 工厂方法声明返回接口类型且被切面命中，运行时按声明类型收集接口必为空（WARNING）；
  - `advice-signature-invalid`：通知签名非法（`@Around` 为 ERROR，`@AfterReturning` / `@AfterThrowing` 为 WARNING）；
  - `pointcut-target-not-found`：切点目标类/方法在扫描范围内不存在（WARNING）；
  - `aop-private-method-pointcut`：切点仅命中 private 方法（WARNING）。
- 新增 `aop-static-method-pointcut`：切点仅命中 static 方法时告警（WARNING）。

### 修复

- 修复 3 个 Kotlin 元数据缺陷（`$annotations` 载体被 `ACC_SYNTHETIC` 一刀切过滤、含 `$` 的嵌套类被整体跳过、`companion object` 注入点跨类不可见），静态规则召回率此前因之减半。
- 修复静态诊断引擎规则缺陷：`@Lazy` 断边判据改为「`lazy` 且依赖类型为接口」（此前完全忽略 `@Lazy`，对已断开的环误报 ERROR，而 `failOnError` 默认开启等于误阻断正确工程）；修正 Kotlin `typealias` 漏判；`duplicate-bean-name` 由 ERROR 降为 WARNING。
- 修复失效的测试门禁：`PaperServerPluginLoadTest` 此前 catch 掉 `UnexpectedBuildFailure` 后仅断言输出含 PASS，「服务端先打印 PASS、随后崩溃」的窄窗口仍判绿；现构建非零退出即判失败。
- 修复 verify 挂载（新增 `isTakeoverEffective` 守护，避免「插件在 taboolib 之后 apply」时误报阻断打包）、relocate 目标包判据（消除自 relocate 与父包收窄）、诊断采集 ClassLoader 遮蔽。

### 变更

- 补齐静态引擎与采集层测试：此前 3 个测试文件从未纳入 git（`git ls-files` 为空），引擎约 15 条规则名义上有测试、实际零门禁，现入库（`:test` 142 tests / 0 failures）。
- 依赖仓库顺序修正（阿里云 / wcpe 镜像前置于 `mavenLocal()`），修复 `~/.m2` 半落盘模块导致的 jacoco 工具链解析死锁。
- `io.izzel.taboolib` 插件统一至 `2.0.38-wcpe.1`，并将 wcpe maven-releases 前置。
- CI 提交触发即构建 + 测试（`build ciTest`），覆盖率门禁在同一 Gradle 调用中去重只运行一次。
- 构建 JVM 升至 JDK 21（嵌套 `GradleRunner` 构建需 JDK 21+ 才能解析 mc-testkit；插件本体仍以 Java 17 toolchain 编译）。
- E2E fixture 用 `taboo` 配置接入 harness-core，`PaperServerPluginLoadTest` 改判 mc-testkit 结果文件（`:runServer` → `:e2eSmoke`，判定真源为 `smoke.properties` 的 `status=PASS`）。

## [0.0.6] - 2026-05-28

### 新增

- 静态分析现在支持 `@Service`、`@Repository`、`@Controller`、`@Aspect` 注解，将其视为与 `@Component` 等价的 Bean 注解，与运行时 `ClassScanner.isComponent` 行为对齐。
- 新增 `StereotypeAnnotationsRegressionTest` 回归测试，确保上述注解被正确识别。

### 修复

- 修复 `analyzeTaboolibIocBeans` 任务在遇到 Groovy DSL 扩展对象（如 `TabooLibExtension`）时抛出 `NoClassDefFoundError: org/codehaus/groovy/runtime/FormatHelper` 的问题。现在会安全地捕获 `toString()` 异常并回退到类名。

## [0.0.5] - 2026-05-05

### 新增

- 新增编译时注入问题扫描规则，支持检测缺失 Bean、类型不兼容、多个 `@Primary` 等问题。
- 新增真实插件集成测试模块，覆盖 Groovy DSL 和 Kotlin DSL 两种消费方式。
- 补充 IoC 注入问题诊断规则测试用例。

### 变更

- 优化 CI 测试报告上传路径匹配规则。
- 添加全量测试与报告发布流程。

## [0.0.4] - 2026-04-13

### 修复

- 修复缺失外部类型导致静态扫描失败的问题。

### 变更

- 对齐官方依赖坐标并打通示例 CI 验证。

## [0.0.1] - 2026-04-02

### 新增

- 初始化 Taboolib IoC Gradle 插件工程。
- 自动向 `taboo` 配置注入 IoC 依赖。
- 自动推导目标包并追加 IoC relocate 规则。
- 支持本仓库联调时改用本地项目依赖。
- 预留 `StandaloneBackend` 扩展位，当前版本只实现 `TABOOLIB` 后端。
- 新增 `taboolibIocDoctor`、`verifyTaboolibIoc`、`analyzeTaboolibIocBeans` 诊断任务。
- 新增静态 Bean 诊断任务，支持缺失 Bean、类型不兼容、多个 `@Primary` 等错误检测。
- 接入静态诊断质量门，`analysisFailOnError` 默认开启，`analysisFailOnWarning` 默认关闭。
- 支持泛型注入匹配，降低原始类型导致的误报。
- 支持 Kotlin `typealias` 索引。
- 支持 `ConditionalOnProperty`、`ConditionalOnClass`、`ConditionalOnMissingClass`、`ConditionalOnBean`、`ConditionalOnMissingBean` 的静态启停判断。
- 集成 Gradle Problems API，结构化问题信息可在 IDEA 中查看。
- 完善发布流程，支持 `publishToMavenLocal`、远端 Maven 仓库和 Plugin Portal。
- 启用覆盖率质量门（行覆盖率 ≥ 75%，分支覆盖率 ≥ 55%）。
- 新增 Groovy DSL 和 Kotlin DSL 示例工程。
- 新增 CI 工作流，覆盖根工程测试与 example 联调校验。
