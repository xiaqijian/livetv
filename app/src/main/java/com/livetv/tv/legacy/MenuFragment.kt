package com.livetv.tv.legacy

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.livetv.tv.databinding.MenuBinding
import com.livetv.tv.legacy.models.TVList
import com.livetv.tv.legacy.models.TVListModel
import com.livetv.tv.legacy.models.TVModel
import com.livetv.tv.YourTVApplication
import com.livetv.tv.R
import androidx.recyclerview.widget.RecyclerView
import kotlin.text.clear

class MenuFragment : Fragment(), GroupAdapter.ItemListener, ListAdapter.ItemListener {
    private var _binding: MenuBinding? = null
    private val binding get() = _binding!!

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var listAdapter: ListAdapter

    private var groupWidth = 0
    private var listWidth = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val application = context.applicationContext as YourTVApplication
        _binding = MenuBinding.inflate(inflater, container, false)

        // 设置根视图可聚焦
        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true

        groupAdapter = GroupAdapter(
            context,
            binding.group,
            TVList.groupModel,
        )
        binding.group.adapter = groupAdapter
        binding.group.layoutManager = LinearLayoutManager(context)
        groupWidth = application.px2Px(binding.group.layoutParams.width)
        binding.group.layoutParams.width = if (SP.compactMenu) {
            groupWidth * 2 / 3
        } else {
            groupWidth
        }
        groupAdapter.setItemListener(this)
        // 确保 RecyclerView 可聚焦
        binding.group.isFocusable = true
        binding.group.isFocusableInTouchMode = true

        var tvListModel = TVList.groupModel.getTVListModel(TVList.groupModel.position.value!!)
        if (tvListModel == null) {
            TVList.groupModel.setPosition(0)
        }

        tvListModel = TVList.groupModel.getTVListModel(TVList.groupModel.position.value!!)

        listAdapter = ListAdapter(
            requireContext(),
            binding.list,
            tvListModel!!,
        )
        binding.list.adapter = listAdapter
        binding.list.layoutManager = LinearLayoutManager(context)
        listWidth = application.px2Px(binding.list.layoutParams.width)
        binding.list.layoutParams.width = if (SP.compactMenu) {
            listWidth * 4 / 5
        } else {
            listWidth
        }
        listAdapter.focusable(false)
        listAdapter.setItemListener(this)
        // 确保 RecyclerView 可聚焦
        binding.list.isFocusable = true
        binding.list.isFocusableInTouchMode = true

