package com.novax.app.data.scrape

import android.content.Context
import com.novax.app.R
import com.novax.app.data.db.MediaDb
import com.novax.app.data.db.MediaItemDao
import com.novax.app.data.db.MediaItemEntity
import com.novax.app.player.KEY_TMDB_API_KEY
import com.novax.app.player.playerPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDB 刮削器（App 无 DI，companion 进程级单例）。
 *
 * 触发（双闸在位，三重闸在 run 内复核）：
 * - 手动：设置-媒体库「立即刮削」（对全部 scrapeEnabled 源）；
 * - 自动：MainActivity collector 观察 NvScannerHolder.progress 某源 RUNNING→DONE 时触发
 *   （全局开 && source.scrapeEnabled && Key 非空，三重闸）。
 *
 * 流程：电影/单视频逐条 /search/movie；EPISODE 按父目录分组 → /search/tv 整组回写。
 * 墓碑语义：检索无命中/无海报 → posterPath=""（不再重试）；下载失败 → 不写（下次重试）。
 * HTTP 401/403 抛 NvTmdbAuthException 由 run() 兜底捕获、整批中止并在进度行提示（E2-1）。
 * 限速 250ms/请求；新扫描开始时由触发方 cancelSource（文件可能已变动）。
 */
class NvScraper private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = NvTmdbClient()
    private val jobs = ConcurrentHashMap<Long, Job>()

    /** 已在刮则返回 false；调用方已通过 source.scrapeEnabled 过滤，Key 缺失在 run 内如实上报 */
    fun scrapeSource(sourceId: Long): Boolean {
        val job = scope.launch { run(sourceId) }
        // putIfAbsent 原子占位（BUG-C-R5-04：先 containsKey 再 put 的两步会让同源双跑）；
        // 完成回调按 (key, job) 条件移除，避免先完成者删掉后启动任务的映射、cancelSource 失效。
        val prev = jobs.putIfAbsent(sourceId, job)
        if (prev != null) {
            job.cancel()
            return false
        }
        job.invokeOnCompletion { jobs.remove(sourceId, job) }
        return true
    }

    fun scrapeAll(sourceIds: List<Long>) = sourceIds.forEach { scrapeSource(it) }

    fun cancelSource(sourceId: Long) {
        jobs.remove(sourceId)?.cancel()
        NvScrapeHolder.clear(sourceId)
    }

    fun cancelAll() = jobs.keys.toList().forEach { cancelSource(it) }

    fun isRunning(sourceId: Long): Boolean = jobs.containsKey(sourceId)

    private suspend fun run(sourceId: Long) {
        try {
            runInner(sourceId)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: NvTmdbAuthException) {
            // i18n Task 9：Key 无效单独给本地化整句（原为中文异常原文拼进进度行）；
            // HTTP 码细节仍留在异常链/logcat 取证
            NvScrapeHolder.publish(
                NvScrapeProgress(sourceId, 0, 0, running = false, message = appContext.getString(R.string.data_scrape_key_invalid)),
            )
        } catch (e: Exception) {
            // 兜底终态：DAO/网络异常也要落 running=false，详情页 rescrape 的等待不能死等（审核存疑项加固）；
            // i18n Task 9：前缀资源化，e.message 为底层诊断原文（异常链白名单，作 detail 透传）
            NvScrapeHolder.publish(
                NvScrapeProgress(
                    sourceId, 0, 0, running = false,
                    message = appContext.getString(R.string.data_scrape_failed_detail, e.message ?: e.javaClass.simpleName),
                ),
            )
        }
    }

    private suspend fun runInner(sourceId: Long) {
        val apiKey = readApiKey()
        if (apiKey.isEmpty()) {
            NvScrapeHolder.publish(
                NvScrapeProgress(sourceId, 0, 0, running = false, message = appContext.getString(R.string.data_scrape_no_api_key)),
            )
            return
        }
        val dao = MediaDb.get(appContext).mediaItemDao()
        val movies = dao.scrapePendingMovies(sourceId)
        val episodeRows = dao.scrapePendingEpisodes(sourceId)
        val groups = episodeRows
            .groupBy { it.path.substringBeforeLast('/', "") }
            .filterKeys { it.isNotBlank() }
        // 根目录的剧集行没有父目录可分组，原实现会整批漏刮（BUG-C-R5-12）：按文件名单独走 /search/tv
        val rootEpisodes = episodeRows.filter { it.path.substringBeforeLast('/', "").isBlank() }
        val total = movies.size + groups.size + rootEpisodes.size
        if (total == 0) {
            NvScrapeHolder.publish(
                NvScrapeProgress(sourceId, 0, 0, running = false, message = appContext.getString(R.string.data_scrape_nothing_new)),
            )
            return
        }
        var done = 0
        publish(sourceId, done, total)
        for (m in movies) {
            currentCoroutineContext().ensureActive()
            scrapeMovie(sourceId, dao, apiKey, m)
            publish(sourceId, ++done, total)
            delay(RATE_LIMIT_MS)
        }
        for ((dir, rows) in groups) {
            currentCoroutineContext().ensureActive()
            scrapeSeries(sourceId, dao, apiKey, dir.substringAfterLast('/'), rows)
            publish(sourceId, ++done, total)
            delay(RATE_LIMIT_MS)
        }
        for (row in rootEpisodes) {
            currentCoroutineContext().ensureActive()
            scrapeSeries(sourceId, dao, apiKey, NvTmdbNameCleaner.clean(row.name), listOf(row))
            publish(sourceId, ++done, total)
            delay(RATE_LIMIT_MS)
        }
        NvScrapeHolder.publish(NvScrapeProgress(sourceId, total, total, running = false))
    }

    private suspend fun scrapeMovie(sourceId: Long, dao: MediaItemDao, apiKey: String, item: MediaItemEntity) {
        val hit = try {
            client.searchMovie(apiKey, NvTmdbNameCleaner.clean(item.name), NvTmdbNameCleaner.extractYear(item.name))
        } catch (_: NvTmdbUnavailableException) {
            // 网络失败 ≠ 无命中：跳过本条、不写墓碑，保持 pending 下次重试（BUG-C-R5-02）
            return
        }
        if (hit == null) {
            // 无命中：title=null + poster=""（双墓碑，不再重试；消费方 blank/null 都回退文件名）
            dao.updateScrape(item.id, null, null, "", null, null)
            return
        }
        // 下载失败保持 poster=null（可重试）；TMDB 本身无海报才写 ""（永久墓碑）——审核 P1-6
        val poster = if (hit.posterPath == null) {
            ""
        } else {
            client.downloadTo(hit.posterPath, posterFile(sourceId, item.path))
        }
        dao.updateScrape(
            item.id,
            hit.title.takeIf { it.isNotBlank() },
            hit.overview,
            poster,
            hit.year,
            hit.voteAverage.takeIf { it > 0.0 },
        )
    }

    private suspend fun scrapeSeries(
        sourceId: Long,
        dao: MediaItemDao,
        apiKey: String,
        dirName: String,
        rows: List<MediaItemEntity>,
    ) {
        val hit = try {
            client.searchTv(apiKey, NvTmdbNameCleaner.clean(dirName), NvTmdbNameCleaner.extractYear(dirName))
        } catch (_: NvTmdbUnavailableException) {
            // 网络失败 ≠ 无命中：跳过本组、不写墓碑（BUG-C-R5-02）
            return
        }
        if (hit == null) {
            dao.updateScrapeByPaths(sourceId, rows.map { it.path }, null, null, "", null, null)
            return
        }
        val poster = if (hit.posterPath == null) {
            ""
        } else {
            client.downloadTo(hit.posterPath, posterFile(sourceId, rows.first().path))
        }
        rows.chunked(SQL_CHUNK).forEach { chunk ->
            dao.updateScrapeByPaths(
                sourceId,
                chunk.map { it.path },
                hit.title.takeIf { it.isNotBlank() },
                hit.overview,
                poster,
                hit.year,
                hit.voteAverage.takeIf { it > 0.0 },
            )
        }
    }

    private fun posterFile(sourceId: Long, path: String): File =
        File(File(appContext.cacheDir, "scrape_posters").apply { mkdirs() }, md5("$sourceId:$path") + ".jpg")

    private suspend fun readApiKey(): String =
        appContext.playerPrefsStore.data.first()[KEY_TMDB_API_KEY].orEmpty().trim()

    private fun publish(sourceId: Long, done: Int, total: Int) =
        NvScrapeHolder.publish(NvScrapeProgress(sourceId, done, total, running = true))

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val RATE_LIMIT_MS = 250L
        private const val SQL_CHUNK = 400

        @Volatile
        private var instance: NvScraper? = null

        fun get(context: Context): NvScraper =
            instance ?: synchronized(this) {
                instance ?: NvScraper(context.applicationContext).also { instance = it }
            }
    }
}
