# Taboolib IoC Gradle Plugin

一个面向 `io.izzel.taboolib` 的辅助 Gradle 插件，用来把 `top.wcpe.taboolib.ioc:taboolib-ioc` 自动打入 consumer 产物，并自动追加 `top.wcpe.taboolib.ioc -> <目标包>.ioc` 的 relocate 规则。

## 功能概览

- 自动向 `taboo` 配置注入 IoC 依赖。
- 自动推导目标包并追加 IoC relocate。
- 支持本仓库联调时改用 `project(':ioc-lib')` 之类的本地项目依赖。
- 对缺失 `io.izzel.taboolib`、目标包无法推导、手写 relocate 冲突等场景给出明确诊断。
- 编译时静态分析：检测缺失 Bean、类型不兼容、多个 `@Primary` 等注入问题。
- 支持 `@Service`、`@Repository`、`@Controller`、`@Aspect` 注解识别（等同 `@Component`）。
- 预留 `StandaloneBackend` 扩展位，但当前版本只实现 `TABOOLIB` 后端。

## 插件 ID

```groovy
plugins {
    id 'io.izzel.taboolib' version '2.0.36'
    id 'top.wcpe.taboolib.ioc' version '0.0.6'
}
```

## 最小接入

Groovy DSL：

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '1.9.25'
    id 'io.izzel.taboolib' version '2.0.36'
    id 'top.wcpe.taboolib.ioc'
}

group = 'com.example.demo'

taboolib {
    version {
        taboolib = '6.2.3'
        coroutines = '1.7.3'
    }
    env {
        install 'common', 'common-env', 'common-util', 'common-platform-api', 'common-reflex', 'platform-bukkit'
    }
}
```

Kotlin DSL：

```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
    id("io.izzel.taboolib") version "2.0.36"
    id("top.wcpe.taboolib.ioc") version "0.0.6"
}

group = "com.example.demo"
```

不写任何额外配置时，插件会默认注入：

- 依赖：`top.wcpe.taboolib.ioc:taboolib-ioc:<iocVersion>`
- relocate：`top.wcpe.taboolib.ioc -> <目标包>.ioc`

## DSL

Groovy DSL：

```groovy
taboolibIoc {
    // 是否启用自动接管：自动注入 IoC 依赖并自动追加 relocate 规则。
    autoTakeover = true

    // IoC 依赖版本：当未显式指定 dependencyNotation 时，会用于推导默认坐标版本。
    iocVersion = '1.2.0-SNAPSHOT'

    // relocate 目标包根：最终会把 top.wcpe.taboolib.ioc 重定位到 com.example.custom.ioc。
    targetPackage = 'com.example.custom'

    // 静态诊断发现 error 时直接拦截构建。
    analysisFailOnError = true

    // 静态诊断发现 warning 时不拦截构建，仅输出报告。
    analysisFailOnWarning = false

    // 使用外部 Maven 坐标作为 IoC 依赖来源。
    dependencyNotation = 'top.wcpe.taboolib.ioc:taboolib-ioc:1.2.0-SNAPSHOT'

    // 本地联调方式（与 dependencyNotation 二选一）。
    // 当前示例不启用本地项目，这里只保留写法演示。
    // useLocalProject ':ioc-lib'
}
```

Kotlin DSL：

```kotlin
taboolibIoc {
    // 是否启用自动接管：自动注入 IoC 依赖并自动追加 relocate 规则。
    autoTakeover(true)

    // IoC 依赖版本：当未显式指定 dependency(...) 时，会用于推导默认坐标版本。
    iocVersion("1.2.0-SNAPSHOT")

    // relocate 目标包根：最终会把 top.wcpe.taboolib.ioc 重定位到 com.example.custom.ioc。
    targetPackage("com.example.custom")

    // 静态诊断发现 error 时直接拦截构建。
    analysisFailOnError(true)

    // 静态诊断发现 warning 时不拦截构建，仅输出报告。
    analysisFailOnWarning(false)

    // 使用外部 Maven 坐标作为 IoC 依赖来源。
    dependency("top.wcpe.taboolib.ioc:taboolib-ioc:1.2.0-SNAPSHOT")

    // 本地联调方式（与 dependency(...) 二选一）。
    // 当前示例不启用本地项目，这里只保留写法演示。
    // useLocalProject(":ioc-lib")
}
```

如果插件是被其他插件间接 apply 到当前工程，Kotlin DSL 可能拿不到 `taboolibIoc {}` 的类型安全访问器。此时可改用：

```kotlin
import top.wcpe.taboolib.ioc.gradle.TaboolibIocExtension

