# 发布指南

本文档说明如何发布 `top.wcpe.taboolib.ioc` Gradle 插件。

## 前置条件

- JDK 21+（插件本体仍以 Java 17 toolchain 编译；第 2 步的 `./gradlew test` 会连带跑
  `plugin-integration-tests`，其 fixture 依赖的 `mc-testkit` 模块元数据要求 JVM 21+，
  只用 17 会在配置期报 `Dependency requires at least JVM runtime version 21`）
- Gradle 8.x
- 远端 Maven 仓库凭据（可选）
- Plugin Portal 密钥（可选）

## 发布步骤

### 1. 更新版本号

修改 `gradle.properties` 中的 `version`：

```properties
version=0.0.11
```

版本号同时决定远端发布目标：**含 `-SNAPSHOT`** 走
`https://maven.wcpe.top/repository/maven-snapshots/`，**不含** 走
`https://maven.wcpe.top/repository/maven-releases/`。两个地址都是 `build.gradle.kts` 中的硬编码常量，
不接受属性覆盖。

同步更新：

- `README.md` 中的版本引用（「插件 ID」与「最小接入」两节的 `plugins { id ... version ... }` 片段；
  顶部 Maven 徽章走 shields 的 `maven-metadata` 动态读取，无需手工同步）
- `example/settings.gradle.kts` 中 `plugins { id("top.wcpe.taboolib.ioc") version "..." }` 的版本

> **不需要**改 `example/gradle.properties` 的 `version`，也**不需要**改
> `example/groovy-consumer/build.gradle` / `example/kotlin-consumer/build.gradle.kts`
> 中的示例版本：那里的 `version=0.0.6` 是示例工程自身的产物版本，与插件版本无关。

### 2. 运行测试

```bash
./gradlew test
```

确保所有测试通过。

### 3. 发布到 mavenLocal

```bash
./gradlew publishToMavenLocal
```

发布后可在 `~/.m2/repository/top/wcpe/taboolib/ioc/` 下找到产物。

### 4. 发布到远端 Maven 仓库

远端仓库地址由版本号决定（见第 1 步），**没有** `publish.repo.url` 这类开关；
只提供凭据即可（属性名依次回退，前者优先）：

| 首选（Gradle 属性） | 兼容回退名 |
| --- | --- |
| `WCPE_MAVEN_USERNAME` | `username` |
| `WCPE_MAVEN_PASSWORD` | `password` |

```bash
./gradlew publish \
  -PWCPE_MAVEN_USERNAME=<your-username> \
  -PWCPE_MAVEN_PASSWORD=<your-password>
```

CI 场景用环境变量时，**必须加 `ORG_GRADLE_PROJECT_` 前缀** —— 凭据是用 `findProperty` 读的，
只认 Gradle 属性，裸环境变量它看不到（照写裸名会静默回退成空串、以匿名凭据发布）：

```bash
export ORG_GRADLE_PROJECT_WCPE_MAVEN_USERNAME=<your-username>
export ORG_GRADLE_PROJECT_WCPE_MAVEN_PASSWORD=<your-password>
./gradlew publish
```

> `WCPE_MAVEN_*` 优先于回退名，两者同时设置时以 `WCPE_MAVEN_*` 为准。
> 任务 `printPublishTargets` 打印的 `[publish] remote = ...` 只回显 `publish.repo.url` /
> `MAVEN_PUBLISH_URL` 属性值，**不参与发布配置**，不要用它判断仓库是否配置成功。

### 5. 发布到 Gradle Plugin Portal

> 当前版本尚未上架 Plugin Portal（`plugins.gradle.org/m2/` 下没有对应 metadata，
> shields 端点为 not found）。本节命令仅在完成首次上架后才有意义；
> 在此之前消费者请使用自建 Maven 坐标 `top.wcpe.taboolib.ioc:taboolib-ioc:<版本>`。

需要先获取 Plugin Portal 密钥：

```bash
./gradlew publishPlugins \
  -Pgradle.publish.key=<your-key> \
  -Pgradle.publish.secret=<your-secret>
```

## 版本号策略

- 开发阶段使用 `-SNAPSHOT` 后缀（如 `0.0.11-SNAPSHOT`）。
- 正式发布时移除 `-SNAPSHOT` 后缀。
- 建议插件版本与默认 `iocVersion` 保持同步。

## 发布后验证

1. 在 `~/.m2/repository/top/wcpe/taboolib/ioc/` 下确认产物存在。
2. 使用示例工程验证插件功能。

`example/settings.gradle.kts` 无条件加载 `groovy-consumer` 与 `kotlin-consumer` 两个模块，
**不存在**按需加载示例模块的 Gradle 属性开关（仓库中没有任何构建脚本读取这类属性），
用任务路径前缀选择模块即可。

示例工程默认从 wcpe 的 `maven-public` 解析 `taboolib-ioc` 快照坐标，**不需要**先本地发布；
只有在离线、或要改版本验证时，才按下面注释的方式自行 `publishToMavenLocal` 到隔离的本地仓库。注意：`example/` 下**已不再包含** IoC 本体模块
（该模块已在历史提交 10ba334 中被删除），不要在 `example/` 下执行发布任务，
请到 IoC 本体仓库根目录执行：

```powershell
$exampleLocalRepo = Join-Path (Resolve-Path 'example').Path '.m2-local'
# 先在 IoC 本体仓库（taboolib-ioc）根目录执行，把坐标发布到同一个隔离仓库：
# .\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=$exampleLocalRepo"
.\gradlew.bat -p example :groovy-consumer:build "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :kotlin-consumer:build "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :groovy-consumer:analyzeTaboolibIocBeans "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
.\gradlew.bat -p example :kotlin-consumer:analyzeTaboolibIocBeans "-Dmaven.repo.local=$exampleLocalRepo" --refresh-dependencies
```

3. 检查产物 jar 中的 relocate 路径是否正确：两个示例的重定位结果相同，
   条目为 `top/wcpe/mc/plugin/taboolib/ioc/example/ioc/annotation/Component.class`，
   且不应再保留原始的 `top/wcpe/taboolib/ioc/...`。
