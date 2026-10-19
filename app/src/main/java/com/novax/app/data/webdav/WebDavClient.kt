package com.novax.app.data.webdav

import com.novax.app.data.NvFileEntry
import com.novax.app.data.NvPaths
import com.novax.app.data.db.SourceEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端（OkHttp 实现）——与 [com.novax.app.data.smb.SmbClient] 对齐的 API。
 *
 * 连接复用（C8 §3.2）：OkHttp [ConnectionPool]（8 连接 / 5min keep-alive），
 * seek 只发新 Range 请求，同 host 连接复用，不重建会话。
 * 认证：预置 Basic（RFC7617）；401 返回 Digest 挑战时由 [DigestAuthInterceptor] 按用户
 * 凭据应答重发一次（RFC2617 MD5 / qop=auth，RFC2069 回退；MD5-sess 与 SHA-256 系
 * 明确不支持，命中抛中文友好 IOException——理由见 DigestAuth.isSupportedAlgorithm）。
 * 目录列举：PROPFIND Depth:1，XmlPullParser 解析 207 Multi-Status。
 */
class WebDavClient(
    httpClient: OkHttpClient? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val client: OkHttpClient = (httpClient ?: OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))  // C8 §3.2 keep-alive 池
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build())
        .newBuilder()
        // Digest 401 挑战应答（应用层拦截器，2026-09-29）：Basic 头仍先发（兼容绝大多数
        // NAS），仅当服务器以 Digest 挑战回应时介入；nonce/nc 缓存在拦截器实例内
        .addInterceptor(DigestAuthInterceptor())
        .build()

    /**
     * 验证源可连：对服务根发 Depth:0 PROPFIND。
     * 207/200 均视为可达（部分服务器对根返回 200）。
     */
    suspend fun connect(source: SourceEntity): Result<Unit> = withContext(io) {
        runCatching {
            // 路径必须传服务根 "/"：urlOf 内部会再拼 shareOrPath，传 shareOrPath 本身
            // 会变成 "/dav/dav/" 双重拼接（BUG-C-R5-01），带根路径的源永远连不上。
            propfind(source, "/", depth = 0).use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("WebDAV PROPFIND 失败: HTTP ${resp.code}")
                }
            }
        }.recoverCatching { e -> throw toIo(e, "connect ${source.nickname}") }
    }

    /** 列目录（[dirPath] 为统一路径表示） */
    suspend fun list(source: SourceEntity, dirPath: String): Result<List<NvFileEntry>> =
        withContext(io) {
            runCatching {
                val dir = NvPaths.asDirectory(dirPath)
                val requestPath = WebDavPaths.encodedPath(source.shareOrPath, dir, isDir = true)
                propfind(source, dir, depth = 1).use { resp ->
                    // 207 是标准答案；200 兼容（connect 同口径），但 200 体必须真是
                    // Multi-Status，否则非 DAV 端点会被静默当成空目录（BUG-C-R5-11）。
                    if (resp.code != 207 && resp.code != 200) {
                        throw IOException("WebDAV PROPFIND 失败: HTTP ${resp.code} $requestPath")
                    }
                    val bodyText = resp.body?.string() ?: throw IOException("WebDAV 空响应体")
                    if (resp.code == 200 && !bodyText.contains("multistatus", ignoreCase = true)) {
                        throw IOException("WebDAV PROPFIND 返回非 Multi-Status 响应: HTTP 200 $requestPath")
                    }
                    val entries = parseMultistatus(StringReader(bodyText))
                    entries
                        .filter { !WebDavPaths.hrefMatchesPath(it.href, requestPath) }
                        .map { e ->
                            val name = WebDavPaths.fileNameFromHref(e.href)
                            val isDir = e.isCollection || WebDavPaths.hrefIsCollection(e.href)
                            if (name.isEmpty()) null
                            else NvFileEntry(
                                name = name,
                                path = NvPaths.join(dir, name) + if (isDir) NvPaths.SEPARATOR else "",
                                isDir = isDir,
                                size = e.size,
                                lastModified = e.lastModified,
                            )
                        }
                        .filterNotNull()
                        .distinctBy { it.path }
                        .sortedWith(compareByDescending<NvFileEntry> { it.isDir }.thenBy { it.name.lowercase() })
                }
            }.recoverCatching { e -> throw toIo(e, "list ${source.nickname} $dirPath") }
        }

    /**
     * 打开从 [offset] 起的读流：GET + Range 头（offset=0 时不发 Range，兼容性最好）。
     * 服务器不支持 Range（返回 200 而非 206）时降级为顺序读并丢弃 [offset] 字节
     * （C8 §3.2 Accept-Ranges 降级策略；此类服务器极少）。
     *
     * @throws IOException 请求失败 / 416 范围不满足
     */
    suspend fun openRead(source: SourceEntity, path: String, offset: Long): InputStream =
        withContext(io) { openReadBlocking(source, path, offset) }

    /**
     * [openRead] 的阻塞版本：供 ExoPlayer 加载线程直接调用（非协程上下文），
     * 与 DefaultHttpDataSource 同模型；连接经 OkHttp keep-alive 池复用。
     */
    fun openReadBlocking(source: SourceEntity, path: String, offset: Long): InputStream {
        require(offset >= 0) { "offset < 0" }
        val builder = Request.Builder()
            .url(urlOf(source, path, isDir = false))
            .header("Accept-Encoding", "identity")  // Range 与透明 gzip 不兼容
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        builder.authOf(source)

        val resp = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw toIo(e, "openRead $path")
        }
        try {
            val body = resp.body ?: throw IOException("WebDAV 空响应体: $path")
            when {
                resp.code == 416 -> throw IOException("Range 不满足（offset=$offset 越界?）: $path")
                !resp.isSuccessful -> throw IOException("WebDAV GET 失败: HTTP ${resp.code} $path")
                offset > 0 && resp.code != 206 -> {
                    // 服务器忽略 Range：顺序流跳过 offset 字节（正确性优先）
                    skipFully(body.byteStream(), offset)
                }
            }
            return BodyReleasingInputStream(body.byteStream(), resp)
        } catch (e: Exception) {
            resp.close()
            throw e
        }
    }

    /** OkHttp 连接池自管；对齐 SmbClient API 显式清空（App 退出/源删除时） */
    fun closeAll() {
        client.connectionPool.evictAll()
    }

    // ---------- PROPFIND ----------

    private fun propfind(source: SourceEntity, path: String, depth: Int, isDir: Boolean = true): Response {
        val builder = Request.Builder()
            .url(urlOf(source, path, isDir = isDir))
            .header("Depth", depth.toString())
            .header("Accept", "application/xml, text/xml")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
        builder.authOf(source)
        return client.newCall(builder.build()).execute()
    }

    /**
     * 单文件大小（字节）；未知/失败返回 -1（C8 §4.3：起播一次取长度，供 NvDataSource.open
     * 返回剩余总长让 Extractor 建精确/降级 SeekMap——坏索引 MKV 降级 seek 的前置）。
     * PROPFIND Depth:0 直指文件（isDir=false，无尾斜杠）；失败静默 -1——长度缺失只
     * 降级 seek 质量，不阻断起播。阻塞模型同 [openReadBlocking]（加载线程直调）。
     */
    fun fileSizeBlocking(source: SourceEntity, path: String): Long = try {
        propfind(source, path, depth = 0, isDir = false).use { resp ->
            if (!resp.isSuccessful) -1L
            else parseMultistatus(StringReader(resp.body?.string().orEmpty()))
                .firstOrNull { !it.isCollection && it.size >= 0 }?.size ?: -1L
        }
    } catch (e: Exception) {
        -1L
    }

    private fun urlOf(source: SourceEntity, path: String, isDir: Boolean): okhttp3.HttpUrl {
        val hostField = source.host
        val scheme = WebDavPaths.schemeOf(hostField)
        val host = WebDavPaths.hostOf(hostField)
        if (host.isEmpty()) throw IOException("WebDAV 源未配置主机: ${source.nickname}")
        val port = if (source.port in 1..65535) source.port else WebDavPaths.defaultPort(scheme)
        return okhttp3.HttpUrl.Builder()
            .scheme(scheme)
            .host(host)
            .port(port)
            .encodedPath(WebDavPaths.encodedPath(source.shareOrPath, path, isDir))
            .build()
    }

    // ---------- Multi-Status XML 解析 ----------

    internal data class DavEntry(
        val href: String,
        val isCollection: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    // internal：单测防回归（8edc70b 命名空间前缀兼容修复）
    internal fun parseMultistatus(reader: Reader): List<DavEntry> {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }.newPullParser()
        parser.setInput(reader)

        val entries = mutableListOf<DavEntry>()
        var inResponse = false
        var inResourceType = false
        var href = ""
        var isCollection = false
        var size = -1L
        var lastModified = 0L
        var textTag: String? = null
        val text = StringBuilder()

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    // 命名空间前缀兼容（2026-09-17 真机测试修复）：isNamespaceAware=false 时
                    // parser.name 返回原始限定名（"d:response"/"D:propstat"/"response"），
                    // 直接 equals 会全不匹配 → 目录永远「为空」。统一剥前缀按本地名匹配。
                    val local = parser.name.substringAfterLast(':').lowercase()
                    when (local) {
                        "response" -> {
                            inResponse = true
                            href = ""; isCollection = false; size = -1L; lastModified = 0L
                        }
                        "responsedescription" -> Unit
                        "resourcetype" -> inResourceType = true
                        "collection" -> if (inResponse && inResourceType) isCollection = true
                        "href", "getcontentlength", "getlastmodified", "displayname" -> {
                            textTag = local
                            text.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (textTag != null) text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.substringAfterLast(':').lowercase()) {
                    "href" -> {
                        href = text.toString().trim(); textTag = null
                    }
                    "getcontentlength" -> {
                        size = text.toString().trim().toLongOrNull() ?: -1L; textTag = null
                    }
                    "getlastmodified" -> {
                        lastModified = WebDavPaths.parseDavDate(text.toString()); textTag = null
                    }
                    "displayname" -> textTag = null
                    "resourcetype" -> inResourceType = false
                    "response" -> {
                        if (inResponse && href.isNotEmpty()) {
                            entries.add(DavEntry(href, isCollection, size, lastModified))
                        }
                        inResponse = false
                    }
                    "multistatus" -> return entries
                }
                XmlPullParser.END_DOCUMENT -> return entries
            }
        }
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = stream.read(buf, 0, remaining.coerceAtMost(buf.size.toLong()).toInt())
            if (n <= 0) throw IOException("服务器不支持 Range 且流短于 offset=$count") // n==0 防自旋
            remaining -= n
        }
    }

    private fun toIo(e: Throwable, what: String): IOException = when (e) {
        is IOException -> e
        else -> IOException("$what: ${e.message}", e)
    }

    /**
     * SourceEntity → 认证挂载（空用户名不挂）：预置 Basic 头（RFC7617 现状保留），
     * 同时把解密后的凭据经 Request tag 交给 [DigestAuthInterceptor]——401 Digest 挑战时
     * 由它计算 Authorization 重发（2026-09-29 Digest 已实现，替代一期 TODO 占位）。
     */
    private fun Request.Builder.authOf(source: SourceEntity) {
        val user = source.username.trim()
        if (user.isEmpty()) return
        val pass = com.novax.app.data.NvSecrets.decrypt(source.password)
        header("Authorization", Credentials.basic(user, pass))
        tag(DigestAuthInterceptor.DigestCredentials::class.java, DigestAuthInterceptor.DigestCredentials(user, pass))
    }

    /** 关闭流时连带释放 OkHttp Response（连接归还池复用，C8 §3.2） */
    private class BodyReleasingInputStream(
        stream: InputStream,
        private val response: Response,
    ) : FilterInputStream(stream) {
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    companion object {
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
                "</d:prop></d:propfind>"
    }
}
