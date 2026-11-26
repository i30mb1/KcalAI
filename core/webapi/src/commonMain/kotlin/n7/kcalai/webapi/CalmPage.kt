package n7.kcalai.webapi

/**
 * На странице прогулки главный — мир. Клик мимо дневника убирает его, следующий клик возвращает; клики по самому
 * дневнику — обычные. Мира нет — прятаться не от чего.
 *
 * @param shown виден ли дневник сейчас
 * @param onDiary клик пришёлся на колонку дневника или панель шагов
 * @return виден ли после клика
 */
fun diaryAfterClick(world: Boolean, shown: Boolean, onDiary: Boolean): Boolean = !world || !shown || onDiary
