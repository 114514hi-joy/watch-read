package com.fqapp.fqapp

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.flutter.plugin.common.MethodChannel

/**
 * 全屏原生文本输入浮层。
 *
 * 为什么不用 Flutter 的 TextField：这台表（OPPO Watch 3 Pro / ColorOS Watch 5）
 * 上，FlutterView 申请输入法后系统始终不显示键盘——IME 窗口建好了、
 * `mInputShown=true`，但 `mDrawState` 卡在 READY_TO_SHOW、shown=false，永远不出
 * 首帧（排查记录见 docs 里的会话笔记）。原生 EditText 不受影响：同一套搜狗
 * 输入法在系统浏览器、番茄小说里都能正常弹出。
 *
 * 所以这里把输入交给一个真正的 EditText：焦点、输入连接、输入法全走系统原生
 * 路径；`showSoftInput(..., SHOW_FORCED)` 是必需的，普通请求在这台表上会被忽略。
 *
 * 布局上有一条硬约束：**按钮必须和输入框在同一行**。搜狗手表输入法是全屏形态，
 * 一弹出系统就会把窗口内容整体上推、只留输入框所在的那一条可见；放在输入框
 * 上方或下方的控件都会被推到屏幕外，用户根本按不到（实测过）。
 */
class WatchInputOverlay(private val activity: Activity) {

    private var overlay: ViewGroup? = null
    private var edit: EditText? = null
    private var pending: MethodChannel.Result? = null

    private val density: Float
        get() = activity.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    val isShowing: Boolean
        get() = overlay != null

    fun show(initialText: String, hintText: String, result: MethodChannel.Result) {
        if (overlay != null) {
            result.error("busy", "原生输入浮层已经打开", null)
            return
        }
        pending = result

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val container = FrameLayout(activity).apply {
            setBackgroundColor(0xF2000000.toInt())
            isClickable = true
        }

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val field = EditText(activity).apply {
            setText(initialText)
            setSelection(text.length)
            setHint(if (hintText.isEmpty()) null else hintText)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8E8A85.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(0xFF2C2C2C.toInt())
                setStroke(dp(1), 0xFF3C3C3C.toInt())
            }
        }

        // 一行搞定：输入框 + 取消 + 确定，三者同生共死（见类注释）。
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(field, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))
        row.addView(button("取消", primary = false) { finish(null) })
        row.addView(button("确定", primary = true) { finish(field.text?.toString().orEmpty()) })

        column.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        container.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ))

        // 输入法动作栏的「完成」等价于点确定，省得在小屏上来回移动手指。
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                finish(field.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        overlay = container
        edit = field
        root.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        field.post {
            field.requestFocus()
            val imm = activity.getSystemService(InputMethodManager::class.java)
            // SHOW_FORCED 是必需的：这台表忽略普通 showSoftInput 请求。
            imm?.showSoftInput(field, InputMethodManager.SHOW_FORCED)
        }
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (primary) Color.WHITE else 0xFFB7B7B7.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (primary) 0xFFEF5038.toInt() else 0x00000000)
            }
            setOnClickListener { onClick() }
        }

    /** 关闭浮层并回传文本；[text] 为 null 表示用户取消。 */
    fun finish(text: String?) {
        val result = pending
        pending = null
        val imm = activity.getSystemService(InputMethodManager::class.java)
        edit?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        overlay = null
        edit = null
        result?.success(text)
    }

    /** Activity 销毁时兜底，避免 Result 悬空。 */
    fun dispose() {
        pending = null
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        overlay = null
        edit = null
    }

    /** 给返回键用：浮层打开时先关浮层。 */
    fun handleBack(): Boolean {
        if (overlay == null) return false
        finish(null)
        return true
    }

    companion object {
        const val CHANNEL = "fqapp/native_input"
    }
}
