package n7.kcalai.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildProbeTest {
    @Test
    fun probe_compiles_and_runs() {
        assertEquals("ok", BuildProbe.OK)
    }
}
