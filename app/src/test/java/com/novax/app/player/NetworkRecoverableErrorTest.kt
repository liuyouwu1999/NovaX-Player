package com.novax.app.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动重连错误分类防回归（2026-09-17 网络恢复自动重连功能）：
 * 只有「恢复网络后重试有意义」的网络类错误码才触发自动重连——
 * 文件不存在/解析失败/解码失败等重试也不会成功的错误必须排除。
 */
class NetworkRecoverableErrorTest {

    @Test
    fun `network class error codes are recoverable`() {
        assertTrue(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
        assertTrue(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_TIMEOUT))
        assertTrue(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test
    fun `non-recoverable error codes are excluded`() {
        // 重试无意义的错误类别：路径/权限、容器解析、解码、未指定
        assertFalse(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertFalse(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
        assertFalse(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertFalse(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertFalse(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertFalse(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(isNetworkRecoverableErrorCode(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }
}
