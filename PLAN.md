# Taboolib IoC Gradle Plugin 计划

## 阶段 1：MVP 框架搭建

目标：建立可独立 apply 的 Gradle 插件框架，对齐 `taboolib-gradle-plugin` 的基础结构，但先只交付骨架与最小可运行链路。

任务：
- [x] 新增 `taboolib-ioc-gradle-plugin` 模块，接入 `java-gradle-plugin`、plugin marker、发布骨架与基础依赖。
- [x] 定义 `TaboolibIocPlugin`、`TaboolibIocExtension`、`PackagingBackend` 等核心类型，预留 `TabooLibBackend` / `StandaloneBackend` 扩展点。
- [x] 实现最小 apply 流程：创建 extension、注册任务组或诊断任务、暴露版本/目标包/自动接管开关等基础 DSL。
- [x] 建立 Gradle TestKit smoke test 或最小 consumer 示例，验证插件可被 apply 且配置链路可运行。

方案：分层混合——先把长期 DSL 和后端抽象搭好，第一阶段只落框架和骨架，不做完整打包重写。

完成标准：示例工程可成功 apply 新插件，能读取 extension 配置并通过最小功能测试。

## 阶段 2：自动打包与自动 relocate 核心能力

目标：让 consumer 项目在引入插件后，自动把 `taboolib-ioc` 打进产物，并自动执行 `top.wcpe.taboolib.ioc -> <目标包>.ioc` 重定位。

任务：
- [x] 实现 `TabooLibBackend`，接管或校验 `io.izzel.taboolib` 插件环境，并与其生命周期对接。
- [x] 自动注入 IoC 依赖打包逻辑，优先支持 `top.wcpe.taboolib.ioc:taboolib-ioc` 发布件，并为本仓库联调保留 project 依赖入口。
- [x] 实现目标包推导规则：显式配置优先，其次 `taboolib.env.group`，最后 `project.group`，统一生成 `.ioc` 目标前缀。
- [x] 自动追加 `taboolib.relocate("top.wcpe.taboolib.ioc", "<目标包>.ioc")` 等效配置，并补充冲突检测与基础日志。
- [x] 用真实示例工程验证输出 jar 中已包含 IoC 且重定位后的类与资源可用。

方案：分层混合——第二阶段先复用 `taboolib-gradle-plugin` 作为实际打包与重定位后端，对外 DSL 保持独立，后续可替换后端而不改使用方式。

完成标准：示例插件无需手写 `taboo(project(":taboolib-ioc"))` 与 `relocate(...)` 也能构建出包含已重定位 IoC 的 jar。

## 阶段 3：工程集成与兼容增强

目标：把插件从“能用”打磨到“适合真实 TabooLib Bukkit 工程长期使用”。

任务：
- [x] 补齐多模块与子项目场景处理，明确 root project、subproject、example 工程的默认行为。
- [x] 增加功能测试矩阵：版本覆盖、显式目标包覆盖、手动 relocate 冲突、禁用自动接管、联调本仓库 project 依赖等。
- [x] 完善诊断与失败提示，明确缺失 `io.izzel.taboolib`、目标包无法推导、重复依赖或重复 relocation 等错误场景。
- [x] 固化后端抽象边界，为后续可选的 `StandaloneBackend` 留出接口和测试位置，但本阶段不强行实现独立打包引擎。

方案：沿用分层混合路线，先把 `TabooLibBackend` 做稳，再把未来独立后端的演进边界固定下来。

完成标准：针对真实 consumer 场景的功能测试稳定通过，常见错误能给出明确诊断，后端抽象已满足后续扩展需要。

## 阶段 4：收尾与交付

目标：完成文档、发布、验收与交接，让插件可以被稳定复用。

任务：
- [x] 编写使用文档：插件 id、DSL、默认推导规则、与手写 `taboo + relocate` 的迁移方式。
- [x] 补充示例配置与最小接入模板，明确如何在 Bukkit 与 TabooLib 工程里启用自动打包与 relocate。
- [x] 完成发布与版本策略整理，包括 plugin marker、maven 或 publish 流程、仓库内联调说明。
- [x] 做最终验收：以 example 或真实插件工程验证从 apply 插件到产出 jar 的完整链路。

方案：沿用前序方案，对交付面做收口，不再增加新能力。