extensions.configure<TaboolibIocExtension>("taboolibIoc") {
    iocVersion.set("1.2.0-SNAPSHOT")
    targetPackage.set("com.example.custom")
}
```

或者直接在 `gradle.properties` 中声明：

```properties
taboolib.ioc.version=1.2.0-SNAPSHOT
```

说明：

- `autoTakeover`：关闭后不再自动注入依赖，也不会自动追加 relocate。
- `iocVersion`：默认读取 `taboolib.ioc.version`；若未设置，则回退到插件自身打包时携带的版本；再无法确定时才回退到内置默认值 `1.2.0-SNAPSHOT`。不会再默认跟随 consumer 项目版本。
- `targetPackage`：显式指定目标包根，最终 relocate 目标统一为 `<targetPackage>.ioc`。如果已经以 `.ioc` 结尾，则不会重复追加。
- `analysisFailOnError`：默认 `true`，静态诊断发现 error 时让 `analyzeTaboolibIocBeans` 和 `check/build` 失败。
- `analysisFailOnWarning`：默认 `false`，打开后 warning 也会触发质量门失败。
- `dependencyNotation`：改用外部 Maven 坐标。
- `useLocalProject(':path')`：本地联调入口，用项目依赖替代外部坐标。

## 目标包推导规则

优先级从高到低（共 4 级，与 `TaboolibIocResolver.resolveTargetPackage` 实现一致）：

1. `taboolibIoc.targetPackage`（DSL 显式声明）
2. `gradle.properties` 中的 `taboolib.env.group`（Gradle 属性 `taboolib.env.group`）
3. taboolib 扩展的 `taboolib { env { group = ... } }`（`taboolib.env.group`）
4. `project.group`

其中第 2、3 级都读取 `taboolib.env.group` 语义，但来源不同：第 2 级是 Gradle 项目属性
（`gradle.properties` / `-P`），第 3 级是 taboolib 扩展对象上的 `env.group`。若三者都不可用，
构建会失败并提示如何补齐配置。

> 注：relocate 目标统一为 `<目标包根>.ioc`；若推导出的包名与源包 `top.wcpe.taboolib.ioc`
> 存在前缀包含关系（含相等），则不会直接采用，而是追加 `.ioc` 后缀，避免自 relocate。

## 从手写 `taboo + relocate` 迁移

原写法：

```groovy
dependencies {
    taboo project(':ioc-lib')
}

