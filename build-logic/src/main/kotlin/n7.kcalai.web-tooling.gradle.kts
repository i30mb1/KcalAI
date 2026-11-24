import org.jetbrains.kotlin.gradle.targets.js.EnvSpec

/*
 * Node.js, Yarn и Binaryen для сборки под Kotlin/Wasm — из репозиториев, объявленных
 * в settings. Без адреса загрузки плагин Kotlin не добавляет их в проект сам: иначе
 * сборка упала бы на запрете репозиториев в проектах.
 *
 * Часть инструментов ставится в корневой проект, часть — в сам модуль: настраиваются оба.
 */
listOf(project, rootProject).distinct().forEach { target ->
    target.plugins.configureEach {
        target.extensions.extensionsSchema
            .filter { EnvSpec::class.java.isAssignableFrom(it.publicType.concreteClass) }
            .forEach { (target.extensions.getByName(it.name) as EnvSpec<*>).downloadBaseUrl.set(null as String?) }
    }
}
