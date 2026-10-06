package top.wcpe.taboolib.ioc.gradle

import java.lang.reflect.InvocationTargetException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

internal object ReflectionSupport {

    /**
     * 宽松读取属性：getter 调用失败（`IllegalAccessException` / getter 内部抛出的
     * `InvocationTargetException`）、字段回退时 `setAccessible` 被受限运行时（Java 模块系统 /
     * 安全管理器）拒绝等异常一律吞掉并返回 `null`。
     *
     * 为什么必须包异常：本方法的调用方普遍写成 `readProperty(...) as? Boolean ?: false`、
     * `readProperty(...) as? String`，这类判空只防「读不到」，不防「读的时候抛」；
     * 一旦 getter 吊起异常就会越过调用方的兜底语义直接冒泡成构建失败。
     * 返回 `null` 与既有的「找不到 getter / 字段」语义一致，即「读不到该属性」。
     */
    fun readProperty(instance: Any, name: String): Any? = runCatching {
        val capitalized = name.replaceFirstChar { it.uppercase() }
        val getter = instance.javaClass.methods.firstOrNull {
            (it.name == "get$capitalized" || it.name == "is$capitalized") && it.parameterCount == 0
        }
        if (getter != null) {
            return@runCatching getter.invoke(instance)
        }

        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.get(instance)
    }.getOrNull()

    /**
     * 统一读取 [ProjectDependency] 指向的工程路径。
     *
     * 两个版本族提供的 API 互斥（Gradle 8.9 的 `ProjectDependency` 只有 `getDependencyProject()`，
     * 9.x 已移除它、只保留 `getPath()`），而本插件按 wrapper（8.14.4）的 `gradleApi()` 编译：
     * 任何一处**编译期直连**都会在另一版本上于运行期抛 `NoSuchMethodError`。
     * 因此路径读取必须全部走这里，且顺序为先 `path` 再回退 `dependencyProject`。
     */
    fun projectDependencyPath(dependency: ProjectDependency): String? {
        val path = readProperty(dependency, "path") as? String
        return path ?: (readProperty(dependency, "dependencyProject") as? Project)?.path
    }

    fun invokeMethod(instance: Any, name: String, vararg args: Any) {
        val method = instance.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == args.size && parametersMatch(it.parameterTypes, args)
        } ?: throw TaboolibIocConfigurationException(
            "TabooLib 扩展不包含可调用的方法 '$name(${args.joinToString { it::class.java.simpleName }})'。"
        )

        try {
            method.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            // 反射会把目标异常包成 InvocationTargetException，而它的 message **恒为 null**：
            // 直接放它出去，用户只会看到一个没有原因的异常名，得自己去翻 cause 链。
            // 这里展开成带 cause 的配置异常，保留原始栈。
            val target = e.targetException
            throw TaboolibIocConfigurationException(
                "调用 TabooLib 扩展方法 '$name' 失败：${target.message ?: target::class.java.name}",
                target,
            )
        }
    }

    private fun parametersMatch(parameterTypes: Array<Class<*>>, args: Array<out Any>): Boolean {
        return parameterTypes.zip(args).all { (parameterType, argument) ->
            parameterType.isAssignableFrom(argument.javaClass)
        }
    }
}