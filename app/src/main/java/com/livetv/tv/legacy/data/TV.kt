package com.livetv.tv.legacy.data

import java.io.Serializable

data class TV(
    var id: Int = 0,
    var name: String = "",
    var title: String = "",
    var started: String? = null,
    var script: String? = null,
    var finished: String? = null,
    var logo: String = "",
    var uris: List<String> = emptyList(), // 默认空列表
    var headers: Map<String, String>? = null,
    var group: String = "",
    var block: List<String>? = null, // 改为可空，与 yourtv.TV 一致
    var selector: String? = null,
    var index: Int? = null
) : Serializable {

    override fun toString(): String {
        return "TV(" +
                "id=$id, " +
                "name='$name', " +
                "title='$title', " +
                "started='$started', " +
                "script='${script?.take(20)}...', " +
                "finished='$finished', " +
                "logo='$logo', " +
                "uris=$uris, " +
                "headers=$headers, " +
                "group='$group', " +
                "block=$block" +
                ")"
    }
}