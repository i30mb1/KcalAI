package n7.kcalai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import n7.kcalai.feature.diary.DiaryRoute
import n7.kcalai.feature.diary.DiaryViewModel
import n7.kcalai.ui.KcalTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as KcalApp).container

        setContent {
            KcalTheme {
                DiaryRoute(
                    viewModel = viewModel(
                        factory = DiaryViewModel.Factory(
                            diary = container.diaryRepository,
                            resolver = container.textResolver,
                            personal = container.personalRepository,
                        )
                    )
                )
            }
        }
    }
}