taboolib {
    relocate 'top.wcpe.taboolib.ioc', 'com.example.demo.ioc'
}
```

迁移后：

```groovy
taboolibIoc {
    useLocalProject ':ioc-lib'
}
```

如果保留了原有手写 relocate，插件会在检测到冲突时直接失败，避免产物中出现不一致的 relocate 规则。

## 诊断任务

- `taboolibIocDoctor`：输出当前后端、依赖来源、目标包来源、是否已完成接管。
- `verifyTaboolibIoc`：在 `jar`、`assemble`、`build` 前验证自动接管是否已经生效。
- `analyzeTaboolibIocBeans`：扫描当前模块编译产物，并联动扫描本地 project 依赖与 `taboo` 依赖产物，建立 Bean 索引、注入点索引和类型别名索引，输出静态诊断报告到 `build/reports/taboolib-ioc/static-diagnosis.json`。

当前静态诊断已支持（与 `StaticDiagnosisEngine` 现有规则一致）：

- `error`：
  - 缺失 Bean（`missing-bean`）、名称 Bean 不存在（`named-bean-not-found`）、名称 Bean 类型不兼容（`named-bean-type-mismatch`）；
  - 多构造器且既无 `@Inject` 标注又无无参构造器（`bean-no-resolvable-constructor`）；
  - 多构造器且运行时选中的构造器有参但无 `@Inject`/无参构造器时存在非空注入风险（`bean-runtime-null-injection-risk`）；
  - 字段引用了可注入的 `@Component` Bean 类型但未声明 `@Inject`（`missing-inject-annotation`）；
  - 接口/抽象类/枚举被声明为组件（`bean-type-not-instantiable`）；
  - 未注册作用域（`unknown-bean-scope`，自定义作用域可通过项目属性 `taboolib.ioc.knownScopes` 逗号分隔声明）；
  - 生命周期方法带参（`lifecycle-method-signature-invalid`，含 Kotlin suspend 编译出的 Continuation 参数）；
  - `@Bean` 方法不在 `@Configuration` 宿主上（`bean-method-outside-configuration`）；
  - `@Bean` 方法返回 void/Unit（`bean-method-void-return`）；
  - `@Value` 表达式包含占位符但非整串单一占位符（`value-expression-unresolved-placeholder`，运行时会整串按字面量注入）；
  - `@Value` 目标类型不受支持（`value-type-unsupported`，仅支持 String 与基本类型/包装类）；
  - 切点表达式非法或引用未声明的 `@Pointcut`（`pointcut-expression-invalid`，运行时会导致插件 enable 失败）；
  - `@Around` 通知签名非法（`advice-signature-invalid`，仅允许单个 `MethodInvocation` 参数，否则容器初始化切面直接失败）；
  - 构造函数循环依赖，或跨作用域且无法解析的循环依赖（`circular-dependency-detected`）。
- `warning`：
  - 多个候选且未限定（`multiple-candidates-unqualified`）、多个 `@Primary` 冲突时的候选歧义（`multiple-primary-beans`）；
  - 条件 Bean 无法被静态完全判定（`conditional-bean-only`）、依赖只能靠运行时手动 Bean 补足（`runtime-manual-bean-only`）；
  - `@ComponentScan` 可能排除某候选（`component-scan-may-exclude`）；
  - 多构造器且未显式标注 `@Inject constructor`，可能被容器选错构造（`bean-constructor-not-explicitly-injected`）；
  - 由早期暴露解析、或已被接口类型 `@Lazy` 代理断开的可解析循环依赖（`circular-dependency-detected`）；
  - `@RefreshScope` Bean 持有资源类型字段但缺少 `@PreDestroy`（`refresh-scope-missing-predestroy`）；
  - `@AfterReturning`/`@AfterThrowing` 通知签名非法（`advice-signature-invalid`，运行时被静默吞掉、通知永久失效）；
  - 切点目标类/方法在扫描范围内不存在（`pointcut-target-not-found`）；
  - 切点仅命中 private 方法（`aop-private-method-pointcut`）、仅命中 static 方法（`aop-static-method-pointcut`）；
  - 被通知的 Bean 未实现任何接口，JDK 动态代理无法包装（`aop-target-not-proxied`）；
  - `@Bean` 工厂方法声明返回接口类型且被切面命中，运行时按声明类型收集接口必为空（`aop-factory-bean-interface-return`）；
  - 同名 Bean（`duplicate-bean-name`，运行时先注册者胜出且取决于 jar 内类顺序）。

  > 说明：同名 Bean 在运行时是被**静默跳过**（不报错、插件仍能启动），因此静态严重度对齐为 WARNING，避免误触 `failOnError` 阻断。
- `info`：
  - `@ThreadScope` Bean 在线程池环境需手动调用 `clearCurrentThread()` 防内存泄漏（`thread-scope-usage-warning`）。

> 可选规则：设置项目属性 `taboolib.ioc.forbidComponentAnnotation=true` 时，检测到 `@Component` 会额外产生 `forbidden-component-annotation` 的 warning（用于团队约定「只用 `@Service`/`@Repository`/`@Inject`」）。

同名类出现在多个扫描根（如同时存在于 compileClasspath 与 `taboo` 依赖）时会按「项目输出优先」去重，不产生假阳性。

Bean 注解识别范围：

- `@Component`、`@Service`、`@Repository`、`@Controller`、`@Aspect` 均被视为等价的组件注解。
- `@Configuration` 被识别为配置类。
- `@Bean` 被识别为工厂方法。

说明：

- 如果某个条件 Bean 在当前构建下已能静态判定为“不满足条件”，并且因此导致依赖拿不到 Bean，当前会直接按 `missing-bean` 记为 `error`。
- 只有当条件表达式本身无法被静态完全判断时，才会保留为 `conditional-bean-only` 的 `warning`。

当前额外支持的判定增强：

- 泛型注入匹配：优先按 `MessageBox<String>` 这类泛型签名匹配 Bean，降低原始类型导致的误报。
- Kotlin `typealias` 索引：报告中会额外输出 `typeAliasIndex`，便于把源码别名和字节码类型对应起来。
- 更细的条件判断：支持 `ConditionalOnProperty`、`ConditionalOnClass`、`ConditionalOnMissingClass`、`ConditionalOnBean`、`ConditionalOnMissingBean` 的静态启停判断。

## 兼容性说明

当前仓库内已经验证通过的组合：

- Java：17
- Gradle Wrapper：8.14.4
- Kotlin JVM Plugin：1.9.25
- `io.izzel.taboolib` Gradle 插件：2.0.36

验证方式：

- 根工程 `test`：覆盖 DSL、resolver、反射辅助、功能测试夹具与本地 project 依赖接管。
- 根工程 `build`：验证插件自身可打包。
- `example` 真实联调：验证 `dependency(...)` 依赖接入路径、自动依赖注入、显式任务依赖与 relocate 产物路径。

当前限制：

- Gradle 9 尚未声明支持。真实 `io.izzel.taboolib` 在更高版本 Gradle 上仍可能出现上游弃用 API 警告或不兼容行为。
- `StandaloneBackend` 只保留扩展边界，当前不能独立完成打包与 relocate。
- 如果要扩展到更多 `io.izzel.taboolib` 版本，建议把 `example` 联调构建纳入 CI 做版本矩阵验证。

## 质量门

- consumer 工程应用本插件后，`check/build` 现在会自动依赖 `analyzeTaboolibIocBeans`，默认把静态诊断 error 接入质量门。
- `analysisFailOnError` 默认开启；`analysisFailOnWarning` 默认关闭，可按模块显式调整。
- 构建被拦下时，异常信息会直接打印规则名、注入点类名/声明名、依赖类型、候选 Bean 和报告路径，避免只能看到错误数量。
- 同时会额外输出 `源码绝对路径:行:列: error|warning: ...` 的问题行，IntelliJ IDEA、VS Code 等通常可以直接点击跳转到对应源码位置。
- 静态诊断也会接入 Gradle Problems API，因此 `build/reports/problems/problems-report.html` 中会出现结构化的 `Taboolib IoC / Static Diagnosis` 问题项，IDEA 导入 Gradle 项目时可利用这层结构化问题信息。
- 根工程 `build` 现在会通过 `jacocoTestCoverageVerification` 校验根工程测试覆盖率。
- 当前门槛为：行覆盖率不低于 75%，分支覆盖率不低于 55%。
- 覆盖率报告输出位置：`build/reports/jacoco/test/`。

## 发布与版本策略

- Gradle Plugin Marker：由 `java-gradle-plugin` 自动生成。
- Maven 发布：支持 `publishToMavenLocal`，也支持通过 `publish.repo.url`、`publish.repo.username`、`publish.repo.password` 或对应环境变量发布到远端 Maven 仓库。
- Plugin Portal：保留 `publishPlugins` 流程，可通过 `gradle.publish.key`、`gradle.publish.secret` 或对应环境变量发布。
- 建议让插件版本与默认 `iocVersion` 对齐；开发阶段使用 `-SNAPSHOT`，正式发布时移除 `-SNAPSHOT` 并同步更新 README 与 example 版本。
- 详细步骤见 `docs/RELEASE.md`。

## Example

仓库内置了两份真实语法示例工程：

- `example/groovy-consumer`：Groovy DSL 集成样例，完整展示 `build.gradle` 中 `taboolibIoc {}` 的 Groovy DSL 配置与中文注释；实际通过 `dependencyNotation` 消费预发布到 `mavenLocal` 的 `taboolib-ioc`，不直接使用本地 project。
- `example/kotlin-consumer`：Kotlin DSL 集成样例，完整展示 `build.gradle.kts` 中 `taboolibIoc {}` 的 Kotlin DSL 配置与中文注释；实际通过 `dependency(...)` 消费预发布到 `mavenLocal` 的 `taboolib-ioc`，不直接使用本地 project。

两个示例工程由 `example/settings.gradle.kts` 通过 `-Pexample.modules=<模块名>` 按需加载（默认不加载任何子模块），各自直接消费外部坐标。

如果你希望只生成报告而不拦截构建，需要显式关闭质量门，例如：

```groovy
taboolibIoc {
    analysisFailOnError = false
    analysisFailOnWarning = false
}
```

或者在命令行临时跳过：

```powershell
.\gradlew.bat -p example :groovy-consumer:build -P "taboolib.ioc.analysis.fail-on-error=false" -P "taboolib.ioc.analysis.fail-on-warning=false"
```

运行方式（示例工程只消费已经发布到本地 Maven 仓库的 `taboolib-ioc` 坐标；
该坐标需先在 IoC 本体仓库执行 `publishToMavenLocal` 生成）：

```powershell
$exampleLocalRepo = Join-Path (Resolve-Path "example").Path ".m2-local"
# 先发布 IoC 本体（在 taboolib-ioc 仓库根目录执行，指向隔离的本地仓库）
# .\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=$exampleLocalRepo"
.\gradlew.bat -p example :groovy-consumer:build -Pexample.modules=groovy-consumer "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :kotlin-consumer:build -Pexample.modules=kotlin-consumer "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :groovy-consumer:analyzeTaboolibIocBeans -Pexample.modules=groovy-consumer "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :kotlin-consumer:analyzeTaboolibIocBeans -Pexample.modules=kotlin-consumer "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
```

其中 `:groovy-consumer:build` 和 `:kotlin-consumer:build` 前都需要先在 IoC 本体仓库执行 `publishToMavenLocal`（产出 `top.wcpe.taboolib.ioc:taboolib-ioc:<版本>` 坐标），并配合 `-Pexample.modules=...` 只加载当前需要的 example 模块；同时通过 `Join-Path (Resolve-Path "example").Path ".m2-local"` 生成隔离的本地 Maven 仓库绝对路径，避免与全局 `~/.m2` 中已有的同名 SNAPSHOT 冲突；追加 `--refresh-dependencies` 是为了让 Gradle 重新读取刚发布的本地 SNAPSHOT。

构建成功后，可检查：

- `example/groovy-consumer/build/libs` 下的 jar，确认其中已经出现 `com/example/groovy/ioc/...`。
- `example/kotlin-consumer/build/libs` 下的 jar，确认其中已经出现 `com/example/custom/ioc/...`。
- 两个产物中都不再保留原始的 `top/wcpe/taboolib/ioc/...` 路径。

执行 `analyzeTaboolibIocBeans` 后，可打开对应模块下的 `build/reports/taboolib-ioc/static-diagnosis.json` 查看静态诊断报告。

## 更新日志

详见 [CHANGELOG.md](./CHANGELOG.md)。
