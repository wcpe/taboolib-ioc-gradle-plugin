# Taboolib IoC Gradle Plugin

[![CI](https://github.com/wcpe/taboolib-ioc-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/wcpe/taboolib-ioc-gradle-plugin/actions/workflows/ci.yml)
[![Maven](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.wcpe.top%2Frepository%2Fmaven-releases%2Ftop%2Fwcpe%2Ftaboolib%2Fioc%2Ftaboolib-ioc-gradle-plugin%2Fmaven-metadata.xml&label=maven.wcpe.top)](https://maven.wcpe.top/repository/maven-releases/top/wcpe/taboolib/ioc/taboolib-ioc-gradle-plugin/maven-metadata.xml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%20toolchain-blue.svg)](#环境要求)
[![Gradle](https://img.shields.io/badge/Gradle-8.9%20~%208.14.4-blue.svg)](#环境要求)

一个面向 `io.izzel.taboolib` 的辅助 Gradle 插件，用来把 `top.wcpe.taboolib.ioc:taboolib-ioc` 自动打入 consumer 产物，并自动追加 `top.wcpe.taboolib.ioc -> <目标包>.ioc` 的 relocate 规则。

## 目录

- [功能概览](#功能概览)
- [环境要求](#环境要求)
- [插件 ID](#插件-id)
- [最小接入](#最小接入)
- [DSL](#dsl)
- [目标包推导规则](#目标包推导规则)
- [从手写 `taboo + relocate` 迁移](#从手写-taboo--relocate-迁移)
- [诊断任务](#诊断任务)
- [编译期 AOP 织入（`weaving(true)`）](#编译期-aop-织入weavingtrue)
- [兼容性说明](#兼容性说明)
- [质量门](#质量门)
- [排障](#排障)
- [发布与版本策略](#发布与版本策略)
- [Example](#example)
- [贡献](#贡献)
- [许可证](#许可证)
- [更新日志](#更新日志)

## 功能概览

- 自动向 `taboo` 配置注入 IoC 依赖。
- 自动推导目标包并追加 IoC relocate。
- 支持本仓库联调时改用 `project(':ioc-lib')` 之类的本地项目依赖。
- 对缺失 `io.izzel.taboolib`、目标包无法推导、手写 relocate 冲突等场景给出明确诊断。
- 编译时静态分析：检测缺失 Bean、类型不兼容、多个 `@Primary` 等注入问题。
- 支持 `@Service`、`@Repository`、`@Controller`、`@Aspect` 注解识别（等同 `@Component`）。
- 预留 `StandaloneBackend` 扩展位，但当前版本只实现 `TABOOLIB` 后端。

## 环境要求

| 场景 | 要求 |
| --- | --- |
| 应用本插件的 consumer 工程 | **JDK 17+**；已验证 Gradle 8.9 ~ 8.14.4 |
| 构建 / 测试本仓库自身 | **JDK 21+**（见下方说明） |

本仓库已验证通过的组合：

- Java：17（插件本体以 Java 17 toolchain 编译）
- Gradle Wrapper：8.14.4
- Kotlin JVM Plugin：1.9.25
- `io.izzel.taboolib` Gradle 插件：2.0.38-wcpe.1

> **为什么本仓库构建需要 JDK 21+**：插件本体仍以 Java 17 toolchain 编译，但运行构建的 Gradle 守护进程与嵌套 `GradleRunner` 构建需要 JDK 21+。被测 fixture 依赖的 `mc-testkit` 模块元数据要求 JVM 21+，在 Java 17 下会在配置期直接报 `Dependency requires at least JVM runtime version 21`。CI 因此同时安装 17 与 21，后安装的 21 成为 `JAVA_HOME`。

当前限制：

- **Gradle 9 尚未声明支持。** 真实 `io.izzel.taboolib` 在更高版本 Gradle 上仍可能出现上游弃用 API 警告或不兼容行为。
- `StandaloneBackend` 只保留扩展边界，当前不能独立完成打包与 relocate。

## 插件 ID

```groovy
plugins {
    id 'io.izzel.taboolib' version '2.0.38-wcpe.1'
    id 'top.wcpe.taboolib.ioc' version '0.0.11'
}
```

> **必要前置：仓库声明。** 插件为 Plugin Marker 发布的 `top.wcpe.taboolib.ioc` 在官方 Gradle Plugin Portal 上**尚未上架**，
> 上述 `plugins { id ... version ... }` 片段**单独照抄会失败**（`Plugin [id: 'top.wcpe.taboolib.ioc', version: '0.0.11'] was not found`）。
> 必须在 `settings.gradle` / `settings.gradle.kts` 的 `pluginManagement { repositories { ... } }` 中声明自建 Maven
> `https://maven.wcpe.top/repository/maven-releases/`（仓库内 `example/settings.gradle.kts` 即为此写法）：

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.wcpe.top/repository/maven-releases/")
        maven("https://maven.aliyun.com/repository/public")
        mavenLocal()
        maven("https://maven.wcpe.top/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("top.wcpe.taboolib.ioc") version "0.0.11"
    }
}
```

Groovy 版 `settings.gradle`：

```groovy
pluginManagement {
    repositories {
        maven { url = uri('https://maven.wcpe.top/repository/maven-releases/') }
        maven { url = uri('https://maven.aliyun.com/repository/public') }
        mavenLocal()
        maven { url = uri('https://maven.wcpe.top/repository/maven-public/') }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id 'top.wcpe.taboolib.ioc' version '0.0.11'
    }
}
```

`pluginManagement.repositories` 的顺序与 `example/settings.gradle.kts` 保持一致，其中 **`mavenLocal()` 必须前置** —— example 声明的插件版本已发布到 `maven-releases`，插件解析按仓库顺序首个命中即止，若 releases 排在前面，验的就是远端已发布插件而不是本次构建产物：wcpe 镜像为部分镜像，
`maven-releases` 必须前置，否则 `io.izzel.taboolib` 等坐标可能解析失败。

## 最小接入

<details>
<summary><b>展开</b> · 只需要一行插件 id；Groovy / Kotlin DSL 两种写法对照</summary>


Groovy DSL：

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '1.9.25'
    id 'io.izzel.taboolib' version '2.0.38-wcpe.1'
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
    id("io.izzel.taboolib") version "2.0.38-wcpe.1"
    id("top.wcpe.taboolib.ioc") version "0.0.11"
}

group = "com.example.demo"
```

不写任何额外配置时，插件会默认注入：

- 依赖：`top.wcpe.taboolib.ioc:taboolib-ioc:<iocVersion>`
- relocate：`top.wcpe.taboolib.ioc -> <目标包>.ioc`

</details>

## DSL

<details>
<summary><b>展开</b> · <code>taboolibIoc { }</code> 全部配置项与含义（<code>autoTakeover</code> / <code>iocVersion</code> / <code>targetPackage</code> / <code>weaving</code> / 诊断开关）</summary>


Groovy DSL：

```groovy
taboolibIoc {
    // 是否启用自动接管：自动注入 IoC 依赖并自动追加 relocate 规则。
    autoTakeover = true

    // IoC 依赖版本：当未显式指定 dependencyNotation 时，会用于推导默认坐标版本。
    iocVersion = '1.2.0-SNAPSHOT'

    // relocate 目标包根：最终会把 top.wcpe.taboolib.ioc 重定位到 com.example.custom.ioc。
    targetPackage = 'com.example.custom'

    // 编译期 AOP 织入：开启后被切点命中的方法在构建期被改写为转发，具体类也能被切、且不创建代理。
    // 也可以写在 gradle.properties：taboolib.ioc.weaving=true
    weaving = false

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

    // 编译期 AOP 织入：开启后被切点命中的方法在构建期被改写为转发，具体类也能被切、且不创建代理。
    // 也可以写在 gradle.properties：taboolib.ioc.weaving=true
    weaving(false)

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
- `weaving`：默认 `false`。读取 `taboolib.ioc.weaving`；开启后 `weaveTaboolibIocAop` 生效，详见 [编译期 AOP 织入](#编译期-aop-织入weavingtrue)。
- `analysisFailOnError`：默认 `true`，静态诊断发现 error 时让 `analyzeTaboolibIocBeans` 和 `check/build` 失败。
- `analysisFailOnWarning`：默认 `false`，打开后 warning 也会触发质量门失败。
- `dependencyNotation`：改用外部 Maven 坐标。
- `useLocalProject(':path')`：本地联调入口，用项目依赖替代外部坐标。

</details>

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

<details>
<summary><b>展开</b> · 原写法与迁移后写法对照；残留的手写 relocate 若与插件冲突会直接失败</summary>


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

</details>

## 诊断任务

<details>
<summary><b>展开</b> · <code>taboolibIocDoctor</code>：输出当前后端、依赖来源…</summary>


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
  - 构造函数循环依赖，或跨作用域且无法解析的循环依赖（`circular-dependency-detected`）；
  - 多个候选且存在多个 `@Primary` 导致的候选歧义（`multiple-primary-beans`，运行时会直接抛出「多个候选」异常）。
- `warning`：
  - 多个候选且未限定（`multiple-candidates-unqualified`）；
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
  >
  > **反例（刻意保持 ERROR）**：泛型实参不匹配的 `missing-bean`。运行期按**擦除**类型匹配、不读泛型（`taboolib-ioc` 全仓无泛型反射调用），因此注入这一步会成功；但注入进来的 Bean 与注入点声明的类型实参不自洽，任何依赖类型实参的使用点都会被编译器插入 `checkcast`，**运行期抛 `ClassCastException`**。这属于「运行时失败」而非「静默降级」，不满足上面那条降级条件，故保持 ERROR。诊断消息里会写明这一点，便于判断。
  >
  > 已知限制：`Port<? extends X>` / Kotlin `Port<out X>` 这类**协变**声明目前仍会被判为不匹配 —— 采集阶段会把 `? extends ` 剥掉、通配符信息丢失，导致无法做子类型判定。这是误报，但不是严重度问题，修正它需要改采集侧的归一化。
- `info`：
  - `@ThreadScope` Bean 在线程池环境需手动调用 `clearCurrentThread()` 防内存泄漏（`thread-scope-usage-warning`）。

> 可选规则：设置项目属性 `taboolib.ioc.forbidComponentAnnotation=true` 时，检测到 `@Component` 会额外产生 `forbidden-component-annotation` 的 warning（用于团队约定「只用 `@Service`/`@Repository`/`@Inject`」）。

> **AOP 静态诊断规则组**（本版本新增）：`pointcut-target-not-found`、`aop-private-method-pointcut`、`aop-static-method-pointcut`、`aop-target-not-proxied`、`aop-factory-bean-interface-return` 默认均为 WARNING；其中 `aop-factory-bean-interface-return` 在 `weaving=true` 时降为 INFO（只作提示、不阻断）。`advice-signature-invalid` 视通知类型区分：`@Around` 为 ERROR，`@AfterReturning`/`@AfterThrowing` 为 WARNING。这类问题在运行时要么静默失效、要么导致插件 `enable` 失败，编译期即可拦截。

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

</details>

## 编译期 AOP 织入（`weaving(true)`）

<details>
<summary><b>展开</b> · JDK 动态代理要求被切的目标实现接口…</summary>


JDK 动态代理要求被切的目标**实现接口**，因此具体类切面会被静默跳过（静态诊断以 `aop-target-not-proxied` 提示）。
开启织入后，插件在**构建期**用 ASM 把被切点命中的 public 实例方法改写为转发到 `AopWeavingRuntime`，
原方法体搬到合成方法 `xxx$ioc$original`：

```java
public String greet(String name) {
    return (String) AopWeavingRuntime.invoke(this, "greet(Ljava/lang/String;)Ljava/lang/String;",
            "greet$ioc$original", new Object[]{ name });
}
public synthetic String greet$ioc$original(String name) { /* 原方法体原样保留 */ }
```

- **具体类也能被切**，且运行期**不创建任何代理**；
- 第二个参数（**方法名 + 描述符**）在**构建期算好**写成字符串常量，运行期只做一次 map 查表 —— 不反射解析 `Method`、不拼接字符串；
- 被织入的类会实现 `WovenTarget` 标记接口，运行期容器见到它即跳过代理，避免通知执行两次；**该标记随字节码一起 relocate，天然安全**；
- 幂等：已织入的类不会二次处理，构建任务可安全重跑；
- 不改写 `static` / `private` / `abstract` / `native` / 合成方法与构造器；带 `@NoAspect` 的类与方法跳过；
- ASM 是**构建期依赖**，不会进入你的插件 jar。

相关任务：

| 任务 | 说明 |
|---|---|
| `weaveTaboolibIocAop` | 执行织入；`weaving(false)` 时为 disabled。挂在 `jar` / `assemble` / `build` / `taboolibMainTask` 之前 |

开关来源（优先级从高到低）：`taboolibIoc { weaving(true) }` → `gradle.properties` 的 `taboolib.ioc.weaving=true` → 默认 `false`。

> **局限**：织入后的类运行期不再创建代理，因此「运行期动态注册的、命中该类**未被织入方法**的切面」不会生效 ——
> 请让构建期的切面集合覆盖你需要的全部切点。

</details>

## 兼容性说明

<details>
<summary><b>展开</b> · 当前仓库内已经验证通过的组合</summary>


当前仓库内已经验证通过的组合：

- Java：17（插件本体以 Java 17 toolchain 编译）
- Gradle Wrapper：8.14.4
- Kotlin JVM Plugin：1.9.25
- `io.izzel.taboolib` Gradle 插件：2.0.38-wcpe.1

> **构建/测试需 JDK 21+**：插件本体仍以 Java 17 toolchain 编译，但运行构建的 Gradle 守护进程与嵌套 `GradleRunner` 构建需 JDK 21+。被测 fixture 依赖的 `mc-testkit` 模块元数据要求 JVM 21+，在 Java 17 下会在配置期直接报 `Dependency requires at least JVM runtime version 21`（CI 因此同时安装 17 与 21，后装的 21 成为 `JAVA_HOME`）。

验证方式：

- 根工程 `test`：覆盖 DSL、resolver、反射辅助、功能测试夹具与本地 project 依赖接管。
- 根工程 `build`：验证插件自身可打包。
- `example` 真实联调：验证 `dependency(...)` 依赖接入路径、自动依赖注入、显式任务依赖与 relocate 产物路径。

当前限制：

- Gradle 9 尚未声明支持。真实 `io.izzel.taboolib` 在更高版本 Gradle 上仍可能出现上游弃用 API 警告或不兼容行为。
- `StandaloneBackend` 只保留扩展边界，当前不能独立完成打包与 relocate。
- 如果要扩展到更多 `io.izzel.taboolib` 版本，建议把 `example` 联调构建纳入 CI 做版本矩阵验证。

</details>

## 质量门

<details>
<summary><b>展开</b> · consumer 工程应用本插件后，<code>check/build</code> 现在会自动依赖…</summary>


- consumer 工程应用本插件后，`check/build` 现在会自动依赖 `analyzeTaboolibIocBeans`，默认把静态诊断 error 接入质量门。
- `analysisFailOnError` 默认开启；`analysisFailOnWarning` 默认关闭，可按模块显式调整。
- 构建被拦下时，异常信息会直接打印规则名、注入点类名/声明名、依赖类型、候选 Bean 和报告路径，避免只能看到错误数量。
- 同时会额外输出 `源码绝对路径:行:列: error|warning: ...` 的问题行，IntelliJ IDEA、VS Code 等通常可以直接点击跳转到对应源码位置。
- 静态诊断也会接入 Gradle Problems API，因此 `build/reports/problems/problems-report.html` 中会出现结构化的 `Taboolib IoC / Static Diagnosis` 问题项，IDEA 导入 Gradle 项目时可利用这层结构化问题信息。
- 根工程 `build` 现在会通过 `jacocoTestCoverageVerification` 校验根工程测试覆盖率。
- 当前门槛为：行覆盖率不低于 75%，分支覆盖率不低于 55%。
- 覆盖率报告输出位置：`build/reports/jacoco/test/`。

</details>

## 排障

### 构建被静态诊断拦下

`check`/`build` 会通过 `analyzeTaboolibIocBeans` 把静态诊断 error 接入质量门，`analysisFailOnError` 默认开启。被拦下时按规则名定位：

| 规则 | 含义 | 处理 |
| --- | --- | --- |
| `missing-inject-annotation` | 字段引用了可注入的 `@Component` 类型，但未声明 `@Inject` | 补 `@Inject`；若该字段确实由业务自行初始化或手工装配，源码里的初值 / 赋值会被识别并自动豁免 |
| `missing-bean` | 注入点的依赖类型找不到匹配的 Bean 候选 | 检查目标类型是否在扫描范围内、是否为 `@Component` 及其同义注解、以及依赖类型与候选类型是否可赋值 |
| `named-bean-not-found` | 限定名找不到对应 Bean | 核对 `@Named` 值与实际 Bean 名 |
| `named-bean-type-mismatch` | 限定名指向的 Bean 类型与注入点不兼容 | 核对 `@Named` 值与目标 Bean |
| `multiple-primary-beans` | 存在多个 `@Primary` 候选 | 只保留一个 `@Primary` |
| `multiple-candidates-unqualified` | 多个候选且无限定（默认仅告警） | 用 `@Named` 或 `@Primary` 消歧 |
| `aop-target-not-proxied` | AOP 目标未被代理 | 见[编译期 AOP 织入](#编译期-aop-织入weavingtrue)一节 |

只出报告、不拦构建：

```groovy
taboolibIoc {
    analysisFailOnError = false
    analysisFailOnWarning = false
}
```

或命令行临时跳过：

```powershell
.\gradlew.bat build -P "taboolib.ioc.analysis.fail-on-error=false" -P "taboolib.ioc.analysis.fail-on-warning=false"
```

报告位置：`build/reports/taboolib-ioc/static-diagnosis.json`。

### `Dependency requires at least JVM runtime version 21`

在 Java 17 下运行构建时出现 —— Gradle 守护进程与嵌套构建需要 JDK 21+（插件本体仍以 17 toolchain 编译）。把 `JAVA_HOME` 切到 JDK 21+ 重跑。

### 目标包无法推导

见[目标包推导规则](#目标包推导规则)；推导不出时显式配置 `targetPackage`。

### 升级 Gradle 后行为异常

Gradle 9 尚未声明支持，请留在已验证的 8.9 ~ 8.14.4 区间。

## 发布与版本策略

<details>
<summary><b>展开</b> · Maven 坐标、Plugin Marker 与 tag 驱动发版流程</summary>


- Gradle Plugin Marker：由 `java-gradle-plugin` 自动生成。
- Maven 发布：支持 `publishToMavenLocal`；发布到自建 Maven 时**仓库 URL 由版本号决定**，不接受任何属性配置 —— 版本号含 `-SNAPSHOT` 走 `https://maven.wcpe.top/repository/maven-snapshots/`，否则走 `https://maven.wcpe.top/repository/maven-releases/`（见 `build.gradle.kts` 中 `publishing` 块的固定地址）。凭据通过 Gradle 属性读取，顺序为 `WCPE_MAVEN_USERNAME`（回退 `username`）与 `WCPE_MAVEN_PASSWORD`（回退 `password`）；因为 `WCPE_MAVEN_*` 优先，同时设置了 `username` 也不会被采用。注意 `findProperty` **只认 Gradle 属性**（`-P` 或 `gradle.properties`），裸环境变量看不到；CI 里要用环境变量必须写成 `ORG_GRADLE_PROJECT_WCPE_MAVEN_USERNAME` / `ORG_GRADLE_PROJECT_WCPE_MAVEN_PASSWORD`。
- Plugin Portal：保留 `publishPlugins` 流程，可通过 `gradle.publish.key`、`gradle.publish.secret` 或对应环境变量发布。**注意当前版本尚未上架 Plugin Portal**（见 [插件 ID](#插件-id) 的仓库前置说明），消费者应改用自建 Maven 坐标。
- 建议让插件版本与默认 `iocVersion` 对齐；开发阶段使用 `-SNAPSHOT`，正式发布时移除 `-SNAPSHOT` 并同步更新 README 版本引用与 `example/settings.gradle.kts` 中的插件版本。
- `printPublishTargets` 任务打印的 `[publish] remote = ...` 一列只回显 `publish.repo.url` / `MAVEN_PUBLISH_URL` 属性，**不参与发布配置**，不要把它当作「远端仓库已配置生效」的证明；真实目标以版本号决定。
- 详细步骤见 `docs/RELEASE.md`。

</details>

## Example

<details>
<summary><b>展开</b> · 仓库内置了两份真实语法示例工程</summary>


仓库内置了两份真实语法示例工程：

- `example/groovy-consumer`：Groovy DSL 集成样例，完整展示 `build.gradle` 中 `taboolibIoc {}` 的 Groovy DSL 配置与中文注释；通过自动接管（`autoTakeover = true`）注入的默认坐标消费 `taboolib-ioc`（优先 `mavenLocal`，否则 `maven-public`），不直接使用本地 project，示例中也**未启用** `dependencyNotation`（该行是注释）。
- `example/kotlin-consumer`：Kotlin DSL 集成样例，完整展示 `build.gradle.kts` 中 `taboolibIoc {}` 的 Kotlin DSL 配置与中文注释；同样通过自动接管注入的默认坐标消费 `taboolib-ioc`（优先 `mavenLocal`，否则 `maven-public`），不直接使用本地 project，示例中**未启用** `dependency(...)`（该行是注释）。

两个示例工程在 `example/settings.gradle.kts` 中**总是被一起加载**（`include("groovy-consumer", "kotlin-consumer")` 是无条件调用，不存在按属性开关的机制），各自直接消费外部坐标。

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

运行方式（示例工程默认从 wcpe 的 `maven-public` 解析 `taboolib-ioc:1.2.0-SNAPSHOT`，**不需要**先本地发布；
只有在离线或要改版本验证时，才按下面注释里的方式自行 `publishToMavenLocal`，并同步示例的 `iocVersion`）：

```powershell
$exampleLocalRepo = Join-Path (Resolve-Path "example").Path ".m2-local"
# 先发布 IoC 本体（在 taboolib-ioc 仓库根目录执行，指向隔离的本地仓库）
# .\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=$exampleLocalRepo"
.\gradlew.bat -p example :groovy-consumer:build "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :kotlin-consumer:build "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :groovy-consumer:analyzeTaboolibIocBeans "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :kotlin-consumer:analyzeTaboolibIocBeans "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
```

其中 `:groovy-consumer:build` 和 `:kotlin-consumer:build` 默认从 `maven-public` 解析 `taboolib-ioc:1.2.0-SNAPSHOT`，**不需要**先本地发布；只有按上面注解的方式改成本地仓库时才需要先在 IoC 本体仓库执行 `publishToMavenLocal`（产出 `top.wcpe.taboolib.ioc:taboolib-ioc:<版本>` 坐标）；`example/settings.gradle.kts` 会无条件加载两个示例模块，任务用 `:模块名:` 前缀指定即可（仓库中**不存在**任何按需加载示例模块的 Gradle 属性开关，也没有任何构建脚本读取这类属性）。同时通过 `Join-Path (Resolve-Path "example").Path ".m2-local"` 生成隔离的本地 Maven 仓库绝对路径，避免与全局 `~/.m2` 中已有的同名 SNAPSHOT 冲突；追加 `--refresh-dependencies` 是为了让 Gradle 重新读取刚发布的本地 SNAPSHOT。

构建成功后，可检查：

- `example/groovy-consumer/build/libs` 下的 jar，确认其中已经出现 `top/wcpe/mc/plugin/taboolib/ioc/example/ioc/annotation/Component.class`。
- `example/kotlin-consumer/build/libs` 下的 jar，确认其中已经出现同一条路径 `top/wcpe/mc/plugin/taboolib/ioc/example/ioc/annotation/Component.class`。
- 两个产物中都不再保留原始的 `top/wcpe/taboolib/ioc/...` 路径。

> 两个示例的重定位结果**完全相同**：groovy 示例的 `targetPackage` 是 `top.wcpe.mc.plugin.taboolib.ioc.example`（`example/groovy-consumer/build.gradle`），
> kotlin 示例未设置 `targetPackage`，取 `example/gradle.properties` 的 `group`（同值），最终 relocate 目标都是 `top.wcpe.mc.plugin.taboolib.ioc.example.ioc`。
> 上述条目与 `ExampleProjectSmokeTest` 的断言常量一致。

执行 `analyzeTaboolibIocBeans` 后，可打开对应模块下的 `build/reports/taboolib-ioc/static-diagnosis.json` 查看静态诊断报告。

</details>

## 贡献

欢迎提交 Issue 与 PR。**一切变更都走 PR，禁止直接 push 到 `master`（hotfix 也不例外）。**

提交信息使用中文 Conventional Commits（`<type>(<scope>): <中文描述>`，type 与 scope 用英文小写），正文写清「为什么改 + 改动要点」，**不带任何 AI 署名或尾注**。合并统一走 squash，合并提交标题需带 PR 编号 `(#<n>)`，正文必须是对 PR 内容的总结而非照抄 PR 描述。

完整规范（分支命名、PR 标题、合并命令、Issue 关联、禁止入库内容、可选本地钩子）见 [.github/CONTRIBUTING.md](./.github/CONTRIBUTING.md)。

## 许可证

本项目以 [MIT 许可证](./LICENSE) 发布。

## 更新日志

详见 [CHANGELOG.md](./CHANGELOG.md)。
