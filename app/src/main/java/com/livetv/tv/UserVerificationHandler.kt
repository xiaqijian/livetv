package com.livetv.tv

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import com.livetv.tv.databinding.UserconfirmBinding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.LayoutInflater
import com.livetv.tv.MainActivity.VerificationCallback



@Suppress("NAME_SHADOWING")
class UserVerificationHandler(
    private val activity: MainActivity,
    private val userInfoManager: UserInfoManager,
    private val viewModel: MainViewModel
) {
    private val TAG = "UserVerificationHandler"
    private var lastClickTime = 0L
    private val DEBOUNCE_INTERVAL = 10000L // 5秒防抖间隔
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var binding: UserconfirmBinding
    private lateinit var remoteUsers: List<RemoteUserInfo>

    @SuppressLint("SetTextI18n")
    fun handleUserVerification(dialog: Dialog, callback: MainActivity.VerificationCallback) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.e(TAG, "handleUserVerification: Activity not in STARTED state, aborting")
            callback.onCompleted()
            return
        }

        activity.lifecycleScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "handleUserVerification: Started")

                // 初始化 remoteUsers
                remoteUsers = emptyList()

                // 配置对话框 UI
                binding = UserconfirmBinding.inflate(dialog.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                dialog.setContentView(binding.root)
                Log.d(TAG, "instructionText=${binding.loadingText1 != null}, confirmButton=${binding.confirmButton != null}, keyInput=${binding.keyInput != null}, errorText=${binding.errorText != null}")

                // 加载本地 user_info.txt
                val userInfo = try {
                    withContext(Dispatchers.IO) { userInfoManager.loadUserInfo() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load userInfo: ${e.message}", e)
                    null
                }
                Log.d(TAG, "Loaded userInfo=$userInfo")

                // 立即显示输入 UI
                setupInputUI(userInfo, callback, dialog.context, dialog)

                // 后台下载 remote users info
                launch(Dispatchers.IO) {
                    try {
                        val (warnings, users) = userInfoManager.downloadRemoteUserInfo(forceRefresh = true)
                        withContext(Dispatchers.Main) {
                            remoteUsers = users
                            activity.usersInfo = users.map { it.userId }
                            Log.d(TAG, "Downloaded users_infon.txt, users=$users")
                            // 更新 UI
                            updateUIAfterDownload(userInfo)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to download users_infon.txt: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            binding.errorText.text = activity.getString(R.string.network_poor_retry_skip)
                            binding.errorText.visibility = View.VISIBLE
                        }
                    }
                }

                // 验证对话框状态
                if (!dialog.isShowing || dialog.window == null) {
                    Log.e(TAG, "Dialog not showing or window is null, isShowing=${dialog.isShowing}")
                    callback.onCompleted()
                    return@launch
                }

                // 处理验证逻辑
                val verificationJob = activity.lifecycleScope.launch(Dispatchers.Main) {
                    val verificationCallback = object : MainActivity.VerificationCallback {
                        override fun onKeyConfirmed(key: String) {
                            Log.d(TAG, "VerificationCallback: onKeyConfirmed key=$key")
                            activity.lifecycleScope.launch { handleKeyConfirmation(key, dialog, callback) }
                        }

                        override fun onSkip() {
                            Log.d(TAG, "VerificationCallback: onSkip")
                            activity.lifecycleScope.launch { handleSkip(dialog, callback) }
                        }

                        override fun onCompleted() {
                            Log.d(TAG, "VerificationCallback: onCompleted")
                            callback.onCompleted()
                        }
                    }
                }

                dialog.setOnDismissListener {
                    verificationJob.cancel()
                    Log.d(TAG, "Dialog dismissed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception: ${e.message}", e)
                callback.onCompleted()
            }
        }
    }

    // 新增方法：下载完成后更新 UI
    private fun updateUIAfterDownload(userInfo: UserInfo?) {
        val hasValidKey = try {
            userInfo != null && userInfoManager.validateKey(userInfo.userId, remoteUsers) != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate key: ${e.message}", e)
            false
        }

        binding.loadingText1.text = if (hasValidKey) {
            activity.getString(R.string.channel_menu_switch_source)
        } else {
            activity.getString(R.string.enter_test_code)
        }
        binding.validKeyText.let {
            if (hasValidKey && userInfo != null) {
                val maskedKey = "*".repeat(15) + userInfo.userId.takeLast(5)
                it.text = activity.getString(R.string.existing_test_code, maskedKey)
                it.visibility = View.VISIBLE
                Log.d(TAG, "Showing valid key=$maskedKey")
            } else {
                it.visibility = View.GONE
            }
        }
        Log.d(TAG, "UI updated after download: instructionText=${binding.loadingText1.text}")
    }

    internal suspend fun handleKeyConfirmation(key: String, dialog: Dialog, callback: VerificationCallback ) {
        Log.d(TAG, "handleKeyConfirmation: Started for key=$key")
        var mutableRemoteUsers = remoteUsers.toMutableList()
        // Force re-download user info
        try {
            val (newWarnings, newUsers) = userInfoManager.downloadRemoteUserInfo(forceRefresh = true)
            mutableRemoteUsers = newUsers.toMutableList()
            activity.usersInfo = mutableRemoteUsers.map { it.userId }
        } catch (e: Exception) {
            Log.e(TAG, "Force re-download failed: ${e.message}", e)
        }
        val downloadFailed = mutableRemoteUsers.isEmpty()

        val validatedUser = try {
            withContext(Dispatchers.IO) { userInfoManager.validateKey(key, mutableRemoteUsers) }
        } catch (e: Exception) {
            Log.e(TAG, "Validate key failed: ${e.message}", e)
            null
        }
        if (validatedUser != null) {
            withContext(Dispatchers.Main) {
                if (!dialog.isShowing) {
                    Log.w(TAG, "Dialog not showing, attempting to show")
                    try {
                        dialog.show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to show dialog: ${e.message}", e)
                        callback.onCompleted()
                        return@withContext
                    }
                }
                hideInputUI()
                binding.loadingText1.text = activity.getString(R.string.code_valid_updating)
                Log.d(TAG, "Showing download UI")
            }

            val deviceId = userInfoManager.getDeviceId()
            val (isValid, errorMsg) = try {
                withContext(Dispatchers.IO) { userInfoManager.checkBinding(key, deviceId) }
            } catch (e: Exception) {
                Log.e(TAG, "Check binding failed: ${e.message}", e)
                false to activity.getString(R.string.test_code_bind_failed)
            }
            if (!isValid) {
                withContext(Dispatchers.Main) {
                    binding.errorText.text = errorMsg ?: activity.getString(R.string.test_code_bind_failed)
                    binding.errorText.visibility = View.VISIBLE
                }
                return
            }
            val (bindingSuccess, bindingMsg) = try {
                withContext(Dispatchers.IO) { userInfoManager.updateBinding(key, deviceId) }
            } catch (e: Exception) {
                Log.e(TAG, "Update binding failed: ${e.message}", e)
                false to activity.getString(R.string.test_code_bind_failed)
            }
            if (!bindingSuccess) {
                withContext(Dispatchers.Main) {
                    binding.errorText.text = bindingMsg ?: activity.getString(R.string.test_code_bind_failed)
                    binding.errorText.visibility = View.VISIBLE
                }
                return
            }

            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

            viewModel.clearCacheChannels() // 清空 MainViewModel 的 cacheChannels

            // 更新 UserInfo
            val updatedDevices = if (validatedUser.devices.contains(deviceId)) {
                validatedUser.devices
            } else {
                validatedUser.devices.toMutableList().apply { add(deviceId) }
            }
            val updatedUserInfo = UserInfo(
                userId = key,
                userLimitDate = validatedUser.userLimitDate,
                userType = validatedUser.userType,
                vipUserUrl = validatedUser.vipUserUrl,
                maxDevices = validatedUser.maxDevices,
                devices = updatedDevices,
                userUpdateStatus = true,
                updateDate = today
            )
            try {
                withContext(Dispatchers.IO) { userInfoManager.saveUserInfo(updatedUserInfo) }
                Log.d(TAG, "Saved updated userInfo for key=$key")
            } catch (e: Exception) {
                Log.e(TAG, "Save userInfo failed: ${e.message}", e)
            }

            try {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Downloading new source from vipUserUrl=${validatedUser.vipUserUrl}")
                    viewModel.importFromUrl(validatedUser.vipUserUrl, skipHistory = true)
                }
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Download completed, dismissing dialog")
                    dialog.dismiss()
                    callback.onCompleted()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) {
                        Log.w(TAG, "Dialog not showing for error, attempting to show")
                        try {
                            dialog.show()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to show dialog for error: ${e.message}", e)
                        }
                    }
                    showInputUI(activity.getString(R.string.live_source_download_failed))
                    Log.d(TAG, "Showing error UI")
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                binding.errorText.text = if (downloadFailed) activity.getString(R.string.network_poor_retry_skip) else activity.getString(R.string.test_code_invalid)
                binding.errorText.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun handleSkip(dialog: Dialog, callback: MainActivity.VerificationCallback) {
        val userInfo = try {
            withContext(Dispatchers.IO) { userInfoManager.loadUserInfo() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load userInfo: ${e.message}", e)
            null
        }
        val hasValidKey = try {
            userInfo != null && userInfoManager.validateKey(userInfo.userId, remoteUsers) != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate key: ${e.message}", e)
            false
        }
        Log.d(TAG, "handleSkip: Started, hasValidKey=$hasValidKey")
        if (!hasValidKey) {
            val defaultUserInfo = userInfoManager.createDefaultUserInfo().copy(userUpdateStatus = true)
            try {
                withContext(Dispatchers.IO) { userInfoManager.saveUserInfo(defaultUserInfo) }
                Log.d(TAG, "Saved default userInfo")
            } catch (e: Exception) {
                Log.e(TAG, "Save default userInfo failed: ${e.message}", e)
            }
        }
        withContext(Dispatchers.Main) {
            binding.loadingText1.text = activity.getString(R.string.test_code_invalid)
            binding.errorText.visibility = View.GONE
            dialog.dismiss()
            Log.d(TAG, "Dialog dismissed")
        }
        callback.onCompleted()
    }

    @SuppressLint("SetTextI18n")
    private fun setupInputUI(
        userInfo: UserInfo?,
        callback: MainActivity.VerificationCallback,
        context: Context,
        dialog: Dialog // 新增 dialog 参数
    ) {
        val hasValidKey = try {
            userInfo != null && userInfoManager.validateKey(userInfo.userId, remoteUsers) != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate key: ${e.message}", e)
            false
        }

        // Show input UI
        showInputUI(null)

        // Update warning messages
        binding.loadingText1.text = if (hasValidKey) {
            activity.getString(R.string.channel_menu_switch_source)
        } else {
            activity.getString(R.string.enter_test_code)
        }
        binding.loadingText1.visibility = View.VISIBLE
        Log.d(TAG, "instructionText set to: ${binding.loadingText1.text}")
        binding.loadingText2.text = context.getString(R.string.loading_message2)
        binding.loadingText2.visibility = View.VISIBLE
        Log.d(TAG, "warningText set to: ${binding.loadingText2.text}")
        binding.loadingText3.text = context.getString(R.string.loading_message3)
        binding.loadingText3.visibility = View.VISIBLE
        Log.d(TAG, "warningText2 set to: ${binding.loadingText3.text}")
        binding.validKeyText.let {
            if (hasValidKey && userInfo != null) {
                val maskedKey = "*".repeat(15) + userInfo.userId.takeLast(5)
                it.text = activity.getString(R.string.existing_test_code, maskedKey)
                it.visibility = View.VISIBLE
                Log.d(TAG, "Showing valid key=$maskedKey")
            } else {
                it.visibility = View.GONE
            }
        }

        // Input filters
        binding.keyInput.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
            val regex = Regex("[0-9A-Z]*")
            if (source.toString().matches(regex)) null else ""
        }, InputFilter.LengthFilter(20))

        // Focus management
        binding.keyInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.errorText.visibility == View.VISIBLE) {
                binding.errorText.text = ""
                binding.errorText.visibility = View.GONE
            }
            Log.d(TAG, "key_input focus changed, hasFocus=$hasFocus")
        }
        binding.confirmButton.setOnFocusChangeListener { _, hasFocus ->
            Log.d(TAG, "confirm_button focus changed, hasFocus=$hasFocus")
        }
        binding.skipButton.setOnFocusChangeListener { _, hasFocus ->
            Log.d(TAG, "skip_button focus changed, hasFocus=$hasFocus")
        }
        // Key listeners
        binding.keyInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                activity.settingActive() // 新增：按键时重置计时器
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    binding.confirmButton.isFocusable = true
                    binding.confirmButton.isFocusableInTouchMode = true
                    binding.confirmButton.requestFocus()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        binding.confirmButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                activity.settingActive() // 新增：按键时重置计时器
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.keyInput.isFocusable = true
                        binding.keyInput.isFocusableInTouchMode = true
                        binding.keyInput.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.skipButton.isFocusable = true
                        binding.skipButton.isFocusableInTouchMode = true
                        binding.skipButton.requestFocus()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        binding.skipButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                activity.settingActive() // 新增：按键时重置计时器
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    binding.confirmButton.isFocusable = true
                    binding.confirmButton.isFocusableInTouchMode = true
                    binding.confirmButton.requestFocus()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        // Text change listener
        binding.keyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null && s.isNotEmpty()) {
                    binding.keyInput.postDelayed({
                        binding.errorText.text = ""
                        binding.errorText.visibility = View.GONE
                    }, 500)
                    activity.settingActive() // 新增：输入时重置计时器
                }
                Log.d(TAG, "Text changed, input=$s, count=$count")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Button listeners
        binding.confirmButton.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime < DEBOUNCE_INTERVAL) {
                Log.w(TAG, "快速点击被忽略: key=${binding.keyInput.text.toString().trim()}")
                return@setOnClickListener
            }
            lastClickTime = currentTime
            Log.d(TAG, "Confirm button clicked")
            activity.settingActive()

            // 检查测试码输入频率
            val prefs = activity.getSharedPreferences("VerificationPrefs", Context.MODE_PRIVATE)
            val lastInputTime = prefs.getLong("last_key_input_time", 0)
            val restrictInterval = 10 * 60 * 1000 // 10 分钟（毫秒）
            val timeSinceLastInput = currentTime - lastInputTime
            if (lastInputTime > 0 && timeSinceLastInput < restrictInterval) {
                val remainingMinutes = ((restrictInterval - timeSinceLastInput) / 60_000 + 1).toInt()
                binding.errorText.text = activity.getString(R.string.please_wait_minutes, remainingMinutes)
                binding.errorText.visibility = View.VISIBLE
                Log.d(TAG, "Input restricted: wait $remainingMinutes minutes")
                return@setOnClickListener
            }

            try {
                val inputKey = binding.keyInput.text.toString().trim()
                // 使用输入的测试码，或 fallback 到 userInfo.userId
                val key = if (inputKey.isNotEmpty() && inputKey.matches("[0-9A-Z]{1,20}".toRegex())) {
                    inputKey
                } else if (userInfo != null && userInfo.userId.isNotEmpty()) {
                    Log.d(TAG, "No input key, using existing userInfo.userId=${userInfo.userId}")
                    userInfo.userId
                } else {
                    binding.errorText.text = activity.getString(R.string.enter_or_valid_code)
                    binding.errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                // 立即显示验证中提示并隐藏输入 UI
                hideInputUI()
                binding.loadingText1.text = activity.getString(R.string.code_verifying)
                binding.loadingText1.visibility = View.VISIBLE
                triggerConfirm(key, dialog, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Confirm button error: ${e.message}", e)
                binding.errorText.text = activity.getString(R.string.input_process_error)
                binding.errorText.visibility = View.VISIBLE
            }
        }

        binding.skipButton.setOnClickListener {
            Log.d(TAG, "Skip button clicked")
            activity.settingActive() // 新增：点击跳过重置计时器
            triggerSkip(dialog, callback)
        }
    }

    private fun showInputUI(message: String? = null) {
        binding.keyInput.visibility = View.VISIBLE
        binding.buttonContainer.visibility = View.VISIBLE
        if (message != null) {
            binding.errorText.text = message
            binding.errorText.visibility = View.VISIBLE
        } else {
            binding.errorText.text = ""
            binding.errorText.visibility = View.GONE
        }
        binding.loadingText1.text = activity.getString(R.string.enter_test_code)
        binding.loadingText2.text = activity.getString(R.string.no_code_no_full_features)
        binding.loadingText3.text = activity.getString(R.string.loading_live_source)
        ensureFocus()
        Log.d(TAG, "Input UI shown")
    }

    private fun hideInputUI() {
        binding.keyInput.text.clear()
        binding.keyInput.visibility = View.GONE
        binding.buttonContainer.visibility = View.GONE
        binding.errorText.text = ""
        binding.errorText.visibility = View.GONE
        binding.loadingText1.text = activity.getString(R.string.loading_message1)
        binding.loadingText2.text = activity.getString(R.string.loading_message2)
        binding.loadingText3.text = activity.getString(R.string.loading_message3)
        binding.keyInput.clearFocus()
        binding.confirmButton.clearFocus()
        binding.skipButton.clearFocus()
        binding.keyInput.setOnKeyListener(null)
        binding.confirmButton.setOnKeyListener(null)
        binding.skipButton.setOnKeyListener(null)
        binding.keyInput.setOnFocusChangeListener(null)
        binding.confirmButton.setOnFocusChangeListener(null)
        binding.skipButton.setOnFocusChangeListener(null)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Input UI hidden")
    }

    fun triggerConfirm(key: String, dialog: Dialog, callback: MainActivity.VerificationCallback) {
        try {
            activity.lifecycleScope.launch { handleKeyConfirmation(key, dialog, callback) }
        } catch (e: Exception) {
            binding.errorText.text = activity.getString(R.string.input_process_error)
            binding.errorText.visibility = View.VISIBLE
            Log.e(TAG, "Trigger confirm error: ${e.message}", e)
        }
    }

    fun triggerSkip(dialog: Dialog, callback: MainActivity.VerificationCallback) {
        try {
            activity.lifecycleScope.launch { handleSkip(dialog, callback) }
        } catch (e: Exception) {
            binding.errorText.text = activity.getString(R.string.operation_failed_retry)
            binding.errorText.visibility = View.VISIBLE
            Log.e(TAG, "Trigger skip error: ${e.message}", e)
        }
    }

    fun isInputUIVisible(): Boolean {
        val isVisible = binding.keyInput.visibility == View.VISIBLE
        Log.d(TAG, "Input UI visible: $isVisible")
        return isVisible
    }

    private fun ensureFocus() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!binding.keyInput.hasFocus() && !binding.confirmButton.hasFocus() && !binding.skipButton.hasFocus()) {
                binding.keyInput.isFocusable = true
                binding.keyInput.isFocusableInTouchMode = true
                binding.keyInput.requestFocus()
            } else {
                Log.d(TAG, "ensureFocus: focus already set, key_input=${binding.keyInput.hasFocus()}, confirm=${binding.confirmButton.hasFocus()}, skip=${binding.skipButton.hasFocus()}")
            }
        }, 100)
    }

    fun getKeyInputText(): String? {
        return binding.keyInput.text?.toString()
    }

    fun showErrorText(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    fun requestKeyInputFocus() {
        binding.keyInput.isFocusable = true
        binding.keyInput.isFocusableInTouchMode = true
        binding.keyInput.requestFocus()
    }

}