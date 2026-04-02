package top.wcpe.taboolib.ioc.gradle

internal object ReflectionSupport {

    fun readProperty(instance: Any, name: String): Any? {
        val capitalized = name.replaceFirstChar { it.uppercase() }
        val getter = instance.javaClass.methods.firstOrNull {
            (it.name == "get$capitalized" || it.name == "is$capitalized") && it.parameterCount == 0
        }
        if (getter != null) {
            return getter.invoke(instance)
        }

        val field = runCatching { instance.javaClass.getDeclaredField(name) }.getOrNull()
            ?: return null
        field.isAccessible = true
        return field.get(instance)
    }

    fun invokeMethod(instance: Any, name: String, vararg args: Any) {
        val method = instance.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == args.size && parametersMatch(it.parameterTypes, args)
        } ?: throw TaboolibIocConfigurationException(
            "TabooLib 扩展不包含可调用的方法 '$name(${args.joinToString { it::class.java.simpleName }})'。"
        )

        method.invoke(instance, *args)
    }

    private fun parametersMatch(parameterTypes: Array<Class<*>>, args: Array<out Any>): Boolean {
        return parameterTypes.zip(args).all { (parameterType, argument) ->
            parameterType.isAssignableFrom(argument.javaClass)
        }
    }
}