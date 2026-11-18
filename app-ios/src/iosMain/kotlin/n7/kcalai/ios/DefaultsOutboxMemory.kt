package n7.kcalai.ios

import n7.kcalai.feature.diary.OutboxCount
import n7.kcalai.feature.diary.OutboxMemory
import platform.Foundation.NSUserDefaults

/** «Не сейчас» помнится в NSUserDefaults — как в SharedPreferences на Android. */
class DefaultsOutboxMemory : OutboxMemory {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun declined(): OutboxCount? =
        if (defaults.objectForKey(DECLINED_PRODUCTS) != null) {
            OutboxCount(
                scans = defaults.integerForKey(DECLINED_SCANS).toInt(),
                products = defaults.integerForKey(DECLINED_PRODUCTS).toInt(),
            )
        } else {
            null
        }

    override suspend fun remember(declined: OutboxCount?) {
        if (declined == null) {
            defaults.removeObjectForKey(DECLINED_SCANS)
            defaults.removeObjectForKey(DECLINED_PRODUCTS)
        } else {
            defaults.setInteger(declined.scans.toLong(), forKey = DECLINED_SCANS)
            defaults.setInteger(declined.products.toLong(), forKey = DECLINED_PRODUCTS)
        }
    }

    private companion object {
        const val DECLINED_SCANS = "outbox.declined_scans"
        const val DECLINED_PRODUCTS = "outbox.declined_products"
    }
}
