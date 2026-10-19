package com.novax.app.ui.files

import org.junit.Assert.*
import org.junit.Test

class SourceEditPolicyTest {
    @Test fun oldConnectionResponseCannotApproveNewCredentials() {
        val tested = NvWizardUiState(open = true, host = "nas", pass = "old")
        assertTrue(sameWizardConnection(tested, tested.copy(connecting = true)))
        assertFalse(sameWizardConnection(tested, tested.copy(pass = "new")))
        assertFalse(sameWizardConnection(tested, tested.copy(host = "other")))
        assertFalse(sameWizardConnection(tested, tested.copy(open = false)))
        assertFalse(sameWizardConnection(tested, tested.copy(sessionId = tested.sessionId + 1)))
        // 2026-10-09 批次二评审（T6）：测连在途切 HTTPS 开关（8096↔443 端口/加密全变）——旧结果不得被接受
        assertFalse(sameWizardConnection(tested, tested.copy(https = true)))
    }
}
