package com.novax.app.data.scanner

import com.novax.app.data.db.MediaItemDao

/**
 * media_items 拼音键存量回填（2026-09-29 建库写入打通）。
 *
 * 背景：搜索 DAO（MediaItemDao.search）的 pinyin/initials 匹配早已接线，但一期建库的存量行
 * 两列恒空串，拼音/首字母检索对老库不生效。新扫描路径已由 MediaScanner.itemOf 写键
 * （ScanMerge 重扫跟随 fresh，重扫一次也能自愈本源），但要求用户重扫过于侵入——本回填在
 * 应用侧零 NAS IO 地补齐存量行（pinyin4j 纯本地转换 + 按 id 只写两列）。
 *
 * 触发：SearchViewModel init（消费方首开时一次性，Dispatchers.IO；幂等——回填过的行不再命中
 * 候选查询，重复触发只剩候选查询的空扫开销）。
 *
 * 键格式跟随 DAO 查询约定（详见 [MediaNames]）：pinyin 全拼小写无分隔（`LIKE '%q%'`）、
 * initials 首字母大写（`LIKE 'Q%'` 前缀，仅查询 ≥2 ASCII 字母时参与匹配）。
 */
object PinyinBackfill {

    /** 单批候选上限：千级库一次过，批间让出协程线程 */
    private const val BATCH_SIZE = 500

    /**
     * 回填全部 pinyin 为空串的行，返回实际更新行数。
     * 守卫：某批全部是「退化名」（无 CJK 且无字母数字 → keys 恒空串，永远留在候选集）时
     * 本批零进展，立即终止防止死循环；这些行的键保持空串（与搜索语义无害——空串不匹配任何查询）。
     */
    suspend fun run(dao: MediaItemDao, batchSize: Int = BATCH_SIZE): Int {
        var updated = 0
        while (true) {
            val rows = runCatching { dao.pinyinBackfillCandidates(batchSize) }.getOrDefault(emptyList())
            if (rows.isEmpty()) break
            var progressed = false
            for (row in rows) {
                val (pinyin, initials) = MediaNames.keys(row.name)
                if (pinyin.isEmpty() && initials.isEmpty()) continue   // 退化名：无键可写，留候选集
                runCatching { dao.updatePinyinKeys(row.id, pinyin, initials) }
                progressed = true
                updated++
            }
            if (!progressed || rows.size < batchSize) break
        }
        return updated
    }
}
