package com.livetv.tv.data

import java.util.UUID
import java.io.Serializable

data class Source(
    var id: String? = null,
    var uri: String,
    var checked: Boolean = false,
) {
    init {
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
        }
    }
}


data class StableSource(
    val id: Int,
    val name: String,
    val title: String,
    val description: String?,
    val logo: String,
    val image: String?,
    val uris: List<String>,
    val videoIndex: Int,
    val headers: Map<String, String>?,
    val group: String,
    val sourceType: String,
    val number: Int,
    val child: List<TV>,
    val timestamp: Long,
    // WebView 相关字段
    val playerType: PlayerType = PlayerType.IPTV, // 播放类型
    val block: List<String>? = emptyList(), // WebView 黑名单
    val script: String? = null, // WebView 脚本
    val selector: String? = null, // WebView 选择器
    val started: String? = null, // WebView 开始脚本
    val finished: String? = null // WebView 完成脚本
) : Serializable