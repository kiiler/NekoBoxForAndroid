package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class ReloadPolicyTest {

    @Test
    fun forcedReloadRequiresFullRestartWithinSameSelectorGroup() {
        assertEquals(
            ReloadMode.FULL_RELOAD,
            ReloadPolicy.decide(
                forceFullReload = true,
                runningSelectorGroupId = 42L,
                candidateSelectorGroupId = 42L,
            ),
        )
    }

    @Test
    fun ordinaryProfileSelectionWithinSameGroupAllowsSelectorHotSwap() {
        assertEquals(
            ReloadMode.SELECTOR_HOT_SWAP,
            ReloadPolicy.decide(
                forceFullReload = false,
                runningSelectorGroupId = 42L,
                candidateSelectorGroupId = 42L,
            ),
        )
    }

    @Test
    fun ordinarySelectionAcrossGroupsRequiresFullReload() {
        assertEquals(
            ReloadMode.FULL_RELOAD,
            ReloadPolicy.decide(
                forceFullReload = false,
                runningSelectorGroupId = 42L,
                candidateSelectorGroupId = 43L,
            ),
        )
    }
}
