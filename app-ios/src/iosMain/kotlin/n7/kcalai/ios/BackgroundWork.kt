package n7.kcalai.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import n7.kcalai.fooddb.seedDirectory
import n7.kcalai.model.Log
import n7.kcalai.remote.updateSeed
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateByAddingTimeInterval

/**
 * Фоновая работа: персонажи и докачка справочника. Что делать — общий с Android код,
 * здесь только будильник BGTaskScheduler на месте WorkManager.
 *
 * Продукты фоном не уходят — как в сборке для людей на Android: продукт отправляется
 * с согласия, по предложению Яйца в ленте.
 *
 * iOS не обещает времени запуска — `earliestBeginDate` это просьба, а не срок:
 * система решает сама, по батарее, сети и тому, как часто человек открывает
 * приложение. Поэтому ни одна из этих работ не считается срочной.
 *
 * Обработчики обязаны быть зарегистрированы до возврата из `didFinishLaunching` —
 * иначе система падает с исключением. Отсюда [register] отдельно от [schedule].
 */
class BackgroundWork(private val container: AppContainer) {

    /** Живёт дольше задачи: отмену system task мы обрабатываем сами. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val scheduler = BGTaskScheduler.sharedScheduler

    /** Зовётся из `didFinishLaunching`, до его возврата. */
    fun register() {
        scheduler.registerForTaskWithIdentifier(TASK_SEED, usingQueue = null) { task ->
            task?.let { run(it) { updateSeed(Path(seedDirectory()), container.seedSource) } }
        }
        scheduler.registerForTaskWithIdentifier(TASK_MASCOTS, usingQueue = null) { task ->
            task?.let { run(it) { container.mascotRoutines.tick() } }
        }
    }

    /**
     * Попросить систему разбудить нас. Персонажам сервер не нужен — их будят всегда,
     * как MascotWorker на Android. Справочник без адреса сервера качать неоткуда.
     */
    fun schedule() {
        submit(
            BGAppRefreshTaskRequest(TASK_MASCOTS).apply {
                setEarliestBeginDate(NSDate().dateByAddingTimeInterval(MASCOTS_DELAY_SECONDS))
            },
        )
        if (!container.serverConfigured) return
        submit(
            BGAppRefreshTaskRequest(TASK_SEED).apply {
                setEarliestBeginDate(NSDate().dateByAddingTimeInterval(SEED_DELAY_SECONDS))
            },
        )
    }

    /**
     * Общая обёртка: система даёт считанные секунды и может оборвать работу,
     * поэтому у каждой задачи есть обработчик истечения, а итог сообщается
     * всегда — незавершённую задачу iOS считает зависшей и штрафует приложение.
     */
    private fun run(task: BGTask, work: suspend () -> Unit) {
        val job = scope.launch {
            try {
                work()
            } catch (error: Exception) {
                // Повтор не поможет: следующая попытка придёт со следующим пробуждением.
                Log.w(TAG, "фоновая работа ${task.identifier} не удалась", error)
            }
        }
        task.expirationHandler = { job.cancel() }
        job.invokeOnCompletion { task.setTaskCompletedWithSuccess(it == null) }
        // Следующее пробуждение просим сразу: одна задача — одно пробуждение.
        schedule()
    }

    /**
     * Опт-ин — из-за `NSError**` последним параметром: в Kotlin это указатель,
     * а указатели в cinterop ещё помечены экспериментальными.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun submit(request: BGTaskRequest) {
        // Бросает, когда идентификатора нет в Info.plist или задача уже стоит в очереди:
        // ни то ни другое не повод ронять приложение на старте.
        runCatching { scheduler.submitTaskRequest(request, null) }
            .onFailure { Log.w(TAG, "не удалось поставить ${request.identifier}: $it") }
    }

    private companion object {
        const val TAG = "BackgroundWork"
        const val TASK_SEED = "n7.kcalai.seed"
        const val TASK_MASCOTS = "n7.kcalai.mascots"

        /** Справочник не срочен: раньше чем через час спрашивать сервер незачем. */
        const val SEED_DELAY_SECONDS = 3600.0

        /** Как период MascotWorker на Android. iOS всё равно решает сама — это просьба, а не срок. */
        const val MASCOTS_DELAY_SECONDS = 30 * 60.0
    }
}
