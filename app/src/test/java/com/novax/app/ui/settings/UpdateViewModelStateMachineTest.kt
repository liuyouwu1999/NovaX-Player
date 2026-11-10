package com.novax.app.ui.settings

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.novax.app.data.update.KEY_UPDATE_DOWNLOAD_ID
import com.novax.app.data.update.KEY_UPDATE_PENDING_VERSION
import com.novax.app.data.update.NvRelease
import com.novax.app.data.update.NvReleaseAsset
import com.novax.app.data.update.UpdateCheckResult
import com.novax.app.data.update.UpdateDmState
import com.novax.app.data.update.UpdateDownloader
import com.novax.app.data.update.UpdateRepository
import com.novax.app.novaxUiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/**
 * 审计 E3（spec C-2）：更新器「下载→校验→安装」状态机测试——019/020 三次热修的震中。
 * fake UpdateDownloader（query 脚本序列）+ FakeHttp（真 UpdateRepository 解析/SUMS 双通道）
 * + 真 NvSha256 校验走真实临时文件。Robolectric 临时 filesDir 承 DataStore；
 * UnconfinedTestDispatcher 跳过轮询 delay；IO 线程竞态用 awaitState 轮询兜。
 *
 * fixture：四 ABI 全量资产（Robolectric 的 SUPPORTED_ABIS 视实现而定）+ 四 ABI SUMS 行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateViewModelStateMachineTest {

    private class FakeHttp(val bodies: Map<String, String>) :
        com.novax.app.data.update.ReleaseHttp {
        override fun get(url: String): String =
            bodies[url] ?: throw java.io.IOException("no fixture for $url")
    }

    private class FakeDownloader(
        private val script: ArrayDeque<UpdateDmState>,
    ) : UpdateDownloader(ApplicationProvider.getApplicationContext()) {
        val enqueued = mutableListOf<String>()
        override fun enqueue(asset: NvReleaseAsset, versionName: String): Long {
            enqueued.add(asset.name)
            return 42L
        }
        override fun query(id: Long): UpdateDmState? = script.removeFirstOrNull()
        override fun fileOf(state: UpdateDmState): File? = state.localPath?.let { File(it) }
        override fun cleanup(pendingVersion: String?, currentVersion: String, activeId: Long?) {}
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    private val sumsUrl = "https://example.com/SHA256SUMS.txt"

    private fun assetName(abi: String) = "NovaX-1.0.0.2-$abi.apk"

    private fun shaOf(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 组 release JSON：指定内容哈希 → 四 ABI 资产 + SHA256SUMS.txt 资产 */
    private fun releaseJsonWithSums(): String {
        val assets = abis.joinToString(",") { abi ->
            """{"name": "${assetName(abi)}", "browser_download_url": "https://example.com/$abi.apk"}"""
        } + "," + """{"name": "SHA256SUMS.txt", "browser_download_url": "$sumsUrl"}"""
        return """{"tag_name": "v1.0.0.2", "html_url": "https://example.com/tag/v1.0.0.2", """ +
            """"body": "更新说明", "assets": [$assets]}"""
    }

    /** SHA256SUMS 行格式 = sha 在前、文件名在后（NvSha256SumsParser 正则钉死） */
    private fun sumsBody(contentSha: String): String =
        abis.joinToString("\n") { abi -> "$contentSha  ${assetName(abi)}" }

    private fun vm(downloader: UpdateDownloader, contentSha: String, version: String = "1.0.0.1"): UpdateViewModel {
        val http = FakeHttp(
            mapOf(
                "https://api.github.com/repos/liuyouwu1999/NovaX-Player/releases/latest" to releaseJsonWithSums(),
                sumsUrl to sumsBody(contentSha),
            ),
        )
        return UpdateViewModel(app, UpdateRepository(http), downloader, version)
    }

    private fun tempFile(content: String): File =
        File(app.cacheDir, "upd_${content.hashCode()}.apk").apply { writeText(content) }

    private fun dmDone(f: File) = UpdateDmState(8, 4, 4, f.absolutePath)

    /** IO 线程竞态兜底：轮询等状态转移 */
    private fun awaitState(
        vm: UpdateViewModel,
        timeoutMs: Long = 10000,
        pred: (UpdateUiState) -> Boolean,
    ): UpdateUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var s = vm.state.value
        while (System.currentTimeMillis() < deadline) {
            s = vm.state.value
            if (pred(s)) return s
            Thread.sleep(20)
        }
        return s
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** 注入持久键（进程重启恢复现场） */
    private fun injectPersisted(id: Long, tag: String) {
        kotlinx.coroutines.runBlocking {
            app.novaxUiPrefs.edit {
                it[KEY_UPDATE_DOWNLOAD_ID] = id
                it[KEY_UPDATE_PENDING_VERSION] = tag
            }
        }
    }

    @Test
    fun `哈希不符_删文件清键_VerifyFailed`() {
        val f = tempFile("AAA")
        val wrongSha = shaOf("BBB")
        val dl = FakeDownloader(ArrayDeque(listOf(dmDone(f))))
        val vm = vm(dl, wrongSha)
        vm.manualCheck()
        awaitState(vm) { it is UpdateUiState.AvailableShow }
        vm.startDownload()
        val s = awaitState(vm) { it is UpdateUiState.VerifyFailed }
        assertTrue("终态=VerifyFailed，实际=$s", s is UpdateUiState.VerifyFailed)
        assertTrue("损坏文件已删", !f.exists())
        // 评审 Minor-1：清键断言（clearDownloadKeys 双键均清）
        val prefs = kotlinx.coroutines.runBlocking {
            app.novaxUiPrefs.data.first()
        }
        assertTrue("downloadId 键已清", prefs[KEY_UPDATE_DOWNLOAD_ID] == null)
        assertTrue("pendingVersion 键已清", prefs[KEY_UPDATE_PENDING_VERSION] == null)
    }

    @Test
    fun `哈希相符_待装族_文件保留`() {
        val f = tempFile("GOOD")
        val dl = FakeDownloader(ArrayDeque(listOf(dmDone(f))))
        val vm = vm(dl, shaOf("GOOD"))
        vm.manualCheck()
        awaitState(vm) { it is UpdateUiState.AvailableShow }
        vm.startDownload()
        val s = awaitState(vm) { it is UpdateUiState.ReadyToInstall || it is UpdateUiState.NeedPermission }
        assertTrue("终态=待装族，实际=$s", s is UpdateUiState.ReadyToInstall || s is UpdateUiState.NeedPermission)
        assertTrue("文件保留", f.exists())
    }

    @Test
    fun `SUMS缺失_canInstall假_下载守卫不发`() {
        val dl = FakeDownloader(ArrayDeque())
        // sumsUrl 无 fixture → SUMS 拉取失败 → sums=null → canInstall=false
        val http = FakeHttp(
            mapOf("https://api.github.com/repos/liuyouwu1999/NovaX-Player/releases/latest" to releaseJsonWithSums()),
        )
        val vm = UpdateViewModel(app, UpdateRepository(http), dl, "1.0.0.1")
        vm.manualCheck()
        val s = awaitState(vm) { it is UpdateUiState.AvailableShow }
        assertTrue("canInstall=false（SUMS 缺失）", (s as UpdateUiState.AvailableShow).check.canInstall.not())
        vm.startDownload()   // guard 在 launch 前同步 ?: return——确定性 no-op
        assertTrue("停在 AvailableShow 不动", vm.state.value is UpdateUiState.AvailableShow)
        assertFalse("enqueue 未发生", dl.enqueued.isNotEmpty())
    }

    @Test
    fun `恢复模式_同tag补查_走全链校验`() {
        val f = tempFile("RECOVER")
        val dl = FakeDownloader(ArrayDeque(listOf(dmDone(f))))
        val vm = vm(dl, shaOf("RECOVER"))
        injectPersisted(42L, "v1.0.0.2")
        vm.onAboutEnter()
        // 注意：Idle 是初始态——pred 必须排除 Idle（等待离开初始态再断言终态）
        val s = awaitState(vm, timeoutMs = 8000) {
            it is UpdateUiState.ReadyToInstall || it is UpdateUiState.NeedPermission || it is UpdateUiState.VerifyFailed
        }
        assertTrue("恢复终态=待装族，实际=$s", s is UpdateUiState.ReadyToInstall || s is UpdateUiState.NeedPermission)
        assertTrue("文件保留", f.exists())
    }

    @Test
    fun `恢复模式_异tag补查拒绝_回Idle`() {
        val f = tempFile("WRONGTAG")
        // 评审 Important-1：脚本必须出 SUCCESSFUL 才能真实触达 settle→fetchCheckFor 的
        // tag 失配拒绝分支（空脚本=查无记录路径，与异 tag 零因果=假覆盖）
        val dl = FakeDownloader(ArrayDeque(listOf(dmDone(f))))
        val vm = vm(dl, shaOf("X"))
        injectPersisted(43L, "v9.9.9")
        vm.onAboutEnter()
        // 异 tag：3 秒内不得进入待装族（Idle=初始态本身，等待超时即"从未推进"）
        val s = awaitState(vm, timeoutMs = 3000) {
            it is UpdateUiState.ReadyToInstall || it is UpdateUiState.NeedPermission
        }
        assertTrue("异 tag 不进入待装族，实际=$s", s is UpdateUiState.Idle)
        assertTrue("异 tag 文件按 pendingVersion 保护保留", f.exists())
    }
}
