package com.livetv.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.google.common.reflect.TypeToken
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.content.Intent

data class UserInfo(
    val userId: String = "",
    val userLimitDate: String = "",
    val userType: String = "",
    val vipUserUrl: String = "",
    val maxDevices: Int = 5,
    val devices: List<String> = emptyList(),
    val userUpdateStatus: Boolean = false,
    val updateDate: String = ""
)

data class RemoteUserInfo(
    val userId: String,
    val userLimitDate: String,
    val userType: String,
    val vipUserUrl: String,
    val maxDevices: Int,
    val devices: List<String>,
    @SerializedName("indemnify") val indemnify: List<String>? = null
)

data class FileMapping(
    val fileName: String
)

data class ApiKeys(
    val api_token: String,
    val account_id: String,
    val database_id: String,
    val worker_api_key: String,
    val r2_endpoint: String,
    val r2_access_key_id: String,
    val r2_secret_access_key: String
)

@SuppressLint("StaticFieldLeak")
object UserInfoManager {
    private lateinit var context: Context
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var prefs: SharedPreferences
    private var remoteUsersCache: List<RemoteUserInfo>? = null
    private var warningMessagesCache: List<String>? = null
    private var fileMappingsCache: List<FileMapping>? = null
    private var rawJsonCache: JsonObject? = null
    internal var apiKeys: ApiKeys? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val TAG = "UserInfoManager"
    private const val USER_INFO_FILE = "users_infon.txt"
    private const val PREFS_NAME = "UserDataCache"
    private const val KEY_USERS = "remote_users"
    private const val KEY_WARNINGS = "warning_messages"
    private const val KEY_FILES = "file_mappings"
    private const val KEY_CACHE_TIME = "cache_time"
    private const val CACHE_DURATION = 24 * 60 * 60 * 1000 // 24 小时
    private const val KEY_TEST_CODES = "test_codes"
    private const val KEY_ACTIVE_USER_ID = "active_user_id"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"


    fun initialize(context: Context) {
        this.context = context.applicationContext
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            this.prefs = EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences: ${e.message}", e)
            this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        loadApiKeys(context)
        loadCache()
        Log.d(TAG, "UserInfoManager initialized")
        // 检查是否需要运行测试码过期检查
        CoroutineScope(Dispatchers.IO).launch {
            val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
            val currentTime = System.currentTimeMillis()
            val checkInterval = 24 * 60 * 60 * 1000L // 24 小时
            if (currentTime - lastCheckTime >= checkInterval) {
                checkExpiredTestCodes()
                with(prefs.edit()) {
                    putLong(KEY_LAST_CHECK_TIME, currentTime)
                    apply()
                }
                Log.d(TAG, "Updated last check time: $currentTime")
            } else {
                Log.d(TAG, "No need to check test codes, last check: $lastCheckTime, current: $currentTime")
            }
        }
    }

    @Suppress("UNREACHABLE_CODE")
    fun loadApiKeys(context: Context) {
        // 本应用不使用上游作者的 Cloudflare 账号与数据库，跳过密钥加载
        return
        try {
            val hexContent = context.resources.openRawResource(R.raw.cloudflare)
                .bufferedReader()
                .use { it.readText() }
            Log.d(TAG, "Raw cloudflare hex content: ${hexContent.take(50)}... (length: ${hexContent.length})")
            val decodedJson = SourceDecoder.decodeHexSource(hexContent) ?: run {
                Log.e(TAG, "Failed to decode cloudflare hex content")
                return
            }
            val cleanedJson = decodedJson.trim().replace(Regex("[\\p{Cntrl}\\s]+"), " ")
            Log.d(TAG, "Cleaned JSON: ${cleanedJson.take(100)}... (length: ${cleanedJson.length})")
            try {
                JSONObject(cleanedJson)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid JSON format: ${e.message}\nRaw JSON: $cleanedJson")
                return
            }
            val json = JSONObject(cleanedJson)
            apiKeys = ApiKeys(
                api_token = json.getString("api_token"),
                account_id = json.getString("account_id"),
                database_id = json.getString("database_id"),
                worker_api_key = json.optString("worker_api_key", ""),
                r2_endpoint = json.getString("endpoint").removeSuffix("/yourtvm3u"),
                r2_access_key_id = json.getString("Access Key ID"),
                r2_secret_access_key = json.getString("Secret Access Key")
            )
            Log.d(TAG, "Loaded ApiKeys: account_id=${apiKeys?.account_id}, database_id=${apiKeys?.database_id}, r2_endpoint=${apiKeys?.r2_endpoint}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load API keys: ${e.message}", e)
        }
    }

    fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    suspend fun checkBinding(key: String, deviceId: String): Pair<Boolean, String?> {
        val user = getUserInfoById(key) ?: return Pair(false, "無效的測試碼")
        val currentDeviceCount = if (user.devices.contains(deviceId)) user.devices.size else user.devices.size + 1
        if (currentDeviceCount > user.maxDevices) {
            return Pair(false, "測試碼已綁定過多設備（最多${user.maxDevices}个）")
        }
        return Pair(true, null)
    }

    suspend fun updateBinding(key: String, deviceId: String): Pair<Boolean, String?> {
        try {
            val (isValid, errorMessage) = checkBinding(key, deviceId)
            if (!isValid) {
                Log.w(TAG, "绑定检查失败: key=$key, deviceId=$deviceId, error=$errorMessage")
                return Pair(false, errorMessage)
            }
            // 在函数开头，替换原有的 user 获取逻辑
            val (warnings, remoteUsers) = downloadRemoteUserInfo() // 强制刷新远程数据
            val user = remoteUsers.find { it.userId == key } ?: run {
                Log.e(TAG, "用户未找到: key=$key")
                return Pair(false, "无效的测试码")
            }
            Log.d(TAG, "更新绑定开始: key=$key, deviceId=$deviceId, currentDevices=${user.devices}")

            // 确定设备ID是否需要添加
            val updatedDevices = if (user.devices.contains(deviceId)) {
                user.devices
            } else {
                user.devices.toMutableList().apply { add(deviceId) }
            }

            // 更新D1数据库
            val jsonBody = JSONObject().apply {
                put("sql", "UPDATE test_codes SET devices = ? WHERE code = ?")
                put("params", JSONArray().apply {
                    put(gson.toJson(updatedDevices))
                    put(key)
                })
            }
            val response = executeD1Query(jsonBody.toString())
            if (!response.optBoolean("success")) {
                Log.e(TAG, "D1更新失败: key=$key, error=${response.optString("error")}")
                return Pair(false, response.optString("error", "更新绑定失败"))
            }
            Log.d(TAG, "D1更新成功: key=$key, devices=$updatedDevices")

            // 更新内存缓存
            remoteUsersCache = remoteUsersCache?.map {
                if (it.userId == key) it.copy(devices = updatedDevices) else it
            }

            // 更新rawJsonCache
            val rawJson = rawJsonCache ?: JsonObject().apply {
                add("warnings", JsonArray())
                add("users", JsonArray())
                addProperty("backupDate", SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()))
            }
            val usersArray = rawJson.getAsJsonArray("users") ?: JsonArray()
            var userFound = false
            for (userElement in usersArray) {
                if (userElement.isJsonObject && userElement.asJsonObject.get("userId")?.asString == key) {
                    val newDevicesArray = JsonArray().apply { updatedDevices.forEach { add(it) } }
                    userElement.asJsonObject.add("devices", newDevicesArray)
                    userFound = true
                    break
                }
            }
            if (!userFound) {
                val newUser = JsonObject().apply {
                    addProperty("userId", user.userId)
                    addProperty("userLimitDate", user.userLimitDate)
                    addProperty("userType", user.userType)
                    addProperty("vipUserUrl", user.vipUserUrl) // 使用最新 remoteUsers 的 vipUserUrl
                    addProperty("maxDevices", user.maxDevices)
                    add("devices", JsonArray().apply { updatedDevices.forEach { add(it) } })
                    add("indemnify", JsonArray().apply { user.indemnify?.forEach { add(it) } })
                }
                usersArray.add(newUser)
            }
            rawJson.addProperty("backupDate", SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()))
            Log.d(TAG, "rawJsonCache更新: $rawJson")

