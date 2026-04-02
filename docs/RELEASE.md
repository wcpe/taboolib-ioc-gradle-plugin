# 发布说明

## 版本策略

- 开发迭代默认使用 `1.0.0-SNAPSHOT` 这一类快照版本。
- 正式发布时移除 `-SNAPSHOT`，并同步更新 `gradle.properties`、README 中的插件示例版本以及需要发布的变更说明。
- 建议保持插件版本与默认 `taboolibIoc.iocVersion` 对齐，降低使用者在联调和排障时的版本心智成本。

## 产物组成

- `pluginMaven`：主插件发布件。
- `top.wcpe.taboolib.ioc.gradle.plugin`：Gradle Plugin Marker 发布件，用于 `plugins { id("top.wcpe.taboolib.ioc") version "..." }`。
- `sourcesJar`：源码包。

这些产物由 `java-gradle-plugin` 与 `maven-publish` 自动装配，POM 元数据在根构建脚本中统一配置。

## 本地发布

执行：

```powershell
.\gradlew.bat publishToMavenLocal
```

验证建议：

```powershell
.\gradlew.bat printPublishTargets
.\gradlew.bat -p example :consumer:build
```

如果要让其他本地工程通过插件 ID 解析当前版本，可确保其 `pluginManagement.repositories` 包含 `mavenLocal()`。

## 发布到远端 Maven 仓库

支持两种配置方式，二选一即可：

1. Gradle 属性

```properties
publish.repo.url=https://your.repo/repository/maven-releases/
publish.repo.username=your-user
publish.repo.password=your-password
```

2. 环境变量

```text
MAVEN_PUBLISH_URL
MAVEN_PUBLISH_USERNAME
MAVEN_PUBLISH_PASSWORD
```

执行：

```powershell
.\gradlew.bat publish
```

说明：

- 未配置远端仓库时，`publish` 仍会包含本地 Maven 发布目标。
- 如果仓库地址是 `http://`，构建脚本会自动打开 `isAllowInsecureProtocol`，但正式环境仍建议使用 `https://`。

## 发布到 Gradle Plugin Portal

需要提供以下凭据之一：

- `gradle.publish.key` / `gradle.publish.secret`
- `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET`

执行：

```powershell
.\gradlew.bat publishPlugins
```

## 发布前检查

建议至少执行以下命令：

```powershell
.\gradlew.bat clean build
.\gradlew.bat test jacocoTestReport
.\gradlew.bat -p example :consumer:build
.\gradlew.bat printPublishTargets
```

如果 `clean build` 因 `jacocoTestCoverageVerification` 失败而中断，可先打开 `build/reports/jacoco/test/html/index.html` 查看未覆盖类与分支。当前默认门槛为：行覆盖率不低于 75%，分支覆盖率不低于 55%。

## 仓库内联调说明

- 当前仓库内置 `example` 工程，可直接验证插件 ID、自动依赖接管、显式任务依赖和 relocate 产物路径。
- 若要联调本地 `ioc-lib`，保持 `example/settings.gradle` 中的 `includeBuild('..')` 与 `consumer` 的 `useLocalProject(':ioc-lib')` 配置即可。
- 发布配置不会影响仓库内联调；example 仍优先通过包含构建引用当前工作区插件。