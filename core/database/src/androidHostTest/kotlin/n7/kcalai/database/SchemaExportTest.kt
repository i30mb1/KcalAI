package n7.kcalai.database

import java.io.File
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Схема каждой версии базы лежит в репозитории.
 *
 * По выгруженным схемам Room строит автомиграции и проверяет ручные: без схемы
 * прошлой версии миграцию к следующей не написать и не проверить. Подняли
 * версию — схема новой версии должна появиться в `schemas/` и попасть в git.
 */
class SchemaExportTest {

    @Test
    fun `схема текущей версии базы выгружена`() {
        val schema = File("schemas/n7.kcalai.database.KcalDatabase/$KCAL_DB_VERSION.json")

        assertTrue(schema.isFile, "нет ${schema.absolutePath}: собрать :core:database и закоммитить схему")
    }
}
