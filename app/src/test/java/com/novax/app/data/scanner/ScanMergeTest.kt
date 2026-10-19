package com.novax.app.data.scanner

import com.novax.app.data.db.MediaItemEntity
import com.novax.app.data.db.MediaKind
import com.novax.app.data.db.ThumbState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 批量 upsert 合并规则：扫描只负责"发现"，绝不覆盖抽帧/刮削成果与入库时间（C12 §二.3） */
class ScanMergeTest {

    private fun fresh(
        path: String = "/media/a.mkv",
        mtime: Long = 200L,
        kind: MediaKind = MediaKind.VIDEO,
    ) = MediaItemEntity(
        sourceId = 1L,
        path = path,
        name = "a",
        nameLower = "a",
        sizeBytes = 999L,
        mtimeMs = mtime,
        kind = kind,
        addedAt = 777L,   // 新行的 addedAt 恒被旧行覆盖
    )

    private fun old() = MediaItemEntity(
        id = 42L,
        sourceId = 1L,
        path = "/media/a.mkv",
        name = "a",
        nameLower = "a",
        sizeBytes = 999L,
        mtimeMs = 200L,
        kind = MediaKind.VIDEO,
        thumbnailPath = "/cache/thumbs/abc.jpg",
        thumbState = ThumbState.DONE.name,
        durationMs = 7_200_000L,
        width = 1920,
        height = 1080,
        scrapedTitle = "旧刮削标题",
        addedAt = 55L,
    )

    @Test
    fun noOldRow_freshWins() {
        val f = fresh()
        assertSame(f, ScanMerge.merge(null, f))
    }

    @Test
    fun merge_protectsIdAddedAtThumbAndScraped() {
        val merged = ScanMerge.merge(old(), fresh())
        assertEquals(42L, merged.id)
        assertEquals(55L, merged.addedAt)                              // "最近添加"排序键不被重扫重置
        assertEquals("/cache/thumbs/abc.jpg", merged.thumbnailPath)    // 抽帧成果不覆盖
        assertEquals(ThumbState.DONE.name, merged.thumbState)
        assertEquals("旧刮削标题", merged.scrapedTitle)
    }

    @Test
    fun merge_oldMetadataWins_missingStaysNull() {
        val merged = ScanMerge.merge(old(), fresh())
        assertEquals(7_200_000L, merged.durationMs)
        assertEquals(1920, merged.width)
        assertEquals(1080, merged.height)

        // 旧行没有元数据 → 保留 null（扫描期 fresh 恒不带 duration，等抽帧回填）
        val bare = old().copy(durationMs = null, width = null, height = null)
        val mergedBare = ScanMerge.merge(bare, fresh())
        assertNull(mergedBare.durationMs)
        assertNull(mergedBare.width)
        assertNull(mergedBare.height)
    }

    @Test
    fun merge_movieKindSurvivesRescan() {
        // 扫描期 fresh 恒 VIDEO；旧行已升级 MOVIE → 保留，防重扫集体降级且不再回升
        // （2026-09-15 修复落地：原断言锁的是「打回 VIDEO」的 bug 行为，与本注释矛盾）
        val merged = ScanMerge.merge(old().copy(kind = MediaKind.MOVIE), fresh(kind = MediaKind.VIDEO))
        assertEquals(MediaKind.MOVIE, merged.kind)
    }

    @Test
    fun merge_episodeRecomputedFromFresh() {
        val merged = ScanMerge.merge(old(), fresh(kind = MediaKind.EPISODE))
        assertEquals(MediaKind.EPISODE, merged.kind)
    }

    @Test
    fun merge_fileChangeDetectionStaysWithFresh() {
        // mtime/size 是变更检测键，来自本次扫描的真实值
        val merged = ScanMerge.merge(old(), fresh(mtime = 999L))
        assertEquals(999L, merged.mtimeMs)
        assertEquals(999L, merged.sizeBytes)
        assertNull(merged.thumbnailPath)
        assertEquals(ThumbState.NONE.name, merged.thumbState)
        assertNull(merged.durationMs)
    }

    // ---------- 图片行（kind=IMAGE）：thumbnailPath=文件路径，恒跟随 fresh 不进抽帧状态机 ----------

    private fun imageFresh(mtime: Long = 200L) = MediaItemEntity(
        sourceId = 1L, path = "/dcim/IMG_1.jpg", name = "IMG_1", nameLower = "img_1",
        sizeBytes = 100L, mtimeMs = mtime, kind = MediaKind.IMAGE,
        durationMs = 0L, thumbnailPath = "/dcim/IMG_1.jpg", thumbState = ThumbState.DONE.name,
        addedAt = 777L,
    )

    @Test
    fun merge_imageRow_keepsFilepathThumbAndDone_evenWhenChanged() {
        val oldImage = imageFresh().copy(id = 42L, addedAt = 55L)
        val merged = ScanMerge.merge(oldImage, imageFresh(mtime = 999L))   // size/mtime 变更
        assertEquals("/dcim/IMG_1.jpg", merged.thumbnailPath)
        assertEquals(ThumbState.DONE.name, merged.thumbState)
        assertEquals(0L, merged.durationMs)
        assertEquals(MediaKind.IMAGE, merged.kind)
        assertEquals(42L, merged.id)
        assertEquals(55L, merged.addedAt)
    }

    // ---------- 2026-09-15 kind 保底不降级（修复：upgradeKindToMovie 死链 + 重扫打回） ----------

    @Test
    fun merge_movieOld_resistsDowngradeOnRescan() {
        val o = old().copy(kind = MediaKind.MOVIE, durationMs = 7_200_000L)
        val merged = ScanMerge.merge(o, fresh(kind = MediaKind.VIDEO))
        assertEquals("已升级 MOVIE 重扫（fresh=VIDEO）不得打回", MediaKind.MOVIE, merged.kind)
    }

    @Test
    fun merge_episodeOld_keepsEpisodeKind() {
        val o = old().copy(kind = MediaKind.EPISODE)
        val merged = ScanMerge.merge(o, fresh(kind = MediaKind.VIDEO))
        // EPISODE 非升级结果，跟随扫描期 fresh 判定（评审规格：old=EPISODE+fresh=VIDEO → VIDEO）
        assertEquals(MediaKind.VIDEO, merged.kind)
    }

    @Test
    fun merge_imageFresh_stillFollowsFreshKind() {
        val o = old().copy(kind = MediaKind.MOVIE)
        val merged = ScanMerge.merge(o, fresh(kind = MediaKind.IMAGE))
        assertEquals("IMAGE 行恒跟随 fresh（图片不走抽帧状态机）", MediaKind.IMAGE, merged.kind)
    }

    @Test
    fun merge_videoOld_keepsVideoKind() {
        val merged = ScanMerge.merge(old(), fresh(kind = MediaKind.VIDEO))
        assertEquals(MediaKind.VIDEO, merged.kind)
    }
}
