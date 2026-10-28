package com.livetv.tv.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.livetv.tv.R

object Global {
    val gson = Gson()

    val typeTvList = object : TypeToken<List<TV>>() {}.type
    val typeSourceList = object : TypeToken<List<Source>>() {}.type
    val typeEPGMap = object : TypeToken<Map<String, List<EPG>>>() {}.type
    val typeStableSourceList = object : TypeToken<List<StableSource>>() {}.type


    val scriptMap = mapOf(
        "live.kankanews.com" to R.raw.ahtv1,
        "www.cbg.cn" to R.raw.ahtv1,
        "www.sxrtv.com" to R.raw.sxrtv1,
        "www.xjtvs.com.cn" to R.raw.xjtv1,
        "www.yb983.com" to R.raw.ahtv1,
        "www.yntv.cn" to R.raw.ahtv1,
        "www.nmtv.cn" to R.raw.nmgtv1,
        "live.snrtv.com" to R.raw.ahtv1,
        "www.btzx.com.cn" to R.raw.ahtv1,
        "static.hntv.tv" to R.raw.ahtv1,
        "www.hljtv.com" to R.raw.ahtv1,
        "www.qhtb.cn" to R.raw.ahtv1,
        "www.qhbtv.com" to R.raw.ahtv1,
        "v.iqilu.com" to R.raw.ahtv1,
        "www.jlntv.cn" to R.raw.ahtv1,
        "www.cztv.com" to R.raw.ahtv1,
        "www.gzstv.com" to R.raw.ahtv1,
        "www.jxntv.cn" to R.raw.jxtv1,
        "www.hnntv.cn" to R.raw.ahtv1,
        "live.mgtv.com" to R.raw.ahtv1,
        "www.hebtv.com" to R.raw.ahtv1,
        "tc.hnntv.cn" to R.raw.ahtv1,
        "live.fjtv.net" to R.raw.ahtv1,
        "tv.gxtv.cn" to R.raw.ahtv1,
        "www.nxtv.com.cn" to R.raw.ahtv1,
        "www.ahtv.cn" to R.raw.ahtv2,
        "news.hbtv.com.cn" to R.raw.ahtv1,
        "www.sztv.com.cn" to R.raw.ahtv1,
        "www.setv.sh.cn" to R.raw.ahtv1,
        "www.gdtv.cn" to R.raw.ahtv1,
        "tv.cctv.com" to R.raw.ahtv1,
        "www.yangshipin.cn" to R.raw.ahtv1,
        "www.brtn.cn" to R.raw.xjtv1,
        "www.kangbatv.com" to R.raw.ahtv1,
        "live.jstv.com" to R.raw.xjtv1,
        "www.wfcmw.cn" to R.raw.xjtv1,
    )

    val blockMap = mapOf(
        "央视甲" to listOf(
            "jweixin",
            "daohang",
            "dianshibao.js",
            "dingtalk.js",
            "configtool",
            "qrcode",
            "shareindex.js",
            "zhibo_shoucang.js",
            "gray",
            "cntv_Advertise.js",
            "top2023newindex.js",
            "indexPC.js",
            "getEpgInfoByChannelNew",
            "epglist",
            "epginfo",
            "getHandDataList",
            "2019whitetop/index.js",
            "pc_nav/index.js",
            "shareindex.js",
            "mapjs/index.js",
            "bottomjs/index.js",
            "top2023newindex.js",
            "2019dlbhyjs/index.js"
        ),
    )
}