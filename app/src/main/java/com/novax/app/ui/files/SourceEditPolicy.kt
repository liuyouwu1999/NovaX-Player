package com.novax.app.ui.files

// 2026-10-09 批次二评审（T6）：https 一并纳入同连口径——测连在途切 HTTPS 开关（8096↔443
// 整站换端口/换加密），旧结果不得被接受
internal fun sameWizardConnection(tested: NvWizardUiState, current: NvWizardUiState): Boolean =
    current.open && tested.sessionId == current.sessionId &&
        tested.protocol == current.protocol && tested.host == current.host &&
        tested.port == current.port && tested.https == current.https &&
        tested.domain == current.domain &&
        tested.user == current.user && tested.pass == current.pass &&
        tested.anonymous == current.anonymous && tested.shareOrPath == current.shareOrPath
