# Taboolib IoC Gradle Plugin

一个面向 `io.izzel.taboolib` 的辅助 Gradle 插件，用来把 `top.wcpe.taboolib.ioc:taboolib-ioc` 自动打入 consumer 产物，并自动追加 `top.wcpe.taboolib.ioc -> <目标包>.ioc` 的 relocate 规则。

## 功能概览

- 自动向 `taboo` 配置注入 IoC 依赖。
- 自动推导目标包并追加 IoC relocate。
- 支持本仓库联调时改用 `project(':ioc-lib')` 之类的本地项目依赖。
- 对缺失 `io.izzel.taboolib`、目标包无法推导、手写 relocate 冲突等场景给出明确诊断。
- 预留 `StandaloneBackend` 扩展位，但当前版本只实现 `TABOOLIB` 后端。

## 插件 ID

```groovy
plugins {
    id 'io.izzel.taboolib' version '2.0.36'
    id 'top.wcpe.taboolib.ioc' version '1.0.0-SNAPSHOT'
}
```

## 最小接入

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.3.0'
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

不写任何额外配置时，插件会默认注入：

- 依赖：`top.wcpe.taboolib.ioc:taboolib-ioc:<iocVersion>`
- relocate：`top.wcpe.taboolib.ioc -> <目标包>.ioc`

## DSL

```groovy
taboolibIoc {
    autoTakeover = true
    iocVersion = '1.0.0-SNAPSHOT'
    targetPackage = 'com.example.custom'
    dependencyNotation = 'top.wcpe.taboolib.ioc:taboolib-ioc:1.0.0-SNAPSHOT'
    useLocalProject ':ioc-lib'
}
```

说明：

- `autoTakeover`：关闭后不再自动注入依赖，也不会自动追加 relocate。
- `iocVersion`：默认读取 `taboolib.ioc.version`，若未设置则回退到当前项目版本。
- `targetPackage`：显式指定目标包根，最终 relocate 目标统一为 `<targetPackage>.ioc`。如果已经以 `.ioc` 结尾，则不会重复追加。
- `dependencyNotation`：改用外部 Maven 坐标。
- `useLocalProject(':path')`：本地联调入口，用项目依赖替代外部坐标。

## 目标包推导规则

优先级从高到低：

1. `taboolibIoc.targetPackage`
2. `gradle.properties` 中的 `taboolib.env.group`
3. `project.group`

若三者都不可用，构建会失败并提示如何补齐配置。

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
- `analyzeTaboolibIocBeans`：扫描 `main` 编译产物，建立 Bean 索引与注入点索引，并输出静态诊断报告到 `build/reports/taboolib-ioc/static-diagnosis.json`。

当前静态诊断已支持：

- `error`：缺失 Bean、名称 Bean 不存在、名称 Bean 类型不兼容、多个 `@Primary`。
- `warning`：多个候选且未限定、条件 Bean 才能满足依赖、依赖只能靠运行时手动 Bean 补足、`@ComponentScan` 可能排除某候选。

## 兼容性说明

当前仓库内已经验证通过的组合：

- Java：17
- Gradle Wrapper：8.10.2
- Kotlin JVM Plugin：2.3.0
- `io.izzel.taboolib` Gradle 插件：2.0.36

验证方式：

- 根工程 `test`：覆盖 DSL、resolver、反射辅助、功能测试夹具与本地 project 依赖接管。
- 根工程 `build`：验证插件自身可打包。
- `example` 真实联调：验证 `useLocalProject(':ioc-lib')`、自动依赖注入、显式任务依赖与 relocate 产物路径。

当前限制：

- Gradle 9 尚未声明支持。真实 `io.izzel.taboolib` 在更高版本 Gradle 上仍可能出现上游弃用 API 警告或不兼容行为。
- `StandaloneBackend` 只保留扩展边界，当前不能独立完成打包与 relocate。
- 如果要扩展到更多 `io.izzel.taboolib` 版本，建议把 `example` 联调构建纳入 CI 做版本矩阵验证。

## 质量门

- `build` 现在会通过 `jacocoTestCoverageVerification` 校验根工程测试覆盖率。
- 当前门槛为：行覆盖率不低于 75%，分支覆盖率不低于 55%。
- 覆盖率报告输出位置：`build/reports/jacoco/test/`。

## 发布与版本策略

- Gradle Plugin Marker：由 `java-gradle-plugin` 自动生成。
- Maven 发布：支持 `publishToMavenLocal`，也支持通过 `publish.repo.url`、`publish.repo.username`、`publish.repo.password` 或对应环境变量发布到远端 Maven 仓库。
- Plugin Portal：保留 `publishPlugins` 流程，可通过 `gradle.publish.key`、`gradle.publish.secret` 或对应环境变量发布。
- 建议让插件版本与默认 `iocVersion` 对齐；开发阶段使用 `-SNAPSHOT`，正式发布时移除 `-SNAPSHOT` 并同步更新 README 与 example 版本。
- 详细步骤见 `docs/RELEASE.md`。

## Example

仓库内置了一个独立的真实联调工程：

- `example/consumer`：真实 consumer，应用 `io.izzel.taboolib` 与当前插件。
- `example/ioc-lib`：本地 IoC 库，供 `useLocalProject(':ioc-lib')` 联调。

运行方式：

```powershell
.\gradlew.bat -p example :consumer:build
.\gradlew.bat -p example :consumer:analyzeTaboolibIocBeans
```

构建成功后，可检查 `example/consumer/build/libs` 下的 jar，确认其中已经出现 `com/example/demo/ioc/...`，且不再保留原始的 `top/wcpe/taboolib/ioc/...` 路径。

执行 `analyzeTaboolibIocBeans` 后，可打开 `example/consumer/build/reports/taboolib-ioc/static-diagnosis.json` 查看 example 中所有静态诊断触发样例。