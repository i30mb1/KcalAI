package n7.kcalai

import android.app.Application

class KcalApp : Application() {

    /** Лёгкий на конструирование: тяжёлое внутри отложено до первого обращения. */
    val container: AppContainer by lazy { AppContainer(this) }
}