            // 序列化和验证JSON
            val json = gson.toJson(rawJson)
            try {
                gson.fromJson(json, JsonObject::class.java)
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "rawJsonCache无效: ${e.message}")
                return Pair(false, "数据格式错误")
            }
            val encodedContent = SourceEncoder.encodeJsonSource(json)
            Log.d(TAG, "生成数据: key=$key, encodedContentLength=${encodedContent.length}")

            // 仅上传到users_infon.txt
            val uploadResult: Result<Unit> = DownGithubPrivate.uploadFile(
                context = context,
                repo = "horsenmail/yourtv",
                filePath = USER_INFO_FILE,
                branch = "main",
                updatedContent = encodedContent,
                commitMessage = "Update user binding for $key"
            )
            if (uploadResult.isSuccess) {
                Log.d(TAG, "上传到 $USER_INFO_FILE 成功")
            } else {
                Log.e(TAG, "上传到 $USER_INFO_FILE 失败: ${uploadResult.exceptionOrNull()?.message}")
            }

            // 更新缓存和本地文件
            rawJsonCache = rawJson
            saveCache()
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            saveUserInfo(
                UserInfo(
                    userId = key,
                    userLimitDate = user.userLimitDate,
                    userType = user.userType,
                    vipUserUrl = user.vipUserUrl,
                    maxDevices = user.maxDevices,
                    devices = updatedDevices,
                    userUpdateStatus = true,
                    updateDate = today
                )
            )
            // 新增：保存测试码和直播源文件名
            val sourceName = user.vipUserUrl.substringAfterLast("/")
            saveTestCode(key, sourceName)
            Log.d(TAG, "绑定完成: key=$key, devices=$updatedDevices")
            return Pair(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "更新绑定失败: key=$key, error=${e.message}", e)
            return Pair(false, "网络不佳，无法验证测试码")
        }
    }

    @Suppress("UNREACHABLE_CODE")
    suspend fun downloadRemoteUserInfo(forceRefresh: Boolean = false): Pair<List<String>, List<RemoteUserInfo>> {
        // 本应用不使用上游作者的云端用户库，直接返回空结果，避免无谓的网络等待
        return emptyList<String>() to emptyList()
        val cacheTime = prefs.getLong(KEY_CACHE_TIME, 0)
        if (!forceRefresh && remoteUsersCache != null && warningMessagesCache != null && fileMappingsCache != null &&
            System.currentTimeMillis() - cacheTime < CACHE_DURATION) {
            Log.d(TAG, "Hit cache: users=${remoteUsersCache!!.size}, warnings=${warningMessagesCache!!.size}, files=${fileMappingsCache!!.size}")
            return warningMessagesCache!! to remoteUsersCache!!
        }
        Log.d(TAG, "Fetching data from D1")
        return withContext(Dispatchers.IO) {
            try {
                if (apiKeys == null) {
                    Log.e(TAG, "API keys not loaded, attempting GitHub fallback")
                    return@withContext downloadFromGitHub()
                }
                val usersQuery = JSONObject().apply {
                    put("sql", "SELECT code AS userId, expiryDate AS userLimitDate, userType, fileName, maxDevices, devices FROM test_codes")
                    put("params", JSONArray())
                }
                val usersResponse = executeD1Query(usersQuery.toString())
                if (!usersResponse.optBoolean("success")) {
                    Log.e(TAG, "test_codes query failed: ${usersResponse.optString("error")}, attempting GitHub fallback")
                    return@withContext downloadFromGitHub()
                }
                val usersJson = usersResponse.getJSONArray("result").getJSONObject(0).getJSONArray("results")
                val remoteUsers = mutableListOf<RemoteUserInfo>()
                for (i in 0 until usersJson.length()) {
                    val user = usersJson.getJSONObject(i)
                    val fileName = user.optString("fileName", "")
                    val devices = gson.fromJson(user.getString("devices"), Array<String>::class.java).toList()
                    remoteUsers.add(
                        RemoteUserInfo(
                            userId = user.getString("userId"),
                            userLimitDate = user.getString("userLimitDate"),
                            userType = user.getString("userType"),
                            vipUserUrl = fileName,
                            maxDevices = user.getInt("maxDevices"),
                            devices = devices,
                            indemnify = null
                        )
                    )
                }

                val warningsQuery = JSONObject().apply {
                    put("sql", "SELECT message FROM warnings")
                    put("params", JSONArray())
                }
                val warningsResponse = executeD1Query(warningsQuery.toString())
                val warningMessages = mutableListOf<String>()
                if (warningsResponse.optBoolean("success")) {
                    val warningsJson = warningsResponse.getJSONArray("result").getJSONObject(0).getJSONArray("results")
                    for (i in 0 until warningsJson.length()) {
                        warningMessages.add(warningsJson.getJSONObject(i).getString("message"))
                    }
                } else {
                    Log.w(TAG, "warnings query failed: ${warningsResponse.optString("error")}")
                }

                val filesQuery = JSONObject().apply {
                    put("sql", "SELECT fileName FROM m3u_files")
                    put("params", JSONArray())
                }
                val filesResponse = executeD1Query(filesQuery.toString())
                val fileMappings = mutableListOf<FileMapping>()
                if (filesResponse.optBoolean("success")) {
                    val filesJson = filesResponse.getJSONArray("result").getJSONObject(0).getJSONArray("results")
                    for (i in 0 until filesJson.length()) {
                        fileMappings.add(FileMapping(fileName = filesJson.getJSONObject(i).getString("fileName")))
                    }
                } else {
                    Log.w(TAG, "m3u_files query failed: ${filesResponse.optString("error")}")
                }

                // 同步到 GitHub
                val rawJson = JsonObject().apply {
                    add("warnings", JsonArray().apply { warningMessages.forEach { add(it) } })
                    add("users", JsonArray().apply {
                        remoteUsers.forEach {
                            add(JsonObject().apply {
                                addProperty("userId", it.userId)
                                addProperty("userLimitDate", it.userLimitDate)
                                addProperty("userType", it.userType)
                                addProperty("vipUserUrl", it.vipUserUrl)
                                addProperty("maxDevices", it.maxDevices)
                                add("devices", JsonArray().apply { it.devices.forEach { add(it) } })
                                add("indemnify", JsonArray().apply { it.indemnify?.forEach { add(it) } })
                            })
                        }
                    })
                    addProperty("backupDate", SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()))
                }
                val json = gson.toJson(rawJson)
                try {
                    gson.fromJson(json, JsonObject::class.java)
                    val encodedContent = SourceEncoder.encodeJsonSource(json)
                    val uploadResult = DownGithubPrivate.uploadFile(
                        context = context,
                        repo = "horsenmail/yourtv",
                        filePath = USER_INFO_FILE,
                        branch = "main",
                        updatedContent = encodedContent,
                        commitMessage = "Sync $USER_INFO_FILE from D1"
                    )
                    if (uploadResult.isSuccess) {
                        rawJsonCache = rawJson
                    } else {
                        Log.w(TAG, "Failed to sync to GitHub: ${uploadResult.exceptionOrNull()?.message}")
                    }
                } catch (e: JsonSyntaxException) {
                    Log.e(TAG, "Failed to generate valid JSON for GitHub sync: ${e.message}")
                }

                remoteUsersCache = remoteUsers
                warningMessagesCache = warningMessages
                fileMappingsCache = fileMappings
                saveCache()
                Log.d(TAG, "Fetched data: users=${remoteUsers.size}, warnings=${warningMessages.size}, files=${fileMappings.size}")
                warningMessages to remoteUsers
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch D1 data: ${e.message}, attempting GitHub fallback")
                downloadFromGitHub()
            }
        }
    }

    private suspend fun downloadFromGitHub(): Pair<List<String>, List<RemoteUserInfo>> {
        Log.d(TAG, "Downloading $USER_INFO_FILE from GitHub")
        val warningMessages = mutableListOf<String>()
        val remoteUsers = mutableListOf<RemoteUserInfo>()
        var rawJson: JsonObject? = null

        try {
            val usersUrl = "https://raw.githubusercontent.com/horsenmail/yourtv/main/$USER_INFO_FILE"
            val result = DownGithubPrivate.download(context, usersUrl)
            result.getOrNull()?.let { hexContent ->
                try {
                    val decoded = SourceDecoder.decodeHexSource(hexContent)
                    if (decoded == null) {
                        Log.e(TAG, "Failed to decode hex content")
                        return@let
                    }
                    rawJson = try {
                        gson.fromJson(decoded, JsonObject::class.java)
                    } catch (e: JsonSyntaxException) {
                        Log.e(TAG, "Malformed JSON detected: ${e.message}")
                        val repairedJson = repairJson(decoded)
                        if (repairedJson != null) {
                            gson.fromJson(repairedJson, JsonObject::class.java)
                        } else {
                            Log.e(TAG, "Failed to repair JSON")
                            return warningMessages to remoteUsers // 直接返回空数据
                        }
                    }
                    rawJson?.let { json ->
                        val warningsArray = json.getAsJsonArray("warnings")
                        warningsArray?.forEach { warning ->
                            warning.asString?.let { warningMessages.add(it) }
                        }
                        val usersArray = json.getAsJsonArray("users")
                        usersArray?.forEach { userElement ->
                            if (userElement.isJsonObject) {
                                val userObj = userElement.asJsonObject
                                try {
                                    val devices = userObj.getAsJsonArray("devices")?.mapNotNull {
                                        it.asString?.takeIf { it.isNotBlank() }
                                    } ?: emptyList()
                                    val user = RemoteUserInfo(
                                        userId = userObj.get("userId")?.asString ?: "",
                                        userLimitDate = userObj.get("userLimitDate")?.asString ?: "",
                                        userType = userObj.get("userType")?.asString ?: "",
                                        vipUserUrl = userObj.get("vipUserUrl")?.asString ?: "",
                                        maxDevices = userObj.get("maxDevices")?.asInt ?: 5,
                                        devices = devices,
                                        indemnify = userObj.getAsJsonArray("indemnify")?.mapNotNull { it.asString }
                                    )
                                    remoteUsers.add(user)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse user object: $userObj, error: ${e.message}")
                                }
                            }
                        }
                        Log.d(TAG, "Parsed warnings: $warningMessages, users: $remoteUsers")
                    } ?: run {
                        Log.e(TAG, "Failed to parse JSON: rawJson is null")
                        return@let
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process $USER_INFO_FILE: ${e.message}", e)
                    return@let
                }
            } ?: Log.e(TAG, "Failed to download $USER_INFO_FILE: Empty content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download from GitHub: ${e.message}", e)
        }

        remoteUsersCache = remoteUsers
        warningMessagesCache = warningMessages
        rawJsonCache = rawJson
        saveCache()
        Log.d(TAG, "Cached $USER_INFO_FILE from GitHub, users: ${remoteUsers.size}, warnings: ${warningMessages.size}")
        return warningMessages to remoteUsers // 明確返回
    }

    private fun repairJson(json: String): String? {
        try {
            var repaired = json.replace(Regex("""\[""\]"""), "[]")
            repaired = repaired.replace(Regex("""\["\]"""), "[]")
            gson.fromJson(repaired, JsonObject::class.java)
            Log.d(TAG, "Successfully repaired JSON")
            return repaired
        } catch (e: Exception) {
            Log.e(TAG, "Failed to repair JSON: ${e.message}")
            return null
        }
    }

    private suspend fun executeD1Query(jsonBody: String): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKeys == null) {
                    Log.e(TAG, "API keys not loaded")
                    return@withContext JSONObject().apply { put("success", false); put("error", "API keys not loaded") }
                }
                val url = "https://api.cloudflare.com/client/v4/accounts/${apiKeys!!.account_id}/d1/database/${apiKeys!!.database_id}/query"
                Log.d(TAG, "D1 request URL: $url")
                Log.d(TAG, "D1 request body: $jsonBody")
                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                Log.d(TAG, "D1 request body bytes: ${requestBody.contentLength()} bytes")
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Authorization", "Bearer ${apiKeys!!.api_token}")
                    .header("Content-Type", "application/json")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: "{}"
                Log.d(TAG, "D1 response code: ${response.code}, body: $body")
                if (!response.isSuccessful) {
                    Log.e(TAG, "D1 query HTTP error: ${response.code}, body: $body")
                }
                JSONObject(body)
            } catch (e: Exception) {
                Log.e(TAG, "D1 query error: ${e.message}", e)
                JSONObject().apply { put("success", false); put("error", e.message) }
            }
        }
    }

    private fun loadCache() {
        try {
            remoteUsersCache = prefs.getString(KEY_USERS, null)?.let {
                gson.fromJson(it, Array<RemoteUserInfo>::class.java).toList()
            }
            warningMessagesCache = prefs.getString(KEY_WARNINGS, null)?.let {
                gson.fromJson(it, Array<String>::class.java).toList()
            }
            fileMappingsCache = prefs.getString(KEY_FILES, null)?.let {
                gson.fromJson(it, Array<FileMapping>::class.java).toList()
            }
            Log.d(TAG, "Loaded encrypted cache: users=${remoteUsersCache?.size}, warnings=${warningMessagesCache?.size}, files=${fileMappingsCache?.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load encrypted cache: ${e.message}", e)
            remoteUsersCache = null
            warningMessagesCache = null
            fileMappingsCache = null
        }
    }

    private fun saveCache() {
        try {
            with(prefs.edit()) {
                putString(KEY_USERS, gson.toJson(remoteUsersCache))
                putString(KEY_WARNINGS, gson.toJson(warningMessagesCache))
                putString(KEY_FILES, gson.toJson(fileMappingsCache))
                putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Saved encrypted cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save encrypted cache: ${e.message}", e)
        }
    }

    fun getRemoteUserInfo(): List<RemoteUserInfo> {
        return remoteUsersCache ?: emptyList()
    }

    fun getWarningMessages(): List<String> {
        return warningMessagesCache ?: emptyList()
    }

    fun getUserInfoById(userId: String): RemoteUserInfo? {
        return remoteUsersCache?.find { it.userId == userId }
    }

    fun loadUserInfo(): UserInfo? {
        val file = java.io.File(context.filesDir, USER_INFO_FILE)
        if (!file.exists()) {
            return null
        }
        return try {
            val content = file.readText()
            gson.fromJson(content, UserInfo::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user info: ${e.message}", e)
            null
        }
    }

    fun saveUserInfo(userInfo: UserInfo) {
        val file = java.io.File(context.filesDir, USER_INFO_FILE)
        try {
            file.writeText(gson.toJson(userInfo))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user info: ${e.message}", e)
        }
    }

    // 非 const，避免编译器把下方代码判定为不可达（不可达代码中智能转换会失效）
    private val disableUpstreamAuth = true

    fun validateKey(key: String, remoteUsers: List<RemoteUserInfo>): RemoteUserInfo? {
        // 本应用不启用上游的测试码授权机制，任何测试码都视为有效
        if (disableUpstreamAuth) {
            return RemoteUserInfo(key, "29991231", "vip", "", 99, emptyList())
        }
        // 記錄輸入參數
        Log.d(TAG, "validateKey called with key: $key, remoteUsers size: ${remoteUsers.size}")

        // 查找用戶
        val user = remoteUsers.find { it.userId == key }
        if (user == null) {
            Log.w(TAG, "No user found for key: $key")
            // 清理失效测试码
            val testCodes = getTestCodes().toMutableMap()
            if (testCodes.remove(key) != null) {
                with(prefs.edit()) {
                    putString(KEY_TEST_CODES, gson.toJson(testCodes))
                    apply()
                }
                Log.d(TAG, "Removed invalid test code: $key")
            }
            return null
        }
        Log.d(TAG, "Found user for key: $key, userLimitDate: ${user.userLimitDate}")

        return try {
            // 獲取當前日期
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            Log.d(TAG, "Current date (formatted): $today")

            // 選擇日期格式
            val sdfInput = if (user.userLimitDate.contains("-")) {
                Log.d(TAG, "Using yyyy-MM-dd format for userLimitDate: ${user.userLimitDate}")
                SimpleDateFormat("yyyy-MM-dd", Locale.US)
            } else {
                Log.d(TAG, "Using yyyyMMdd format for userLimitDate: ${user.userLimitDate}")
                SimpleDateFormat("yyyyMMdd", Locale.US)
            }

            // 解析日期
            val userDate = sdfInput.parse(user.userLimitDate)
            val currentDate = SimpleDateFormat("yyyyMMdd", Locale.US).parse(today)
            Log.d(TAG, "Parsed userDate: $userDate, currentDate: $currentDate")

            // 檢查日期是否有效
            if (userDate == null || currentDate == null) {
                Log.e(TAG, "Date parsing failed: userDate=$userDate, currentDate=$currentDate, userLimitDate=${user.userLimitDate}")
                return null
            }

            // 比較日期
            if (!userDate.before(currentDate)) {
                Log.d(TAG, "Key is valid: $key, userLimitDate=${user.userLimitDate} is not before $today")
                user
            } else {
                Log.w(TAG, "Key expired: $key, userLimitDate=${user.userLimitDate} is before $today")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate key: $key, userLimitDate=${user.userLimitDate}, error: ${e.message}", e)
            null
        }
    }

    fun createDefaultUserInfo(): UserInfo {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return UserInfo(
            userId = "testuser",
            userLimitDate = "19700101",
            userType = "",
            vipUserUrl = "",
            maxDevices = 5,
            devices = emptyList(),
            userUpdateStatus = false,
            updateDate = today
        )
    }

    fun saveTestCode(userId: String, sourceName: String) {
        val testCodes = getTestCodes().toMutableMap()
        val cleanSourceName = sourceName.removeSuffix(".txt")
        val filename = "$cleanSourceName.txt"
        testCodes[userId] = cleanSourceName
        try {
            with(prefs.edit()) {
                putString(KEY_TEST_CODES, gson.toJson(testCodes))
                putString(KEY_ACTIVE_USER_ID, userId)
                apply()
            }
            val savedCodes = getTestCodes()
            if (savedCodes[userId] == cleanSourceName) {
                Log.d(TAG, "Saved test code: userId=$userId, sourceName=$cleanSourceName, filename=$filename")
            } else {
                Log.e(TAG, "Failed to verify saved test code: userId=$userId, expected=$cleanSourceName, actual=$savedCodes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save test code: userId=$userId, error=${e.message}", e)
        }
    }

    fun getTestCodes(): Map<String, String> {
        return try {
            prefs.getString(KEY_TEST_CODES, null)?.let {
                gson.fromJson(it, object : TypeToken<Map<String, String>>() {}.type)
            } ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load test codes: ${e.message}", e)
            emptyMap()
        }
    }

    fun getActiveUserId(): String? {
        return prefs.getString(KEY_ACTIVE_USER_ID, null)
    }

    @Suppress("UNREACHABLE_CODE")
    suspend fun checkExpiredTestCodes() {
        // 本应用不使用上游作者的云端测试码库，跳过过期检查
        return
        Log.d(TAG, "Checking expired test codes")
        val testCodes = getTestCodes().toMutableMap()
        if (testCodes.isEmpty()) {
            Log.d(TAG, "No test codes to check")
            return
        }
        val remoteUsers = downloadRemoteUserInfo(forceRefresh = true).second
        val expiredCodes = mutableListOf<String>()
        for ((userId, sourceName) in testCodes) {
            val isValid = validateKey(userId, remoteUsers)
            if (isValid == null) {
                expiredCodes.add(userId)
                Log.w(TAG, "Test code expired: $userId, associated source: $sourceName")
            }
        }
        if (expiredCodes.isNotEmpty()) {
            val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
            with(prefs.edit()) {
                for (userId in expiredCodes) {
                    val sourceName = testCodes[userId]
                    // 修正文件名
                    val filename = sourceName?.removeSuffix(".txt") + ".txt"
                    val cacheFile = File(context.filesDir, "cache_$filename")
                    if (cacheFile.exists()) {
                        try {
                            if (cacheFile.delete()) {
                                Log.d(TAG, "Deleted cache file: cache_$filename for expired test code: $userId")
                            } else {
                                Log.w(TAG, "Failed to delete cache file: cache_$filename")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting cache file: cache_$filename", e)
                        }
                    } else {
                        Log.d(TAG, "Cache file not found: cache_$filename")
                    }
                    remove("cache_$filename")
                    remove("cache_time_$filename")
                    remove("url_$filename")
                    if (prefs.getString("active_source", null) == filename) {
                        // 设置默认源
                        putString("active_source", "default_channels.txt")
                        Log.d(TAG, "Set active_source to default_channels.txt for expired test code: $userId")
                    }
                    testCodes.remove(userId)
                    Log.d(TAG, "Removed cache entries for expired test code: $userId, filename: $filename")
                }
                putString(KEY_TEST_CODES, gson.toJson(testCodes))
                apply()
            }
            try {
                val intent = Intent("com.livetv.tv.TEST_CODE_EXPIRED")
                intent.putStringArrayListExtra("expired_codes", ArrayList(expiredCodes))
                context.sendBroadcast(intent)
                Log.d(TAG, "Broadcast sent for expired test codes: $expiredCodes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send broadcast for expired test codes: ${e.message}", e)
            }
        }
    }

}