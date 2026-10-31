package n7.kcalai.database

import android.content.Context
import androidx.room.Room

fun buildKcalDatabase(context: Context): KcalDatabase =
    Room.databaseBuilder<KcalDatabase>(context, context.getDatabasePath("kcal.db").absolutePath)
        .configured()
