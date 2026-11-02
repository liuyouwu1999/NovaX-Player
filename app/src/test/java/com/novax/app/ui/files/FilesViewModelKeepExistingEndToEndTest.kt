package com.novax.app.ui.files

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.SourceEntity
import com.novax.app.data.db.SourceType
import com.novax.app.data.scanner.ScanRoots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 一期机构整改挂账 §10-11 销账（2026-11-02 二期，spec §5 D2 Robolectric 落地后补）：
 * VM 壳层端到端断言——「解密失败空框(passUnknown) → 免重测直通 → resolveSave=KeepExisting →
 * doSaveSource 原列回写 → DAO 落库」三环接线在真实 VM + 真实 Room 上成立。
 *
 * 一期只能用纯函数测试（JVM 构造 VM 绑死单例+Keystore 被证不可行，见 spec §2.5-10）；本测试
 * 是 SourceSavePolicyTest.resolveSave_keepExisting_usesOriginalPasswordColumn 的执行路径对偶。
 * password 列注入不可解密密文形态（nvenc1:AAAA:BBBB——Base64 合法但 GCM 校验必败，
 * Robolectric 上 AndroidKeyStore 本就不可用，decryptOrNull 返 null）→ passUnknown=true 走
 * 免重测；filesOnly 保存路径避开真实扫描（confirmAddSourceFilesOnly），落库完成信号 =
 * scanRoots==DISABLED（doSaveSource 对 filesOnly 的确定性副作用）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FilesViewModelKeepExistingEndToEndTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun edit_keepExisting_endToEnd_daoReceivesOriginalColumn() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val vm = FilesViewModel(app)
        val db = MediaDb.get(app)
        val dao = db.sourceDao()
        val originalColumn = "nvenc1:AAAA:BBBB"   // 不可解密密文形态（Robolectric 无 AndroidKeyStore）
        val id = dao.insert(
            SourceEntity(
                type = SourceType.WEBDAV,
                nickname = "keepme",
                host = "10.255.255.1",   // 内网保留段假地址（不可路由）——filesOnly 路径不真实测连/扫描；
                                         // 免重测分支的 loadWizardDir 会发起一次真实浏览尝试，失败只落
                                         // browse 错误态不清 testSuccess（既有依赖锚），vm.clear() 兜底取消
                port = 5005,
                username = "svc",
                password = originalColumn,
                shareOrPath = "/dav",
            ),
        )
        try {
            vm.editSource(id)
            // editSource 异步载入（Room 真线程），轮询而非 advanceUntilIdle（防真实线程竞态）
            withTimeout(10_000) { vm.wizard.first { it.open && it.passUnknown && it.original?.id == id } }
            vm.goNext()
            // 免重测直通：testSuccess=true 且 step=2（spec §2.2-2 直通分支，不发起真实连接）
            withTimeout(10_000) { vm.wizard.first { it.step == 2 && it.testSuccess } }
            vm.confirmAddSourceFilesOnly()
            // 落库完成信号：doSaveSource 对 filesOnly 的确定性副作用 scanRoots=DISABLED
            val deadline = System.currentTimeMillis() + 10_000
            var saved: SourceEntity? = null
            while (System.currentTimeMillis() < deadline) {
                saved = dao.getById(id)
                if (saved?.scanRoots == ScanRoots.DISABLED) break
                delay(50)
            }
            assertEquals("doSaveSource 未落库（scanRoots 未置 DISABLED）", ScanRoots.DISABLED, saved?.scanRoots)
            // 断言核心：原口令列逐字回写（解密失败绝不静默清空——机构 §2 验收标准的执行路径证明）
            assertEquals("KeepExisting 必须原列回写", originalColumn, saved!!.password)
            assertEquals("update 而非插入（id 不变）", "keepme", saved.nickname)
            assertEquals("仅一行（无重复插入）", 1, dao.getAll().size)
        } finally {
            vm.closeWizard()
            // 取消 viewModelScope（免重测分支遗留的 loadWizardDir 后台浏览任务）。
            // 落地备注：简报原文 vm.clear() 在 lifecycle 2.9 编译不过（ViewModel.clear 已收成
            // internal），viewModelScope.cancel() 是公开 API 下的等价物——clear() 对 scope 的
            // 实际效果就是取消，测试清理目标（杀后台浏览协程）完全一致。
            vm.viewModelScope.cancel()
        }
    }
}
