package n7.kcalai.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import n7.kcalai.fooddb.SeedInstaller
import n7.kcalai.fooddb.seedDirectory
import n7.kcalai.model.Log
import n7.kcalai.remote.uploadPendingContributions
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateByAddingTimeInterval

/**
 * Фоновая работа: персонажи, отправка вкладов и докачка справочника.
 *
 * iOS не обещает времени запуска — `earliestBeginDate` это просьба, а не срок:
 * система решает сама, по батарее, сети и тому, как часто человек открывает
 * приложение. Поэтому ни одна из этих работ не считается срочной: вклады ждут
 * в очереди, справочник встаёт при следующем запуске.
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
        scheduler.registerForTaskWithIdentifier(TASK_CONTRIBUTIONS, usingQueue = null) { task ->
            task?.let { run(it) { uploadContributions() } }
        }
        scheduler.registerForTaskWithIdentifier(TASK_SEED, usingQueue = null) { task ->
            task?.let { run(it) { updateSeed() } }
        }
        scheduler.registerForTaskWithIdentifier(TASK_MASCOTS, usingQueue = null) { task ->
            task?.let { run(it) { container.mascotRoutines.tick() } }
        }
    }

    /**
     * Попросить систему разбудить нас. Персонажам сервер не нужен — их будят всегда,
     * как MascotWorker на Android. Без адреса сервера остальным просить не о чем:
     * вклады копятся, справочник не докачивается.
     */
    fun schedule() {
        submit(
            BGAppRefreshTaskRequest(TASK_MASCOTS).apply {
                setEarliestBeginDate(NSDate().dateByAddingTimeInterval(MASCOTS_DELAY_SECONDS))
            },
        )
        if (!container.serverConfigured) return
        submit(BGProcessingTaskRequest(TASK_CONTRIBUTIONS).apply { setRequiresNetworkConnectivity(true) })
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

    private suspend fun uploadContributions() {
        uploadPendingContributions(container.database.contributionDao(), container.contributionUploader)
    }

    /**
     * Скачанное не встаёт на место сразу: `FoodDb` держит соединение с текущим
     * файлом. Файл ложится в `seed.db.next`, а продвигает его следующий запуск.
     */
    private suspend fun updateSeed() {
        val dir = Path(seedDirectory())
        val manifest = container.seedSource.seedManifest() ?: return
        if (manifest.sha256.equals(SeedInstaller.installedDigest(dir), ignoreCase = true) ||
            manifest.sha256.equals(SeedInstaller.stagedDigest(dir), ignoreCase = true)
        ) {
            return
        }

        val tmp = Path(dir, "seed.db.download")
        if (!container.seedSource.downloadSeed(manifest, tmp.toString())) {
            SystemFileSystem.delete(tmp, mustExist = false)
            return
        }
        val ready = SeedInstaller.stage(dir, tmp, manifest.sha256)
        Log.i(
            TAG,
            if (ready) "справочник v${manifest.version} докачан, встанет при следующем запуске"
            else "закачка справочника битая, выброшена",
        )
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
        const val TASK_CONTRIBUTIONS = "n7.kcalai.contributions"
        const val TASK_SEED = "n7.kcalai.seed"
        const val TASK_MASCOTS = "n7.kcalai.mascots"

        /** Как период MascotWorker на Android. iOS всё равно решает сама — это просьба, а не срок. */
        const val MASCOTS_DELAY_SECONDS = 30 * 60.0

        /** Справочник не срочен: раньше чем через час спрашивать сервер незачем. */
        const val SEED_DELAY_SECONDS = 3600.0
    }
}
