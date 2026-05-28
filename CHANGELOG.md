# 更新日志

本文件记录 `top.wcpe.taboolib.ioc` Gradle 插件的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