        binding.menu.setOnClickListener {
            hideSelf()
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val mainActivity = activity as? MainActivity
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING, RecyclerView.SCROLL_STATE_IDLE -> {
                        mainActivity?.menuActive()
                    }
                }
            }
        }
        // 添加触摸监听，重置计时器
        val touchListener = View.OnTouchListener { _, _ ->
            (activity as? MainActivity)?.menuActive()
            false
        }
        binding.group.addOnScrollListener(scrollListener)
        binding.list.addOnScrollListener(scrollListener)

        // 简化按键监听，仅处理关闭菜单
        binding.menu.setOnClickListener {
            hideSelf()
        }

        view.post {
            binding.list.isFocusable = true
            binding.list.isFocusableInTouchMode = true
            if (listAdapter.itemCount > 0) {
                listAdapter.focusable(true)
                groupAdapter.focusable(false)
                val success = binding.list.requestFocus()
                if (success) {
                    val position = TVList.getTVModel()?.listIndex ?: 0
                    listAdapter.toPosition(position)
                    Log.d(TAG, "Focus requested on list at position $position")
                } else {
                    Log.w(TAG, "Failed to focus list RecyclerView, falling back to group")
                    binding.group.isFocusable = true
                    binding.group.isFocusableInTouchMode = true
                    groupAdapter.focusable(true)
                    listAdapter.focusable(false)
                    val groupSuccess = binding.group.requestFocus()
                    if (groupSuccess) {
                        val groupPosition = TVList.groupModel.position.value!!
                        groupAdapter.toPosition(groupPosition)
                        Log.d(TAG, "Focus requested on group at position $groupPosition")
                    } else {
                        Log.w(TAG, "Failed to focus group RecyclerView")
                        binding.root.requestFocus() // 回退到根视图
                    }
                }
            } else {
                binding.group.isFocusable = true
                binding.group.isFocusableInTouchMode = true
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                val success = binding.group.requestFocus()
                if (success) {
                    val groupPosition = TVList.groupModel.position.value!!
                    groupAdapter.toPosition(groupPosition)
                    Log.d(TAG, "Focus requested on group at position $groupPosition")
                } else {
                    Log.w(TAG, "Failed to focus group RecyclerView")
                    binding.root.requestFocus() // 回退到根视图
                }
            }
        }
    }

    override fun onKey(keyCode: Int): Boolean {
        (activity as? MainActivity)?.menuActive() // 重置计时器
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.group.findFocus() != null) { // 焦点在 group 或其子项
                    if (listAdapter.itemCount == 0) {
                        Toast.makeText(context, getString(R.string.no_channels), Toast.LENGTH_LONG).show()
                        return true
                    }
                    groupAdapter.focusable(false)
                    listAdapter.focusable(true)
                    binding.list.isFocusable = true
                    binding.list.isFocusableInTouchMode = true
                    val success = binding.list.requestFocus()
                    if (success) {
                        val position = TVList.getTVModel()?.listIndex ?: 0
                        listAdapter.toPosition(position)
                        Log.d(TAG, "Focus moved to list RecyclerView")
                    } else {
                        Log.w(TAG, "Failed to focus list RecyclerView")
                        binding.root.requestFocus()
                    }
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                hideSelf()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                hideSelf()
                return true
            }
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        // 移除重复焦点设置
    }

    fun update() {
        groupAdapter.update(TVList.groupModel)

        var tvListModel = TVList.groupModel.getTVListModel(TVList.groupModel.position.value!!)
        if (tvListModel == null) {
            TVList.groupModel.setPosition(0)
        }
        tvListModel = TVList.groupModel.getTVListModel(TVList.groupModel.position.value!!)

        if (tvListModel != null) {
            (binding.list.adapter as ListAdapter).update(tvListModel)
        }
    }

    fun updateList(position: Int) {
        TVList.groupModel.setPosition(position)
        SP.positionGroup = position
        val tvListModel = TVList.groupModel.getTVListModel()
        Log.i(TAG, "updateList tvListModel $position ${tvListModel?.size()}")
        if (tvListModel != null) {
            (binding.list.adapter as ListAdapter).update(tvListModel)
        }
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commit()
    }

    override fun onItemFocusChange(tvListModel: TVListModel, hasFocus: Boolean) {
        if (hasFocus) {
            (binding.list.adapter as ListAdapter).update(tvListModel)
            (activity as MainActivity).menuActive()
        }
    }

    override fun onItemClicked(position: Int) {
        listAdapter.clear() // 清除 list 焦点
        groupAdapter.focusable(true)
        listAdapter.focusable(false)
        groupAdapter.toPosition(position) // 聚焦 group 子项
        TVList.groupModel.setPosition(position)
        (activity as? MainActivity)?.menuActive() // 重置计时器
        Log.d(TAG, "Group item clicked, focused at position $position")
    }

    override fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean) {
        if (hasFocus) {
            (activity as MainActivity).menuActive()
        }
    }

    override fun onItemClicked(tvModel: TVModel) {
        TVList.setPosition(tvModel.tv.id)
        (activity as? MainActivity)?.menuActive() // 重置计时器
        (activity as MainActivity).hideMenuFragment()
    }

    override fun onKey(listAdapter: ListAdapter, keyCode: Int): Boolean {
        (activity as? MainActivity)?.menuActive() // 重置计时器
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                binding.group.visibility = VISIBLE
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                listAdapter.clear()
                Log.i(TAG, "group toPosition on left")
                groupAdapter.toPosition(TVList.groupModel.position.value!!)
                return true
            }
//            KeyEvent.KEYCODE_DPAD_RIGHT -> {
//                binding.group.visibility = VISIBLE
//                groupAdapter.focusable(true)
//                listAdapter.focusable(false)
//                listAdapter.clear()
//                Log.i(TAG, "group toPosition on left")
//                groupAdapter.toPosition(TVList.groupModel.position.value!!)
//                return true
//            }
        }
        return false
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.post {
                binding.list.isFocusable = true
                binding.list.isFocusableInTouchMode = true
                if (listAdapter.itemCount > 0) {
                    listAdapter.focusable(true)
                    groupAdapter.focusable(false)
                    binding.list.requestFocus()
                    val position = TVList.getTVModel()?.listIndex ?: 0
                    listAdapter.toPosition(position)
                    Log.d(TAG, "onHiddenChanged: Focus requested on list at position $position")
                } else {
                    binding.group.isFocusable = true
                    binding.group.isFocusableInTouchMode = true
                    groupAdapter.focusable(true)
                    listAdapter.focusable(false)
                    binding.group.requestFocus()
                    val groupPosition = TVList.groupModel.position.value!!
                    groupAdapter.toPosition(groupPosition)
                    Log.d(TAG, "onHiddenChanged: Focus requested on group at position $groupPosition")
                }
                (activity as MainActivity).menuActive()
            }
        } else {
            view?.post {
                groupAdapter.visiable = false
                listAdapter.visiable = false
            }
        }
    }

    fun updateSize() {
        view?.post {
            binding.group.layoutParams.width = if (SP.compactMenu) {
                groupWidth * 4 / 5
            } else {
                groupWidth
            }
            binding.list.layoutParams.width = if (SP.compactMenu) {
                listWidth * 4 / 5
            } else {
                listWidth
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 移除重复焦点设置
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "MenuFragment"
    }
}