package com.livetv.tv.legacy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.livetv.tv.legacy.models.TVList
import com.livetv.tv.R
import com.livetv.tv.legacy.SimpleServer.Companion.PORT
import com.livetv.tv.legacy.ModalFragment.Companion.KEY_URL
import com.livetv.tv.YourTVApplication
import com.livetv.tv.databinding.SettingMytv1Binding
import com.livetv.tv.UpdateManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import androidx.appcompat.widget.SwitchCompat
import android.os.Handler
import android.os.Looper
import com.livetv.tv.R.string

@Suppress("DEPRECATION")
class SettingFragment : Fragment() {

    private var _binding: SettingMytv1Binding? = null
    private val binding get() = _binding!!

    private lateinit var uri: Uri
    private lateinit var updateManager: UpdateManager
    private var server = "http://${PortUtil.lan()}:$PORT"
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SettingMytv1Binding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireActivity()
        val mainActivity = (activity as MainActivity)
        val application = context.applicationContext as YourTVApplication

        // Initialize controls
        binding.versionName.text = "v${context.appVersionName}"
        binding.version.text = requireContext().getString(R.string.project_address)
        binding.version.isFocusable = true
        binding.version.isFocusableInTouchMode = true
        binding.version.setOnClickListener {
            val url = requireContext().getString(R.string.project_github_address)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply { addCategory(Intent.CATEGORY_BROWSABLE) })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open URL: $url", e)
                string.no_browser_found.showToast()
                binding.version.requestFocus()
            }
        }
        binding.version.setOnFocusChangeListener { _, hasFocus ->
            binding.version.background = ContextCompat.getColor(context, if (hasFocus) R.color.focus else R.color.description_blur).toDrawable()
            binding.version.setTextColor(ContextCompat.getColor(context, if (hasFocus) R.color.white else R.color.blur))
        }

        // Setup switches
        setupSwitch(binding.switchChannelReversal, SP.channelReversal) { isChecked ->
            SP.channelReversal = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchChannelNum, SP.channelNum) { isChecked ->
            SP.channelNum = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchTime, SP.time) { isChecked ->
            SP.time = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchBootStartup, SP.bootStartup) { isChecked ->
            SP.bootStartup = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchConfigAutoLoad, SP.configAutoLoad) { isChecked ->
            SP.configAutoLoad = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchCompactMenu, SP.compactMenu) { isChecked ->
            SP.compactMenu = isChecked
            mainActivity.updateMenuSize()
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchDisplaySeconds, SP.displaySeconds) { isChecked ->
            SP.displaySeconds = isChecked
            mainActivity.settingActive()
        }
        setupSwitch(binding.switchEnableWebviewType, !com.livetv.tv.SP.enableWebviewType) { isChecked ->
            com.livetv.tv.SP.enableWebviewType = !isChecked
            mainActivity.handleWebviewTypeSwitch(isChecked)
            mainActivity.settingActive()
        }

        setupSwitch(binding.switchFullScreenMode, com.livetv.tv.SP.fullScreenMode) { isChecked ->
            com.livetv.tv.SP.fullScreenMode = isChecked
            mainActivity.updateFullScreenMode(isChecked)
            (context.applicationContext as YourTVApplication).toggleFullScreenMode(isChecked)
            mainActivity.settingActive()
        }

        // 设置 switchWebviewType 的初始状态
        updateWebviewTypeSwitch()

        binding.switchDisplaySeconds.isChecked = SP.displaySeconds
        binding.switchDisplaySeconds.isFocusable = true
        binding.switchDisplaySeconds.isFocusableInTouchMode = true

        binding.remoteSettings.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putString(KEY_URL, server)
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.checkVersion.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    startActivityForResult(intent, REQUEST_UNKNOWN_APP_SOURCES)
                    Toast.makeText(context, string.enable_unknown_sources, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open unknown sources settings: ${e.message}")
                    Toast.makeText(context, string.install_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    updateManager.checkAndUpdate(isAutoCheck = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check update: ${e.message}")
                    Toast.makeText(context, string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.checkVersion.setOnLongClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    startActivityForResult(intent, REQUEST_UNKNOWN_APP_SOURCES)
                    Toast.makeText(context, string.enable_unknown_sources, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open unknown sources settings: ${e.message}")
                    Toast.makeText(context, string.install_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    updateManager.checkAndUpdate(isAutoCheck = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check update: ${e.message}")
                    Toast.makeText(context, string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
            true // 消费长按事件，阻止 MenuFragment
        }

        binding.confirmConfig.setOnClickListener {
            var url = SP.configUrl!!
            url = Utils.formatUrl(url)
            uri = Uri.parse(url)
            if (uri.scheme == "") {
                uri = uri.buildUpon().scheme("http").build()
            }
            Log.i(TAG, "Uri $uri")
            if (uri.isAbsolute) {
                Log.i(TAG, "Uri ok")
                if (uri.scheme == "file") {
                    requestReadPermissions()
                } else {
                    TVList.parseUri(uri)
                }
            }
            mainActivity.settingActive()
        }
        binding.appreciate.setOnClickListener {
            val imageModalFragment = ModalFragment()

            val args = Bundle()
            args.putInt(ModalFragment.KEY_DRAWABLE_ID, R.drawable.appreciate)
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.setting.setOnClickListener {
            hideSelf()
            (activity as? MainActivity)?.settingActive()
        }
        0
        // 修改退出按钮为X5管理
        binding.exit.setOnClickListener {
            val isX5Available = YourTVApplication.getInstance().isX5Available()
            Log.d(TAG, "exit button: isX5Available=$isX5Available, useX5WebView=${SP.useX5WebView}")
            if (!isX5Available) {
                // 强制关闭 X5 模式，与 switchWebviewType 保持一致
                SP.useX5WebView = false
                binding.switchWebviewType.isChecked = false
                try {
                    val intent = Intent(context, TbsDebugActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    Toast.makeText(context, getString(R.string.x5_not_installed), Toast.LENGTH_LONG).show()
                    startActivity(intent)
                    Log.d(TAG, "Started TbsDebugActivity")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start TbsDebugActivity: ${e.message}", e)
                    Toast.makeText(context, R.string.x5_install_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                // X5 可用，确保开关状态与 SP.useX5WebView 一致
                binding.switchWebviewType.isChecked = SP.useX5WebView
                Toast.makeText(context, R.string.x5_already_initialized, Toast.LENGTH_SHORT).show()
            }
        }

        binding.clear.setOnClickListener { showClearDialog() }

        // UI styling (aligned with yourtv)
        val txtTextSize = application.px2PxFont(binding.versionName.textSize)
        binding.content.layoutParams.width = application.px2Px(binding.content.layoutParams.width)
        binding.content.setPadding(
            application.px2Px(binding.content.paddingLeft),
            application.px2Px(binding.content.paddingTop),
            application.px2Px(binding.content.paddingRight),
            application.px2Px(binding.content.paddingBottom)
        )
        binding.version.textSize = txtTextSize
        val layoutParamsVersion = binding.version.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsVersion.topMargin = application.px2Px(binding.version.marginTop)
        layoutParamsVersion.bottomMargin = application.px2Px(binding.version.marginBottom)
        binding.version.layoutParams = layoutParamsVersion
        binding.versionName.textSize = txtTextSize

        // 设置按钮样式
        val btnWidth = application.px2Px(binding.confirmConfig.layoutParams.width)
        val btnLayoutParams = binding.remoteSettings.layoutParams as ViewGroup.MarginLayoutParams
        btnLayoutParams.marginEnd = application.px2Px(binding.remoteSettings.marginEnd)

        for (i in listOf(
            binding.remoteSettings,
            binding.confirmConfig,
            binding.clear,
            binding.checkVersion,
            binding.exit,
            binding.appreciate
        )) {
            i.layoutParams.width = btnWidth
            i.textSize = txtTextSize
            i.layoutParams = btnLayoutParams
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    //i.background = ContextCompat.getColor(context, R.color.focus).toDrawable()
                    i.setTextColor(ContextCompat.getColor(context, R.color.white))
                } else {
                    i.setTextColor(ContextCompat.getColor(context, R.color.blur))
                }
            }
        }

        // 设置开关样式
        val textSizeSwitch = application.px2PxFont(binding.switchChannelReversal.textSize)
        val layoutParamsSwitch = binding.switchChannelReversal.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsSwitch.topMargin = application.px2Px(binding.switchChannelReversal.marginTop)

        for (i in listOf(
            binding.switchEnableWebviewType,
            binding.switchChannelReversal,
            binding.switchChannelNum,
            binding.switchTime,
            binding.switchBootStartup,
            binding.switchConfigAutoLoad,
            binding.switchCompactMenu,
            binding.switchDisplaySeconds,
            binding.switchWebviewType,
            binding.switchFullScreenMode,
        )) {
            i.textSize = textSizeSwitch
            i.layoutParams = layoutParamsSwitch
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    i.setTextColor(ContextCompat.getColor(context, R.color.focus))
                } else {
                    i.setTextColor(ContextCompat.getColor(context, R.color.title_blur))
                }
            }
        }

        // Initialize UpdateManager
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
        updateManager = UpdateManager(requireActivity(), versionCode) // 使用 requireActivity()

        // Focus management
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // 确保 name 可聚焦并添加焦点监听
        binding.name.isFocusable = true
        binding.name.isFocusableInTouchMode = true
        binding.name.setOnFocusChangeListener { _, hasFocus ->
            binding.name.setTextColor(ContextCompat.getColor(context, if (hasFocus) R.color.focus else R.color.title_blur))
            Log.d(TAG, "name focus changed: hasFocus=$hasFocus")
        }

        view.post {
            binding.remoteSettings.isFocusable = true
            binding.remoteSettings.isFocusableInTouchMode = true
            binding.remoteSettings.requestFocus()
            binding.remoteSettings.onFocusChangeListener?.onFocusChange(binding.remoteSettings, true)
            Log.d(TAG, "Focus requested on remoteSettings")
        }

        // 添加触摸监听
        binding.root.setOnTouchListener { _, _ ->
            (activity as? MainActivity)?.settingActive()
            false
        }

        // Key listener
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                (activity as? MainActivity)?.settingActive()
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        when (view.findFocus()?.id) {
                            R.id.switchEnableWebviewType -> binding.switchEnableWebviewType.toggle()
                            R.id.remote_settings -> binding.remoteSettings.performClick()
                            R.id.confirm_config -> binding.confirmConfig.performClick()
                            R.id.clear -> binding.clear.performClick()
                            R.id.check_version -> binding.checkVersion.performClick()
                            R.id.exit -> binding.exit.performClick()
                            R.id.appreciate -> binding.appreciate.performClick()
                            R.id.setting -> binding.setting.performClick()
                            R.id.switch_channel_reversal -> binding.switchChannelReversal.toggle()
                            R.id.switch_channel_num -> binding.switchChannelNum.toggle()
                            R.id.switch_time -> binding.switchTime.toggle()
                            R.id.switch_boot_startup -> binding.switchBootStartup.toggle()
                            R.id.switch_config_auto_load -> binding.switchConfigAutoLoad.toggle()
                            R.id.switch_compact_menu -> binding.switchCompactMenu.toggle()
                            R.id.switch_display_seconds -> binding.switchDisplaySeconds.toggle()
                            R.id.switch_webview_type -> binding.switchWebviewType.toggle()
                            R.id.switch_full_screen_mode -> binding.switchFullScreenMode.toggle()
                        }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        binding.setting.performClick()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        mainActivity.ready(TAG)
    }

    private fun updateWebviewTypeSwitch() {
        val isX5Available = YourTVApplication.getInstance().isX5Available()
        Log.d(TAG, "updateWebviewTypeSwitch: isX5Available=$isX5Available, useX5WebView=${SP.useX5WebView}")

        if (!isX5Available) {
            binding.switchWebviewType.visibility = View.GONE
            binding.switchWebviewType.isFocusable = false
            binding.switchWebviewType.isFocusableInTouchMode = false
            SP.useX5WebView = false
        } else {
            binding.switchWebviewType.visibility = View.VISIBLE
            binding.switchWebviewType.isChecked = SP.useX5WebView
            binding.switchWebviewType.isFocusable = true
            binding.switchWebviewType.isFocusableInTouchMode = true
            setupSwitch(binding.switchWebviewType, SP.useX5WebView) { isChecked ->
                Log.d(TAG, "switchWebviewType toggled: isChecked=$isChecked")
                SP.useX5WebView = isChecked
                if (SP.useX5WebView != isChecked) {
                    Log.e(TAG, "SP.useX5WebView save failed: expected $isChecked, got ${SP.useX5WebView}")
                    Toast.makeText(requireContext(), getString(R.string.save_settings_failed), Toast.LENGTH_SHORT).show()
                    binding.switchWebviewType.isChecked = !isChecked
                    return@setupSwitch
                }

                Toast.makeText(requireContext(), getString(R.string.webview_switched), Toast.LENGTH_LONG).show()
                // 清理 WebView 缓存
                if (isChecked) {
                    com.tencent.smtt.sdk.WebStorage.getInstance().deleteAllData()
                    com.tencent.smtt.sdk.CookieManager.getInstance().removeAllCookies(null)
                } else {
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                }

                // 延迟重启
                handler.postDelayed({
                    val restartIntent = Intent(requireContext(), com.livetv.tv.legacy.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    requireContext().startActivity(restartIntent)
                    requireActivity().finish()
                }, 500L)
                (activity as? MainActivity)?.settingActive()
            }
        }
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
        (activity as MainActivity).addTimeFragment()
    }

    private fun setupSwitch(switch: SwitchCompat, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        switch.isChecked = checked
        switch.setOnCheckedChangeListener { _, isChecked -> onCheckedChange(isChecked) }
        switch.isFocusable = true
        switch.isFocusableInTouchMode = true
    }

    private fun showClearDialog() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.confirm_reset))
            .setPositiveButton(R.string.confirm) { _, _ ->
                SP.configUrl = ""
                SP.channel = 0
                requireContext().deleteFile(TVList.CACHE_FILE_NAME)
                SP.deleteLike()
                SP.position = 0
                TVList.setPosition(0)
                TVList.setDisplaySeconds(SP.DEFAULT_DISPLAY_SECONDS)
                (activity as? MainActivity)?.settingActive()
                Log.d(TAG, "Settings cleared")
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                (activity as? MainActivity)?.settingActive()
                Log.d(TAG, "Clear settings cancelled")
            }
            .setCancelable(true)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#33000000")))
            val displayMetrics = resources.displayMetrics
            dialog.window?.setLayout((displayMetrics.widthPixels * 0.30).toInt(), (displayMetrics.heightPixels * 0.25).toInt())

            val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)

            positiveButton?.setOnFocusChangeListener { _, hasFocus ->
                positiveButton.setTextColor(ContextCompat.getColor(requireContext(), if (hasFocus) R.color.focus else R.color.blur))
                negativeButton?.setTextColor(ContextCompat.getColor(requireContext(), if (hasFocus) R.color.blur else R.color.focus))
            }
            negativeButton?.setOnFocusChangeListener { _, hasFocus ->
                negativeButton.setTextColor(ContextCompat.getColor(requireContext(), if (hasFocus) R.color.focus else R.color.blur))
                positiveButton?.setTextColor(ContextCompat.getColor(requireContext(), if (hasFocus) R.color.blur else R.color.focus))
            }

            positiveButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.focus))
            positiveButton?.requestFocus()
            negativeButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.blur))
        }
        dialog.show()
        (activity as? MainActivity)?.settingActive()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 初始化 switchWebviewType
            updateWebviewTypeSwitch()

            view?.post {
                binding.remoteSettings.isFocusable = true
                binding.remoteSettings.isFocusableInTouchMode = true
                binding.remoteSettings.requestFocus()
                binding.remoteSettings.onFocusChangeListener?.onFocusChange(binding.remoteSettings, true)
                Log.d(TAG, "onHiddenChanged: Focus requested on remoteSettings")
            }
        }
    }

    private fun checkAndAddPermission(context: Context, permission: String, permissionsList: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission)
        }
    }

    private fun requestInstallPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionsList.add(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }
        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)
        checkAndAddPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE, permissionsList)
        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsList.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        } else {
            updateManager.checkAndUpdate(isAutoCheck = false)
        }
    }

    private fun requestReadPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()
        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)
        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsList.toTypedArray(), PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE)
        } else {
            TVList.parseUri(uri)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                TVList.parseUri(uri)
            } else {
                R.string.permission_failed.showToast(Toast.LENGTH_LONG)
            }
        }
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }
            if (allPermissionsGranted) {
                updateManager.checkAndUpdate(isAutoCheck = false)
            } else {
                R.string.permission_failed.showToast(Toast.LENGTH_LONG)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.postDelayed({
            updateManager.destroy()
            Log.d(TAG, "UpdateManager destroyed after delay")
        }, 5000)
        _binding = null
    }

    private fun String.showToast(duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(requireContext(), this, duration).show()
    }

    private fun Int.showToast() {
        Toast.makeText(requireContext(), this, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "SettingFragment"
        const val PERMISSIONS_REQUEST_CODE = 1
        const val PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE = 2
        const val REQUEST_UNKNOWN_APP_SOURCES = 3
    }
}


