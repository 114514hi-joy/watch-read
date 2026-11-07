package com.fqapp.fqapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// androidx.media:media 1.7.0 把这三个类只发在 android.support.v4.media 下
// （androidx/media/session/ 里只有 MediaButtonReceiver）。写成 androidx.media.session
// 会直接编译不过——别照直觉"修正"回 androidx。
// 它们留在 import 块里会被 ktlint 的 import-ordering 判违规（注释阻住自动修复），
// 所以把说明放在导入之后，导入本身按字典序排：android.* → androidx.* → java.*。

/**
 * 听书 / 听视频（listen mode）的前台保活服务：
 * 1. 提高进程优先级，防 MIUI / HyperOS / ColorOS 等厂商系统冻结后台进程导致音频中断。
 * 2. 常驻媒体通知卡片：展示当前书名/剧名与章节名，提供「上一章/集」、「播放/暂停」、
 *    「下一章/集」、「关闭」控制。
 * 3. 点击通知主体或系统媒体卡片快速拉起应用回到播放页。
 *
 * Note: 通知走 MediaStyle + MediaSessionCompat，不是普通通知——普通通知在 Android 12+
 * 折叠态不显示动作按钮，也不会进系统媒体卡片 / 锁屏媒体控制 / 耳机按键路由。
 *
 * 通知大图与媒体卡封面用当前作品封面（issue #123），对齐官方 `dn2/i.java`
 * （`PlayNotificationImpl`）：官方把 `bookInfo.thumbUrl` 交给
 * `ImageLoaderUtils.fetchBitmap` 下载解码，然后同一张 Bitmap 既写进通知的
 * `setLargeIcon`，也写进媒体会话的 `METADATA_KEY_ALBUM_ART`；拿不到或下载失败
 * 时回退成应用图标。
 */
class ListenKeepAliveService : Service() {

    private var currentTitle: String = ""
    private var currentCoverUrl: String = ""

    /** 通知大图的缓存（同一张封面只下载 + 解码一次）。 */
    private val coverCache = CoverBitmapCache<Bitmap>()