完成标准：计划范围内能力全部可验证，文档足以让新项目直接接入，发布与验收链路清晰可复用。

## 阶段 5：质量加固与发布准备

目标：在保持当前功能稳定的前提下补足单元测试、覆盖率报告与兼容性说明，为后续发布与 CI 验证做准备。

任务：
- [x] 为 resolver、模型、插件注册、反射辅助与 `StandaloneBackend` 补齐单元测试。
- [x] 接入 JaCoCo 覆盖率报告任务，并在 README 中补充当前已验证的兼容范围与限制说明。
- [x] 为 `TabooLibBackend`、`taboolibIocDoctor`、`verifyTaboolibIoc` 与核心错误分支补齐测试，提升核心路径覆盖率并验证诊断行为。
- [x] 继续补足剩余插件路径覆盖率，达到可启用覆盖率门槛的水平。
- [x] 评估并落地真实 `io.izzel.taboolib` CI 校验方案，固定当前已验证的 Windows + Java 17 根工程与 example 联调构建流程。

方案：先补纯逻辑单元测试与覆盖率报告，再在覆盖率达标后引入硬性覆盖率门槛并纳入 `build/check`，避免在覆盖率明显不足时把构建流程直接卡死。

完成标准：核心逻辑具备稳定单元测试覆盖，覆盖率报告可持续产出且已纳入 `build/check` 的硬性质量门，兼容范围说明清晰，后续发布与 CI 扩展边界明确。

## 阶段 6：源码 Bean 静态诊断

目标：在不依赖运行时容器启动的前提下，为 consumer 工程提供可直接落地的 Bean/注入点索引与静态诊断能力，提前暴露 IoC 配置风险。

任务：
- [x] 建立 Bean 索引，识别类级 `@Bean`、`@Configuration`、`@Bean` 工厂方法，以及 `beanName`、`@Primary`、`@Order`、条件注解。
- [x] 建立注入点索引，识别构造器参数、字段、方法参数，以及 `@Named`、`@Resource`、`required = false`。
- [x] 新增 `analyzeTaboolibIocBeans` 任务，支持缺失 Bean、名称 Bean 不存在、名称 Bean 类型不兼容、多个 `@Primary`、多个候选且未限定、条件 Bean 才能满足依赖、依赖只能靠运行时手动 Bean 补足、`@ComponentScan` 可能排除某候选。
- [x] 为功能测试夹具与 `example/consumer` 补齐全部规则触发样例，并验证报告输出与示例构建。

方案：基于 `main` 编译产物做字节码扫描，先建立 Bean、注入点、`@ComponentScan` 与类型继承索引，再在 Gradle 任务中输出结构化 JSON 诊断报告，避免把分析能力绑死在运行时容器实现上。

完成标准：`analyzeTaboolibIocBeans` 可稳定产出结构化静态诊断报告，example 中保留全部可触发规则的最小样例，根工程测试与 example 构建验证通过。

## 阶段 7：静态诊断质量门与规则深化

目标：把静态诊断真正接入 consumer 侧质量门，并提升规则在泛型、类型别名、跨模块依赖与条件注解场景下的可信度。

任务：
- [x] 为 `analyzeTaboolibIocBeans` 增加 `analysisFailOnError` / `analysisFailOnWarning` 配置，并接入 `check/build` 质量门与 CI 健康示例模块。
- [x] 扩展静态诊断规则：补齐泛型匹配、Kotlin `typealias` 索引、跨模块依赖 Bean 扫描，以及 `ConditionalOnProperty`、`ConditionalOnClass`、`ConditionalOnMissingClass`、`ConditionalOnBean`、`ConditionalOnMissingBean` 的更细静态判断。
- [x] 调整 example 结构：保留 `consumer` 的错误触发样例模块，同时新增 `quality-gate-consumer` 作为健康质量门模块，并验证两条链路都可复用。

方案：在现有字节码索引基础上补目录 + jar 联合扫描、反射泛型补全、源码别名索引和条件求值，再通过模块级开关把静态诊断纳入 consumer 的 `check/build` 生命周期。

完成标准：健康模块在 `analysisFailOnError = true` 与 `analysisFailOnWarning = true` 下可通过 `build`，错误样例模块仍能稳定输出完整报告，CI 已显式覆盖健康质量门链路。
