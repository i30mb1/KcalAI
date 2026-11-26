package n7.kcalai.webapi

/**
 * На странице прогулки главный — мир: дневник поверх него виден, пока им пользуются. Пользуются — курсор на дневнике
 * и им недавно двигали, или печатают. Мира нет — прятаться не от чего.
 *
 * @param overPanels курсор над колонкой дневника или панелью шагов
 * @param sinceMoveMs сколько не двигали мышь
 * @param sinceTypingMs сколько не нажимали клавиш
 */
fun diaryShown(world: Boolean, overPanels: Boolean, sinceMoveMs: Long, sinceTypingMs: Long): Boolean =
    !world || sinceTypingMs < TYPING_KEEPS_MS || (overPanels && sinceMoveMs < POINTER_KEEPS_MS)

private const val POINTER_KEEPS_MS = 6_000L
private const val TYPING_KEEPS_MS = 10_000L
