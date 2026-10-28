package com.livetv.tv.legacy.models

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.livetv.tv.R
import com.livetv.tv.legacy.SP
import com.livetv.tv.legacy.Utils.getDateFormat
import com.livetv.tv.legacy.data.Global.gson
import com.livetv.tv.legacy.data.Global.typeTvList
import com.livetv.tv.legacy.data.TV
import com.livetv.tv.legacy.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.livetv.tv.SourceDecoder



object TVList {
    private const val TAG = "TVList"
    const val CACHE_FILE_NAME = "web_channels.txt"
    val DEFAULT_CHANNELS_FILE = R.raw.web_channels
    private lateinit var appDirectory: File
    private lateinit var serverUrl: String
    private var list: List<TV> = emptyList()

    var listModel: List<TVModel> = listOf()
    val groupModel = TVGroupModel()

    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    private val _position = MutableLiveData<Int>()
    val position: LiveData<Int>
        get() = _position

    fun init(context: Context) {
        _position.value = 0

        groupModel.addTVListModel(TVListModel(context.getString(R.string.my_favorites), 0))
        groupModel.addTVListModel(TVListModel(context.getString(R.string.all_channels), 1))

        appDirectory = context.filesDir
        val file = File(appDirectory, CACHE_FILE_NAME)
        val str = if (file.exists()) {
            Log.i(TAG, "read $file")
            file.readText()
        } else {
            Log.i(TAG, "read resource")
            context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }

        try {
            str2List(str)
        } catch (e: Exception) {
            Log.e("", "error $e")
            file.deleteOnExit()
            Toast.makeText(context, "读取频道失败，请在菜单中进行设置", Toast.LENGTH_LONG).show()
        }

        if (SP.configAutoLoad && !SP.configUrl.isNullOrEmpty()) {
            SP.configUrl?.let {
                update(it)
            }
        }
        Log.i(TAG, "str2List result: ${str2List(str)}")
        Log.i(TAG, "groupModel size: ${groupModel.size()}")
        Log.i(TAG, "tvListModel at position 0: ${groupModel.getTVListModel(0)}")
    }

    private fun update() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "request $serverUrl")
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(serverUrl).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val file = File(appDirectory, CACHE_FILE_NAME)
                    if (!file.exists()) {
                        file.createNewFile()
                    }
                    response.body?.let {
                        val str = it.string()
                        withContext(Dispatchers.Main) {
                            if (str2List(str)) {
                                file.writeText(str)
                                SP.configUrl = serverUrl
                                R.string.channel_import_success.showToast()
                            } else {
                                R.string.channel_import_error.showToast()
                            }
                        }
                    }
                } else {
                    Log.e("", "request status ${response.code}")
                    R.string.channel_import_error.showToast()
                }
            } catch (e: Exception) {
                Log.e("", "request error $e")
                R.string.channel_import_error.showToast()
            }
        }
    }

    private fun update(serverUrl: String) {
        this.serverUrl = serverUrl
        update()
    }

    fun parseUri(uri: Uri) {
        if (uri.scheme == "file") {
            val file = uri.toFile()
            Log.i(TAG, "file $file")
            val str = if (file.exists()) {
                Log.i(TAG, "read $file")
                file.readText()
            } else {
                R.string.file_not_exist.showToast(Toast.LENGTH_LONG)
                return
            }

            try {
                if (str2List(str)) {
                    SP.configUrl = uri.toString()
                    R.string.channel_import_success.showToast(Toast.LENGTH_LONG)
                } else {
                    R.string.channel_import_error.showToast(Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                Log.e("", "error $e")
                file.deleteOnExit()
                R.string.channel_import_error.showToast(Toast.LENGTH_LONG)
            }
        } else {
            update(uri.toString())
        }
    }

    fun str2List(str: String): Boolean {
        var string = str
        if (str.isEmpty()) {
            Log.e(TAG, "Input string is empty")
            list = emptyList()
            return false
        }
        try {
            val isPlainText = str.trim().startsWith("#EXTM3U") ||
                    str.trim().startsWith("http://") ||
                    str.trim().startsWith("https://")
            val isHex = str.trim().matches(Regex("^[0-9a-fA-F]+$"))

            if (isPlainText) {
                string = str // 明文直接使用
            } else if (isHex) {
                val decodedStr = SourceDecoder.decodeHexSource(str) ?: str
                string = decodedStr
            } else {
                // 非明文非 HEX，尝试解码
                try {
                    val decodedStr = SourceDecoder.decodeHexSource(str) ?: str
                    string = decodedStr
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process non-plaintext, non-hex content: ${e.message}")
                    string = str // 解码失败回退到原始字符串
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process string: ${e.message}", e)
            string = str // 异常时回退到原始字符串
        }

        // 初始化 list
        list = emptyList()

        // 使用 getOrNull 防止索引越界
        if (string.getOrNull(0) != '[') {
            Log.e(TAG, "Invalid string format, not starting with '[': $string")
            return false
        }

        try {
            list = gson.fromJson(string, typeTvList) ?: emptyList()
            Log.i(TAG, "导入频道 ${list.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: $string", e)
            return false
        }

        groupModel.clear()

        val map: MutableMap<String, MutableList<TVModel>> = mutableMapOf()
        for (v in list) {
            if (v.group !in map) {
                map[v.group] = mutableListOf()
            }
            map[v.group]?.add(TVModel(v))
        }

        val listModelNew: MutableList<TVModel> = mutableListOf()
        var groupIndex = 2
        var id = 0
        for ((k, v) in map) {
            val tvListModel = TVListModel(k, groupIndex)
            for ((listIndex, v1) in v.withIndex()) {
                v1.tv.id = id
                v1.groupIndex = groupIndex
                v1.listIndex = listIndex
                tvListModel.addTVModel(v1)
                listModelNew.add(v1)
                id++
            }
            groupModel.addTVListModel(tvListModel)
            groupIndex++
        }

        listModel = listModelNew
        groupModel.getTVListModel(1)?.setTVListModel(listModel)
        Log.i(TAG, "groupModel ${groupModel.size()}")
        groupModel.setChange()

        return true
    }

    fun getTVModel(): TVModel? {
        return getTVModel(position.value!!)
    }

    fun getTVModel(idx: Int): TVModel? {
        if (idx >= size()) {
            return null
        }
        return listModel[idx]
    }

    fun setPosition(position: Int): Boolean {
        Log.i(TAG, "setPosition $position/${size()}")
        if (position >= size()) {
            return false
        }

        if (_position.value != position) {
            _position.value = position
        }

        val tvModel = getTVModel(position)

        // set a new position or retry when position same
        tvModel!!.setReady()

        groupModel.setPosition(tvModel.groupIndex)

        SP.positionGroup = tvModel.groupIndex
        SP.position = position
        return true
    }

    fun size(): Int {
        return listModel.size
    }

    fun reset() {
        // 清空频道列表和相关状态
        list = emptyList()
        listModel = emptyList()
        _position.value = 0
        groupModel.clear()
        SP.position = 0
        SP.positionGroup = 0
        SP.configUrl = null
        // 删除缓存文件
        val file = File(appDirectory, CACHE_FILE_NAME)
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "Cache file $CACHE_FILE_NAME deleted")
        }
        Log.d(TAG, "TVList state reset")
    }

    fun reloadData(context: Context) {
        // 重新执行初始化逻辑
        init(context)
        Log.d(TAG, "TVList data reloaded")
    }

}