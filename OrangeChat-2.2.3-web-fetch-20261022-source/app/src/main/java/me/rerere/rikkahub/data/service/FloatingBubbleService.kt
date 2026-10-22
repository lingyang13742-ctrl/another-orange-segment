/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.BounceInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil3.SingletonImageLoader
import coil3.target.ImageViewTarget
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Avatar
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

/**
 * 悬浮球服务：主动消息到达时弹出一个 Telegram 风格的圆形悬浮球提醒。
 *
 * - 点击球 → 打开对应会话聊天页（RouteActivity + conversationId extra）→ 球消失。
 * - 长按/拖动 → 改变球的位置，松手贴边。
 * - 最长存活 [MAX_ALIVE_MS] 后自动消失。
 * - 无 overlay 权限时不弹球（由调用方在触发前已做检查，这里也兜底）。
 *
 * Intent extras:
 * - EXTRA_CONVERSATION_ID: String
 * - EXTRA_SENDER_NAME: String
 * - EXTRA_AVATAR: String (Avatar 序列化 JSON)
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubbleService"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_SENDER_NAME = "sender_name"
        const val EXTRA_AVATAR = "avatar_json"

        private const val BUBBLE_SIZE_DP = 56
        private const val RED_DOT_SIZE_DP = 14
        // 点击判定阈值：移动距离小于此值视为点击
        private const val CLICK_THRESHOLD_PX = 16f
        // 最长存活时间，防止球永久挂在那里被遗忘
        private const val MAX_ALIVE_MS = 60_000L
        private const val FOREGROUND_NOTIF_ID = 20003
        // action: 移除当前悬浮球（设置里关闭开关时调用）
        const val ACTION_DISMISS = "me.rerere.rikkahub.DISMISS_BUBBLE"

        fun show(
            context: Context,
            conversationId: String,
            senderName: String,
            avatar: Avatar,
        ) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_SENDER_NAME, senderName)
                putExtra(EXTRA_AVATAR, Json.encodeToString(Avatar.serializer(), avatar))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun dismiss(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_DISMISS
            }
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var shakeAnimator: android.animation.ValueAnimator? = null
    private var popAnimator: android.animation.ObjectAnimator? = null

    private val autoDismissRunnable = Runnable {
        Log.d(TAG, "Auto dismiss after timeout")
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关闭信号：直接移除球并退出
        if (intent?.action == ACTION_DISMISS) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        // 兜底权限检查：无 overlay 权限就不弹球
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission, skip showing bubble")
            stopSelf()
            return START_NOT_STICKY
        }

        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        val senderName = intent?.getStringExtra(EXTRA_SENDER_NAME) ?: "AI"
        val avatarJson = intent?.getStringExtra(EXTRA_AVATAR)
        val avatar = try {
            avatarJson?.let { Json.decodeFromString(Avatar.serializer(), it) } ?: Avatar.Dummy
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode avatar, fallback to Dummy", e)
            Avatar.Dummy
        }

        if (conversationId == null) {
            Log.w(TAG, "No conversationId, skip")
            stopSelf()
            return START_NOT_STICKY
        }

        // 先移除旧的球（同一时间只显示一个）
        removeBubbleInternal()
        showBubble(conversationId, senderName, avatar)

        // 最长存活定时
        handler.removeCallbacks(autoDismissRunnable)
        handler.postDelayed(autoDismissRunnable, MAX_ALIVE_MS)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoDismissRunnable)
        stopAnimations()
        removeBubbleInternal()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("橘瓣正在等待你查看…")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FOREGROUND_NOTIF_ID, notification)
        }
    }

    private fun showBubble(conversationId: String, senderName: String, avatar: Avatar) {
        val sizePx = dp(BUBBLE_SIZE_DP)
        val accentColor = resolveAccentColor()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }

        // 圆形背景
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentColor)
            setStroke(dp(2), Color.WHITE)
        }

        // 头像/文字层
        when (avatar) {
            is Avatar.Image -> {
                val imageView = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = bgDrawable
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                }
                container.addView(imageView, FrameLayout.LayoutParams(sizePx, sizePx))
                loadAvatar(imageView, avatar.url, senderName, accentColor)
            }

            is Avatar.Emoji -> {
                val tv = TextView(this).apply {
                    text = avatar.content
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = bgDrawable
                }
                container.addView(tv, FrameLayout.LayoutParams(sizePx, sizePx))
            }

            is Avatar.Dummy -> {
                val initial = senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
                val tv = TextView(this).apply {
                    text = initial
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = bgDrawable
                }
                container.addView(tv, FrameLayout.LayoutParams(sizePx, sizePx))
            }
        }

        // 未读小红点
        val redDotSize = dp(RED_DOT_SIZE_DP)
        val redDot = View(this).apply {
            background = (
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FF3B30"))
                    setStroke(dp(2), Color.WHITE)
                }
            )
        }
        val redDotLp = FrameLayout.LayoutParams(redDotSize, redDotSize, Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(2)
            bottomMargin = dp(2)
        }
        container.addView(redDot, redDotLp)

        // 阴影
        container.elevation = dp(8).toFloat()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - sizePx - dp(12)
            y = screenHeight() / 3
        }

        setupTouchListener(container, params, conversationId)

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
            stopSelf()
            return
        }

        bubbleView = container
        layoutParams = params

        startAnimations(container)
    }

    private fun loadAvatar(
        imageView: ImageView,
        url: String,
        senderName: String,
        accentColor: Int,
    ) {
        scope.launch {
            runCatching {
                val loader = SingletonImageLoader.get(this@FloatingBubbleService)
                val request = ImageRequest.Builder(this@FloatingBubbleService)
                    .data(url)
                    .target(ImageViewTarget(imageView))
                    .build()
                loader.execute(request)
            }.onFailure {
                Log.w(TAG, "Failed to load avatar image, fallback to initial", it)
                // 回退成首字母
                imageView.setImageDrawable(null)
                val initial = senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
                imageView.setBackgroundColor(accentColor)
                // 用一个 TextView 叠上去显示首字母
                val tv = TextView(this@FloatingBubbleService).apply {
                    text = initial
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                }
                (imageView.parent as? FrameLayout)?.addView(
                    tv,
                    FrameLayout.LayoutParams(dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP))
                )
            }
        }
    }

    /**
     * 拖动 + 点击处理。
     * 按下记录初始坐标；移动实时 updateViewLayout；松手时若位移很小视为点击 → 打开会话 → 关球。
     */
    private fun setupTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        conversationId: String,
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    stopAnimations()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > CLICK_THRESHOLD_PX || abs(dy) > CLICK_THRESHOLD_PX) {
                        isDragging = true
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 点击：打开会话
                        openConversation(conversationId)
                        stopSelf()
                    } else {
                        // 拖动结束：简单贴边 + 恢复抖动
                        snapToEdge(view, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val sizePx = dp(BUBBLE_SIZE_DP)
        val targetX = if (params.x + sizePx / 2 < screenWidth() / 2) {
            dp(12)
        } else {
            screenWidth() - sizePx - dp(12)
        }
        val from = params.x
        val animator = android.animation.ValueAnimator.ofInt(from, targetX).apply {
            duration = 200
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        animator.start()
        // 贴边后恢复抖动
        handler.postDelayed({ bubbleView?.let { startAnimations(it) } }, 220)
    }

    private fun openConversation(conversationId: String) {
        runCatching {
            val intent = Intent(this, RouteActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra("conversationId", conversationId)
            }
            startActivity(intent)
        }.onFailure {
            Log.e(TAG, "Failed to open conversation", it)
        }
    }

    /**
     * 抖动动画：
     * 1. pop：弹出放大再缩回（一次）。
     * 2. shake：循环摇头（sin 波 + 衰减重置），直到被停止。
     */
    private fun startAnimations(view: View) {
        stopAnimations()
        // pop 弹跳
        popAnimator = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            view,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.6f, 1.2f, 1f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.6f, 1.2f, 1f),
        ).apply {
            duration = 350
            interpolator = BounceInterpolator()
            start()
        }
        // 循环摇头
        handler.postDelayed({
            if (bubbleView == null) return@postDelayed
            shakeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                repeatMode = android.animation.ValueAnimator.RESTART
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener {
                    val phase = it.animatedValue as Float
                    // sin 波驱动旋转，每个周期幅度固定（持续提醒）
                    val angle = (sin(phase * 2 * PI) * 8).toFloat()
                    view.rotation = angle
                }
                start()
            }
        }, 400) // 等 pop 结束后再开始摇头
    }

    private fun stopAnimations() {
        shakeAnimator?.cancel()
        shakeAnimator = null
        popAnimator?.cancel()
        popAnimator = null
        bubbleView?.rotation = 0f
    }

    private fun removeBubbleInternal() {
        bubbleView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        bubbleView = null
        layoutParams = null
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            Resources.getSystem().displayMetrics
        ).toInt()

    private fun screenWidth(): Int = Resources.getSystem().displayMetrics.widthPixels
    private fun screenHeight(): Int = Resources.getSystem().displayMetrics.heightPixels

    /**
     * 从当前主题解析强调色作为球背景；解析失败回退到一个固定的橙色调（呼应"橘瓣"品牌）。
     */
    private fun resolveAccentColor(): Int {
        return runCatching {
            val typedValue = TypedValue()
            theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
            if (typedValue.resourceId != 0) {
                ContextCompat.getColor(this, typedValue.resourceId)
            } else {
                typedValue.data
            }
        }.getOrDefault(Color.parseColor("#FF8800"))
    }
}
