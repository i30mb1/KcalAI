package n7.kcalai.mascot.voice

import n7.kcalai.model.Mascot

/** Голоса троих и кто из них говорит на какую тему. */
object Voices {

    fun of(mascot: Mascot): Voice = when (mascot) {
        Mascot.EGG -> EggVoice
        Mascot.BUTTER -> ButterVoice
        Mascot.GRAIN -> GrainVoice
    }

    /**
     * Кто умеет говорить на тему. Выводится из самих голосов: тема без говорящего
     * или говорящий без темы ловятся тестом голосов, а не человеком.
     */
    val cast: Map<Topic, Set<Mascot>> by lazy {
        Topic.entries.associateWith { topic ->
            Mascot.entries.filterTo(LinkedHashSet()) { topic in of(it).topics }
        }
    }
}
