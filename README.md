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

## 发布与版本策略

- Gradle Plugin Marker：由 `java-gradle-plugin` 自动生成。
- Maven 发布：根工程已接入 `maven-publish` 骨架，可根据实际仓库补充仓库地址与凭据。
- 建议让插件版本与默认 `iocVersion` 对齐，降低联调时的认知成本。

## Example

仓库内置了一个独立的真实联调工程：

- `example/consumer`：真实 consumer，应用 `io.izzel.taboolib` 与当前插件。
- `example/ioc-lib`：本地 IoC 库，供 `useLocalProject(':ioc-lib')` 联调。

运行方式：

```powershell
.\gradlew.bat -p example :consumer:build
```

构建成功后，可检查 `example/consumer/build/libs` 下的 jar，确认其中已经出现 `com/example/demo/ioc/...`，且不再保留原始的 `top/wcpe/taboolib/ioc/...` 路径。