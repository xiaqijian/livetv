package com.livetv.tv

/**
 * 用户输入的订阅地址校验。纯逻辑、无 Android 依赖，便于单元测试。
 */
object SourceUrlValidator {

    /** 去掉首尾空白后的地址；空串表示用户没输入 */
    fun normalized(raw: String?): String = raw?.trim().orEmpty()

    /**
     * 地址是否可以拿去发起导入。
     * 只接受 http/https，且必须带主机名，避免把本地文件路径误当订阅地址。
     */
    fun isValid(raw: String?): Boolean {
        val url = normalized(raw)
        if (url.isEmpty()) return false
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val host = url
            .substringAfter("://", "")
            .substringBefore("/")
            .substringBefore("?")
            .substringAfter("@")
            .substringBefore(":")
        return host.isNotEmpty() && host.contains(".")
    }

    /** 校验失败时该提示哪条错误 */
    fun errorMessageRes(raw: String?): Int? = when {
        normalized(raw).isEmpty() -> R.string.source_url_empty
        !isValid(raw) -> R.string.source_url_invalid
        else -> null
    }
}
