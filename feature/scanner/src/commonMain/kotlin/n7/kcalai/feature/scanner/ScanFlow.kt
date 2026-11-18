package n7.kcalai.feature.scanner

/*
 * Что окно съёмки показывает и куда отдаёт код — общим кодом, без Compose:
 * окно одно на обе платформы, и проверяется это тестами, а не глазами на двух телефонах.
 */

/**
 * Что стоит внизу окна.
 *
 * Карточка — когда она есть. Иначе ввод цифр: по кнопке или когда камеры нет.
 * Отказ в камере — не тупик, а вторая дверь, но пока человек не ответил на запрос,
 * показывать «доступа нет» рано.
 */
internal fun scanBottom(card: ScanState?, typingCode: Boolean, answered: Boolean, granted: Boolean, searching: Boolean): Bottom =
    when {
        card != null -> Bottom.Card(card)
        typingCode || (answered && !granted) -> Bottom.Code(searching)
        else -> Bottom.Hint(searching)
    }

/** Подсказка над «Нет штрих-кода». */
internal fun scanHint(searching: Boolean, answered: Boolean): String = when {
    searching -> "Ищу товар…"
    !answered -> "Запрашиваем доступ к камере…"
    else -> "Наведите на штрих-код"
}

/**
 * Куда идёт код.
 *
 * На шаге кода он уходит наружу один раз: камера читает его десятки раз подряд,
 * а набранный руками и пойманный камерой в ту же секунду — это всё ещё один товар.
 * Пока товар ищется, код с камеры никуда не идёт. В карточке он встаёт в её поле.
 *
 * Зовётся только с главного потока — так камера обеих платформ отдаёт коды.
 */
internal class BarcodeRouter(private val onScanned: (String) -> Unit) {

    private var delivered = false

    /** Код набран руками. */
    fun submit(code: String) {
        if (delivered) return
        delivered = true
        onScanned(code)
    }

    /** Код пойман камерой. */
    fun route(step: ScanStep, card: ScanState?, code: String) {
        when (step) {
            ScanStep.Barcode -> submit(code)
            is ScanStep.Searching -> Unit
            is ScanStep.NewProduct -> card?.onBarcode(code)
        }
    }
}
