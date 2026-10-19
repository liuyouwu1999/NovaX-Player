package com.novax.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4-4（发版审查）：versionCode 方案数学性质锁定。
 * 公式（与 app/build.gradle.kts 中 nvReleaseBase 注释同步维护，任一侧改动须同步另一侧）：
 *   base = 发版日YYYYMMDD × 100 + 同日序号 n × 10   （n=0 为当日首版，n ∈ 0..9）
 *   code = base + ABI 偏移（arm64-v8a=1, armeabi-v7a=2, x86_64=3）
 */
class ReleaseVersionSchemeTest {

    private fun base(date: Int, n: Int): Int {
        require(n in 0..9) { "同日序号超限（最多 10 版/日）" }
        val b = date * 100 + n * 10
        require(b + 3 <= Int.MAX_VALUE) { "versionCode 溢出" }
        return b
    }

    @Test fun `相邻两日任一版本号对不碰撞`() {
        for (n in 0..9) {
            val todayMax = base(20261016, n) + 3
            val tomorrowMin = base(20261017, 0)
            assertTrue("同日第${n}版最高码 $todayMax 须小于次日最低码 $tomorrowMin", todayMax < tomorrowMin)
        }
    }

    @Test fun `同日多版自增且ABI不重叠`() {
        val v0 = (1..3).map { base(20261016, 0) + it }        // 首版三 ABI（+1/+2/+3）
        val v1 = (1..3).map { base(20261016, 1) + it }        // 同日第二版
        org.junit.Assert.assertTrue(v0.max() < v1.min())
        org.junit.Assert.assertEquals(3, v0.toSet().size)
    }

    @Test fun `日期上限2147年内不溢出`() {
        assertTrue(base(21471231, 9) + 3 <= Int.MAX_VALUE)
    }

    @Test(expected = IllegalArgumentException::class) fun `同日第11版拒绝`() { base(20261016, 10) }

    private fun assertEquals(a: Int, b: Int) = org.junit.Assert.assertEquals(a, b)
}
