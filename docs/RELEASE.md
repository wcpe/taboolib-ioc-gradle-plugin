# 发布指南

本文档说明如何发布 `top.wcpe.taboolib.ioc` Gradle 插件。

## 前置条件

- Java 17+
- Gradle 8.x
- 远端 Maven 仓库凭据（可选）
- Plugin Portal 密钥（可选）

## 发布步骤

### 1. 更新版本号

修改 `gradle.properties` 中的 `version`：

```properties
version=0.0.7
```

同步更新以下文件中的版本号：

- `example/gradle.properties`
- `example/groovy-consumer/build.gradle`
- `example/kotlin-consumer/build.gradle.kts`
- `example/settings.gradle.kts`
- `README.md` 中的版本引用

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

通过 Gradle 属性或环境变量配置凭据：

```bash
./gradlew publish \
  -Ppublish.repo.url=https://maven.wcpe.top/repository/maven-snapshots/ \
  -Pusername=<your-username> \
  -Ppassword=<your-password>
```

或通过环境变量：

```bash
export MAVEN_PUBLISH_URL=https://maven.wcpe.top/repository/maven-snapshots/
export MAVEN_PUBLISH_USERNAME=<your-username>
export MAVEN_PUBLISH_PASSWORD=<your-password>
./gradlew publish
```

### 5. 发布到 Gradle Plugin Portal

需要先获取 Plugin Portal 密钥：

```bash
./gradlew publishPlugins \
  -Pgradle.publish.key=<your-key> \
  -Pgradle.publish.secret=<your-secret>
```

## 版本号策略

- 开发阶段使用 `-SNAPSHOT` 后缀（如 `0.0.7-SNAPSHOT`）。
- 正式发布时移除 `-SNAPSHOT` 后缀。
- 建议插件版本与默认 `iocVersion` 保持同步。

## 发布后验证

1. 在 `~/.m2/repository/top/wcpe/taboolib/ioc/` 下确认产物存在。
2. 使用示例工程验证插件功能：

```bash
.\gradlew.bat -p example :ioc-lib:publishToMavenLocal -Pexample.modules=ioc-lib "-Dmaven.repo.local=$(Join-Path (Resolve-Path 'example').Path '.m2-local')"
.\gradlew.bat -p example :groovy-consumer:build -Pexample.modules=groovy-consumer "-Dmaven.repo.local=$(Join-Path (Resolve-Path 'example').Path '.m2-local')" --refresh-dependencies
```

3. 检查产物 jar 中的 relocate 路径是否正确。
