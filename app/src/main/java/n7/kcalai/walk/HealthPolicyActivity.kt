package n7.kcalai.walk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Health Connect спрашивает, зачем приложению шаги: показываем политику конфиденциальности — так требует Google.
 * Своего экрана нет: политика открывается в браузере, а окно сразу закрывается.
 */
class HealthPolicyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        } catch (_: ActivityNotFoundException) {
            // Браузера нет — показывать нечем; Health Connect вернётся к своему экрану.
        }
        finish()
    }

    companion object {
        /** Та же политика, что на странице приложения в Google Play. */
        const val PRIVACY_POLICY_URL = "https://github.com/i30mb1/KcalAI/blob/main/docs/privacy-policy.md"
    }
}
