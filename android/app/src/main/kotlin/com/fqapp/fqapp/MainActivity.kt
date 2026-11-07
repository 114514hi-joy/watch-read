package com.fqapp.fqapp

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.RenderMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterShellArgs
import io.flutter.plugin.common.MethodChannel
import kotlin.math.roundToInt

class MainActivity : FlutterActivity() {
    // 音量键翻页桥。Dart 侧在阅读页且开启“音量键翻页”时通过 setEnabled(true)
    // 通知原生拦截音量键，原生 dispatchKeyEvent 吃掉按键（系统音量不再变化）并通过
    // volumeKey 回调让 Dart 翻页。该方案在原生层拦截，不受 Flutter 生命周期
    // 过渡事件漏发导致的 HardwareKeyboard 派发丢失影响。
    private var volumeKeyEnabled = false
    private var volumeChannel: MethodChannel? = null

    /**
     * 原生文本输入浮层。窄屏（手表）上 Flutter 自己的 TextField 调不出系统
     * 输入法，改由它承载输入，见 [WatchInputOverlay] 的类注释。
     */
    private var watchInput: WatchInputOverlay? = null

    /**
     * 目标逻辑宽度（dp）。只有比它更**窄**的设备才会被放大画布。
     *
     * 表盘（OPPO Watch 3 Pro）是 378×496px、密度 2.0，逻辑画布只有
     * 189×248dp，而整套界面是按 360dp 排版的，不缩放必然溢出。把密度按
     * 目标宽度重算后画布变成 320×420dp，界面等比缩到 59%。
     *
     * Flutter 的 devicePixelRatio 直接取 Resources 的 DisplayMetrics.density
     * （FlutterView#getDevicePixelRatio），所以 attachBaseContext 里换掉
     * Configuration 的 densityDpi，引擎、布局、手势坐标、系统 inset 会一起
     * 等比缩放；manifest 的 configChanges 已含 density，不会重建 Activity。
     * 手机（≥320dp）走原逻辑，行为不变。
     */
    private val targetWidthDp = 320f

    override fun attachBaseContext(newBase: Context) {
        val metrics = newBase.resources.displayMetrics
        val density = metrics.density
        // 拿不到有效尺寸就原样挂载，缩放是增强而不是前置条件。
        if (density <= 0f || metrics.widthPixels <= 0) {
            Log.i("FqDensity", "skip: metrics unavailable")
            super.attachBaseContext(newBase)
            return
        }
        val currentWidthDp = metrics.widthPixels / density
        // 比目标更宽（手机）不缩：这里只解决「画布太窄装不下」。
        // 判断方向不能反：手表是 189dp < 320dp，正是要放大画布的那一侧。
        if (currentWidthDp >= targetWidthDp) {
            Log.i(
                "FqDensity",
                "skip: widthDp=${currentWidthDp.roundToInt()} >= target=${targetWidthDp.roundToInt()}dp",
            )
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        val originalDpi = config.densityDpi
        config.densityDpi = (originalDpi * currentWidthDp / targetWidthDp).roundToInt()
        Log.i(
            "FqDensity",
            "widthPx=${metrics.widthPixels} widthDp=${currentWidthDp.roundToInt()} " +
                "dpi=$originalDpi -> ${config.densityDpi} target=${targetWidthDp.roundToInt()}dp",
        )
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /**
     * 渲染模式固定为 SurfaceView（引擎默认）。
     *
     * 曾经试过 RenderMode.texture，想绕开这台表上「输入法窗口卡在
     * mDrawState=READY_TO_SHOW / shown=false、永远不出首帧」的问题，
     * 实测无效：切成 TextureView 后窗口状态一模一样，屏幕上仍然没有键盘。
     * 所以那条路证伪，保留 SurfaceView（纹理模式多一次 GPU 拷贝，白搭）。
     *
     * 输入法问题的排查记录见 docs/watch-watch3pro-notes.md：原生 EditText
     * （系统浏览器、番茄小说）都正常，只有 FlutterView 触发。
     */
    override fun getRenderMode(): RenderMode = RenderMode.surface

    override fun getFlutterShellArgs(): FlutterShellArgs = super.getFlutterShellArgs().also {
        // Runs before the engine/FlutterLoader starts; configureFlutterEngine is
        // too late to select the process-wide rendering backend.
        RendererCompatibility.configure(it)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Native ExoPlayer host for DRM short dramas (CENC streaming decrypt).
        flutterEngine.plugins.add(NativePlayerPlugin())
        flutterEngine.plugins.add(ReaderDevicePlugin())
        // 全屏播放用 WindowInsetsController 隐藏系统栏；immersiveSticky 在
        // target SDK 36 的 edge-to-edge 下已不生效（issue #176）。
        flutterEngine.plugins.add(PlayerSystemUiPlugin())
        flutterEngine.plugins.add(SystemTtsPlugin())
        flutterEngine.plugins.add(LocalBookPickerPlugin())
        flutterEngine.plugins.add(LibraryBackupPlugin())
        flutterEngine.plugins.add(FanqieAccountPlugin())
        // 官方系统分享是 Intent.ACTION_SEND；没有可分享的应用时回 false。
        flutterEngine.plugins.add(SharePlugin())
        // 整本 TXT 导出：MediaStore.Downloads 或应用外部目录，见 DownloadsPlugin。
        flutterEngine.plugins.add(DownloadsPlugin())
        // 海信墨水屏整屏刷新（压残影）：反射调用厂商 EPD 接口。
        flutterEngine.plugins.add(EinkRefreshPlugin())
        // 小米澎湃 OS 公平运行内存：收 TRIM/KILL 广播，转发 Dart 处理后回系统。
        flutterEngine.plugins.add(FairMemoryPlugin())

        // 原生文本输入通道：Dart 在窄屏上放弃 Flutter 的 TextField，改调这里
        // 弹出的原生 EditText 浮层（系统输入法只认原生控件）。
        val input = WatchInputOverlay(this)
        watchInput = input
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            WatchInputOverlay.CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "open" -> input.show(
                    call.argument<String>("initial").orEmpty(),
                    call.argument<String>("hint").orEmpty(),
                    result,
                )
                "close" -> {
                    input.finish(null)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // 音量键翻页通道：Dart -> 原生 setEnabled 控制是否拦截；原生 -> Dart
        // volumeKey 回调翻页方向（+1 下一页 / -1 上一页）。
        volumeChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "fqapp/volume-key",
        ).also { ch ->
            ch.setMethodCallHandler { call, result ->
                when (call.method) {
                    "setEnabled" -> {
                        volumeKeyEnabled = call.arguments as? Boolean ?: false
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    /** 原生输入浮层打开时，返回键先关浮层，不要退出页面。 */
    @Deprecated("FlutterActivity 仍走 Activity#onBackPressed；浮层需要优先消费它")
    override fun onBackPressed() {
        if (watchInput?.handleBack() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        watchInput?.dispose()
        watchInput = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (volumeKeyEnabled && event != null) {
            val key = event.keyCode
            if (key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_VOLUME_UP) {
                // 仅在首次按下（非长按重复）触发一次翻页，避免按住连翻。
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val direction = if (key == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
                    volumeChannel?.invokeMethod("volumeKey", direction)
                }
                // 无论按下/抬起都吃掉音量键，系统音量面板不再弹出。
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
