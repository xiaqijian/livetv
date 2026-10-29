package com.livetv.tv

import android.content.Context
import android.os.Build
import android.util.Log
import com.livetv.tv.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.system.exitProcess

class YourTVExceptionHandler(val context: Context) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        val crashInfo =
            "APP: ${context.appVersionName}, PRODUCT: ${Build.PRODUCT}, DEVICE: ${Build.DEVICE}, SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString()}, BOARD: ${Build.BOARD}, MANUFACTURER: ${Build.MANUFACTURER}, MODEL: ${Build.MODEL}, VERSION: ${Build.VERSION.SDK_INT}\nThread: ${t.name}\nException: ${e.message}\nStackTrace: ${
                Log.getStackTraceString(
                    e
                )
            }\n"

        runBlocking {
            launch {
                saveCrashInfoToFile(crashInfo)

                withContext(Dispatchers.Main) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(1)
                }
            }
        }
    }

    private suspend fun saveCrashInfoToFile(crashInfo: String) {
        // 只写本地日志。原实现会把设备型号/厂商/堆栈 POST 到第三方服务器，已移除。
        Log.e(TAG, crashInfo)
    }

    companion object {
        private const val TAG = "YourTVException"
    }
}