    /** 只是取封面，不阻塞 `onStartCommand`：网络请求不能跑在主线程。 */
    private val coverLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fqapp-listen-cover").apply { isDaemon = true }
    }

    private var currentEpisode: String = ""
    private var isPlaying: Boolean = true
    private var hasPrev: Boolean = true
    private var hasNext: Boolean = true
    private var mediaSession: MediaSessionCompat? = null
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L
    private var speed: Float = 1f

    // 前台服务只提高进程优先级，不阻止 CPU / Wi-Fi 休眠。手表息屏后 Dart
    // isolate 会被挂起，原生 STATE_ENDED 事件与「下一集」定时器都推不动，
    // 亮屏才补跑。播放期间（含换集间隙）必须自己持锁。
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceHandler = Handler(Looper.getMainLooper())
    private var releaseLocksRunnable: Runnable? = null
    private var foregroundStarted = false
    private var lastPayloadSignature: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
        mediaSession?.release()
        mediaSession = null
        releaseLocks()
        coverLoader.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.listen_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.listen_channel_name)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 持有 PARTIAL_WAKE_LOCK + WifiLock。播放中每次 onStartCommand 都会续上；
     * 章末 Dart 上报 playing=false 时不立刻释放，留 [WAKE_LOCK_GRACE_MS] 窗口
     * 给「播完 → 拉下一集流 → 起播」，否则正好卡在换集这一步。
     */
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        releaseLocksRunnable?.let {
            serviceHandler.removeCallbacks(it)
            releaseLocksRunnable = null
        }
        try {
            if (wakeLock == null) {
                val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "fqapp:listen-playback"
                ).apply { setReferenceCounted(false) }
            }
            // 不判断 isHeld：OPPO/ColorOS 的后台省电管控会在不通知客户端的
            // 情况下把锁撤销，此时客户端 isHeld() 仍是 true，判断后就永远不再
            // 申请（实测手表上锁被撤后再没回来）。非引用计数锁重复 acquire
            // 是安全的，每次 playing=true 都向 PowerManagerService 重申一次。
            wakeLock?.acquire()

            if (wifiLock == null) {
                // 用 applicationContext：WifiLock 会持住系统服务，别挂在可能被
                // 销毁的 Service 上下文上。
                val manager = applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = manager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL,
                    "fqapp:listen-playback"
                ).apply { setReferenceCounted(false) }
            }
            // 同上：WifiLock 也可能被系统单方面撤掉。
            wifiLock?.acquire()
        } catch (error: Throwable) {
            Log.w(TAG, "listen wake lock unavailable", error)
        }
    }

    private fun releaseLocks() {
        releaseLocksRunnable?.let {
            serviceHandler.removeCallbacks(it)
            releaseLocksRunnable = null
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (error: Throwable) {
            Log.w(TAG, "listen wake lock release failed", error)
        }
    }

    /** 暂停后延迟释放：给换集间隙留窗口，也避免用户真暂停后一直耗电。 */
    private fun scheduleReleaseLocks() {
        if (wakeLock?.isHeld != true && wifiLock?.isHeld != true) return
        // Dart 暂停期间仍会定时回传状态；已排过就别反复续期，否则锁永远不释放。
        if (releaseLocksRunnable != null) return
        val pending = Runnable {
            releaseLocksRunnable = null
            if (!isPlaying) releaseLocks()
        }
        releaseLocksRunnable = pending
        serviceHandler.postDelayed(pending, WAKE_LOCK_GRACE_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            when (action) {
                ACTION_PLAY_PAUSE -> {
                    NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PLAY_PAUSE)
                    return START_NOT_STICKY
                }

                ACTION_PREV -> {
                    NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PREV)
                    return START_NOT_STICKY
                }

                ACTION_NEXT -> {
                    NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_NEXT)
                    return START_NOT_STICKY
                }

                ACTION_STOP -> {
                    NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_STOP)
                    releaseLocks()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: currentTitle.takeIf { it.isNotBlank() }
            ?: getString(R.string.listen_notification_title)
        val episode = intent?.getStringExtra(EXTRA_EPISODE)
            ?: currentEpisode
        val coverUrl = intent?.getStringExtra(EXTRA_COVER_URL) ?: currentCoverUrl
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, isPlaying) ?: isPlaying
        val prev = intent?.getBooleanExtra(EXTRA_HAS_PREV, hasPrev) ?: hasPrev
        val next = intent?.getBooleanExtra(EXTRA_HAS_NEXT, hasNext) ?: hasNext
        val position = intent?.getLongExtra(EXTRA_POSITION_MS, positionMs) ?: positionMs
        val duration = intent?.getLongExtra(EXTRA_DURATION_MS, durationMs) ?: durationMs
        val rate = intent?.getFloatExtra(EXTRA_SPEED, speed) ?: speed

        currentTitle = title
        currentEpisode = episode
        currentCoverUrl = coverUrl
        isPlaying = playing
        hasPrev = prev
        hasNext = next
        positionMs = position
        durationMs = duration
        speed = rate

        // 播放中持锁；章末 Dart 上报 playing=false 时留宽限期，避免换集间隙
        // CPU 睡过去、「下一集」停在半路。
        if (playing) acquireLocks() else scheduleReleaseLocks()

        // 位置按 2 秒分桶：Dart 定时回真实位置纠正系统外推，内容没变时反复
        // 重建 4 个 PendingIntent + MediaStyle + MediaSession，在手表主线程上
        // 是纯浪费。首次仍必须老老实实 startForeground。
        val signature = listOf(
            title, episode, coverUrl, playing, prev, next, duration, rate, position / 2000L
        ).joinToString("|")
        if (foregroundStarted && signature == lastPayloadSignature) {
            return START_NOT_STICKY
        }
        lastPayloadSignature = signature

        val notification = buildNotification(title, episode, isPlaying, hasPrev, hasNext)
        syncPlaybackState(isPlaying, positionMs, durationMs, speed)
        // 封面是异步的：当前这一张还在路上时先用应用图标顶上，取到之后再刷一遍
        // 通知（官方 `dn2/i.java` 的 `FMPlayerSDK.updateNotification` 是同一做法）。
        ensureCover(coverUrl)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, episode: String, playing: Boolean, hasPrev: Boolean, hasNext: Boolean): Notification {
        val text = episode.ifBlank { getString(R.string.listen_notification_text) }

        val contentPendingIntent = contentIntent()

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPendingIntent)
            .setOngoing(playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 媒体通知样式：折叠态直接露出控制，并进系统媒体卡片/锁屏媒体控制。
            // 普通通知在 Android 12+ 会把动作按钮藏在展开态里，且不进媒体区。
            .setStyle(
                MediaStyle()
                    .setMediaSession(ensureMediaSession().sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        // 大图 = 当前作品封面。没封面（或还没下载完）时不设，系统会用应用
        // 图标补位——这正是 issue #123 里「一大一小两个应用图标」的现状。
        coverCache.value?.let { builder.setLargeIcon(it) }

        // 上一章 / 上一集
        val prevIntent = PendingIntent.getService(
            this,
            REQUEST_PREV,
            Intent(this, ListenKeepAliveService::class.java).apply { action = ACTION_PREV },
            flags
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_previous,
                getString(R.string.listen_prev),
                prevIntent
            ).build()
        )

        // 播放 / 暂停
        val playPauseIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (playing) getString(R.string.listen_pause) else getString(R.string.listen_play)
        val playPauseIntent = PendingIntent.getService(
            this,
            REQUEST_PLAY_PAUSE,
            Intent(this, ListenKeepAliveService::class.java).apply { action = ACTION_PLAY_PAUSE },
            flags
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                playPauseIcon,
                playPauseText,
                playPauseIntent
            ).build()
        )

        // 下一章 / 下一集
        val nextIntent = PendingIntent.getService(
            this,
            REQUEST_NEXT,
            Intent(this, ListenKeepAliveService::class.java).apply { action = ACTION_NEXT },
            flags
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_next,
                getString(R.string.listen_next),
                nextIntent
            ).build()
        )

        // 关闭：暂停并撤掉常驻通知。播放中通知是 ongoing，没有这个按钮用户划不掉它。
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ListenKeepAliveService::class.java).apply { action = ACTION_STOP },
            flags
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.listen_stop),
                stopIntent
            ).build()
        )

        return builder.build()
    }

    /**
     * 取当前封面的 Bitmap。只在 URL 变了的时候真的去下载；同一个 URL 只解码一次。
     *
     * 取到之后刷一次通知与媒体元数据——系统把大图缓存在通知对象里，只 setLargeIcon
     * 而不重新 notify 是不会更新的（官方 `dn2/i.java` 的 `FMPlayerSDK.updateNotification`
     * 就是这一步）。
     */
    private fun ensureCover(url: String) {
        if (!coverCache.select(url)) return
        // 没有封面：`select` 已经把缓存清空了，下一次 buildNotification 自然不
        // 带大图，不会把上一本的封面留在通知上；此刻也不需要提前 notify。
        if (url.isBlank()) return
        coverLoader.execute {
            val bitmap = downloadCover(url)
            // 回到主线程再动通知 / 媒体会话。
            Handler(Looper.getMainLooper()).post {
                // 下载期间又换了书：丢掉这份结果（官方用 URL 比较做同一件事）。
                if (!coverCache.accept(url, bitmap)) return@post
                refreshNotification()
            }
        }
    }

    /** 用当前状态重建并重发通知（通知栏与媒体会话一起刷）。 */
    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(currentTitle, currentEpisode, isPlaying, hasPrev, hasNext)
        )
        syncPlaybackState(isPlaying, positionMs, durationMs, speed)
    }

    /** 下载 + 解码封面。失败一律返回 null，调用方按「没有封面」处理。 */
    private fun downloadCover(url: String): Bitmap? {
        coverCache.value?.let { return it }
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = COVER_TIMEOUT_MS
            connection.readTimeout = COVER_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "fqapp")
            try {
                // 通知大图最大也就 256dp@3x 上下，按 512px 采样解码，别把整张
                // 原图读进内存。
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                connection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
            } finally {
                connection.disconnect()
            }
        } catch (error: IOException) {
            Log.w(TAG, "listen cover download failed", error)
            null
        } catch (error: SecurityException) {
            // 缺少 INTERNET 权限或明文 HTTP 被禁：当作没有封面处理。
            Log.w(TAG, "listen cover blocked", error)
            null
        }
    }

    /** 点击通知卡片 / 系统媒体卡片都拉起主界面。 */
    private fun contentIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知用的媒体会话：系统媒体卡片、锁屏控制与耳机/蓝牙按键都靠它路由。
     * 回调与通知按钮共用 [NativePlayerPlugin.dispatchNotificationAction] 这一条通路，
     * 不另起一套控制逻辑。
     */
    private fun ensureMediaSession(): MediaSessionCompat {
        mediaSession?.let { return it }
        val session = MediaSessionCompat(this, MEDIA_SESSION_TAG)
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PLAY_PAUSE)

            override fun onPause() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PLAY_PAUSE)

            override fun onSkipToPrevious() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_PREV)

            override fun onSkipToNext() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_NEXT)

            override fun onStop() = NativePlayerPlugin.dispatchNotificationAction(ACTION_NAME_STOP)
        })
        session.setSessionActivity(contentIntent())
        session.isActive = true
        mediaSession = session
        return session
    }

    /**
     * 把播放状态与时长告诉系统：系统媒体卡片的进度条与两侧时间就来自这里。
     *
     * [positionMs] + [speed] 交给 `setState` 后由系统**自行外推**（位置 = position +
     * 经过时间 × speed），所以不必按帧刷新，只在播放/暂停、切章、拖动、倍速变化时同步即可。
     * 总时长必须放进 [MediaMetadataCompat]，否则卡片两侧都是 00:00。
     */
    private fun syncPlaybackState(playing: Boolean, positionMs: Long, durationMs: Long, speed: Float) {
        val session = ensureMediaSession()
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentEpisode)
            .putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                durationMs.coerceAtLeast(0L)
            )
        // 系统媒体卡片 / 锁屏的封面来自这里（官方 `w06/a.java` 的
        // `METADATA_KEY_ALBUM_ART`）。通知的 setLargeIcon 与它是同一张 Bitmap。
        coverCache.value?.let {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        session.setMetadata(metadata.build())
        // NOTE: ACTION_SEEK_TO is deliberately NOT advertised. The session
        // previously claimed it without an onSeekTo override, so the system
        // media card offered a seek bar whose drags went nowhere. Real seek
        // forwarding needs a Dart-side receiver first: the method channel
        // only carries a String action and no handler has a 'seek' case, so
        // a Kotlin-side onSeekTo today would be silently dropped. Re-add
        // the flag together with onSeekTo + a Dart seek handler, not alone.
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_STOP
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    positionMs.coerceAtLeast(0L),
                    if (speed > 0f) speed else 1f
                )
                .build()
        )
    }

    companion object {
        const val CHANNEL_ID = "listen_playback"
        const val NOTIFICATION_ID = 47

        const val EXTRA_TITLE = "title"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_HAS_PREV = "hasPrev"
        const val EXTRA_HAS_NEXT = "hasNext"
        const val EXTRA_POSITION_MS = "positionMs"
        const val EXTRA_DURATION_MS = "durationMs"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_COVER_URL = "coverUrl"

        const val ACTION_PLAY_PAUSE = "com.fqapp.fqapp.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.fqapp.fqapp.ACTION_PREV"
        const val ACTION_NEXT = "com.fqapp.fqapp.ACTION_NEXT"
        const val ACTION_STOP = "com.fqapp.fqapp.ACTION_STOP"

        private const val REQUEST_PREV = 101
        private const val REQUEST_PLAY_PAUSE = 102
        private const val REQUEST_NEXT = 103

        private const val REQUEST_STOP = 104

        private const val MEDIA_SESSION_TAG = "fqapp-listen"

        private const val TAG = "fqapp-listen"

        /** 封面下载的建连 / 读超时：后台服务里不能挂住太久。 */
        private const val COVER_TIMEOUT_MS = 8_000

        /**
         * playing=false 之后保留唤醒锁的宽限期。章末到下一集起播之间要拉流、
         * 解密、prepare，手表上慢的时候十几秒也正常；给足 90 秒，用户真暂停
         * 也不会一直耗电。
         */
        private const val WAKE_LOCK_GRACE_MS = 90_000L

        /** Dart 侧 `_handleRemoteAction` 认识的动作名；通知按钮与媒体会话共用一份。 */
        private const val ACTION_NAME_PLAY_PAUSE = "playPause"
        private const val ACTION_NAME_PREV = "prev"
        private const val ACTION_NAME_NEXT = "next"
        private const val ACTION_NAME_STOP = "stop"
    }
}
