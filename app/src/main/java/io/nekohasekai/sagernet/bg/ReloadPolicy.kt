package io.nekohasekai.sagernet.bg

enum class ReloadMode {
    FULL_RELOAD,
    SELECTOR_HOT_SWAP,
}

object ReloadPolicy {

    fun decide(
        forceFullReload: Boolean,
        runningSelectorGroupId: Long,
        candidateSelectorGroupId: Long,
    ): ReloadMode = if (
        !forceFullReload &&
        runningSelectorGroupId >= 0L &&
        runningSelectorGroupId == candidateSelectorGroupId
    ) {
        ReloadMode.SELECTOR_HOT_SWAP
    } else {
        ReloadMode.FULL_RELOAD
    }
}
