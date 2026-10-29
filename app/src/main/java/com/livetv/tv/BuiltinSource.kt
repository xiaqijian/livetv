package com.livetv.tv

/**
 * 内置订阅地址。
 *
 * 选型依据：该源由国内维护者整理，分组完整（央视 / 卫视 / 地方 / 电影 / 纪录等），
 * 在本机实测 14 个流地址中 12 个可播放，远好于 iptv-org 官方源的海外地址。
 *
 * 按顺序尝试，前面的失败自动走下一个：
 * 1. jsdelivr 主站 CDN —— 国内可直连，更新跟随仓库 master 分支
 * 2. jsdelivr fastly 节点 —— 主站不可用时的备用 CDN
 * 3. GitHub raw —— 最后兜底，部分网络环境不可达
 *
 * 全部失败时回退到 res/raw/channels.txt 内置快照，保证无网络也能看。
 */
object BuiltinSource {
    const val PRIMARY = "https://cdn.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u"

    val URLS: List<String> = listOf(
        PRIMARY,
        "https://fastly.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u",
        "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u"
    )
}
