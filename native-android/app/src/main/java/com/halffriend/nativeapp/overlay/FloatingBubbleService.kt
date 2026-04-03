package com.halffriend.nativeapp.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.halffriend.nativeapp.MainActivity
import com.halffriend.nativeapp.R
import com.halffriend.nativeapp.assistant.AssistantCommandParser
import com.halffriend.nativeapp.assistant.AssistantActionExecutor
import com.halffriend.nativeapp.assistant.AssistantExecutionPipeline
import com.halffriend.nativeapp.assistant.ChatHistoryStore
import com.halffriend.nativeapp.assistant.DeviceCapabilitySnapshot
import com.halffriend.nativeapp.assistant.HalfFriendMemorySnapshot
import com.halffriend.nativeapp.assistant.HalfFriendSkillRegistry
import com.halffriend.nativeapp.assistant.HalfFriendSkillSessionStore
import com.halffriend.nativeapp.assistant.HalfFriendMemoryStore
import com.halffriend.nativeapp.assistant.HalfFriendSuggestionEngine
import com.halffriend.nativeapp.assistant.OpenAiService
import com.halffriend.nativeapp.assistant.PermissionManager
import com.halffriend.nativeapp.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingBubbleService : Service() {
    private enum class BubbleMode { QUIET, STANDBY, ACTIVE }

    private val parser by lazy { AssistantCommandParser() }
    private val executor by lazy { AssistantActionExecutor(applicationContext) }
    private val permissionManager by lazy { PermissionManager(applicationContext) }
    private val openAiService by lazy { OpenAiService() }
    private val executionPipeline by lazy {
        AssistantExecutionPipeline(
            parser = parser,
            permissionManager = permissionManager,
            executor = executor,
            openAiService = openAiService
        )
    }
    private val skillRegistry by lazy { HalfFriendSkillRegistry() }
    private val skillSessionStore by lazy { HalfFriendSkillSessionStore(applicationContext) }
    private val memoryStore by lazy { HalfFriendMemoryStore(applicationContext) }
    private val chatHistoryStore by lazy { ChatHistoryStore(applicationContext) }
    private val suggestionEngine by lazy { HalfFriendSuggestionEngine() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var resultTextView: TextView? = null
    private var commandInput: EditText? = null
    private var historyWrapView: LinearLayout? = null
    private var bubbleCoreView: FrameLayout? = null
    private var bubbleHaloView: View? = null
    private var bubbleRingView: View? = null
    private var bubbleBurstView: View? = null
    private var bubbleStatusView: TextView? = null
    private var bubbleVisualView: FrameLayout? = null
    private var bubbleAnimatorSet: AnimatorSet? = null
    private var haloAnimator: ObjectAnimator? = null
    private var currentMode: BubbleMode = BubbleMode.STANDBY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> {
                showBubble()
                if (intent?.getBooleanExtra(EXTRA_OPEN_PANEL, false) == true) {
                    showPanel()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        bubbleAnimatorSet?.cancel()
        haloAnimator?.cancel()
        bubbleView?.let { view -> windowManager?.removeView(view) }
        panelView?.let { view -> windowManager?.removeView(view) }
        bubbleView = null
        panelView = null
        super.onDestroy()
    }

    private fun buildForegroundNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HalfFriend overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the HalfFriend bubble running over other apps"
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HalfFriend overlay active")
            .setContentText("Tap the bubble to open your floating command panel.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showBubble() {
        if (bubbleView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        bubbleParams = WindowManager.LayoutParams(
            dp(108),
            dp(108),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(220)
        }

        val bubble = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(108), dp(108))
        }

        val visual = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }

        val halo = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER)
            alpha = 0.35f
        }

        val ring = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER)
        }

        val burst = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER)
            alpha = 0f
        }

        val core = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
            elevation = dp(12).toFloat()
        }

        val coreLogo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
            setImageResource(R.drawable.halffriend_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        core.addView(coreLogo)

        val status = TextView(this).apply {
            text = "Ready"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(24),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(2)
                rightMargin = dp(2)
            }
            setPadding(dp(10), 0, dp(10), 0)
        }

        bubbleHaloView = halo
        bubbleRingView = ring
        bubbleBurstView = burst
        bubbleCoreView = core
        bubbleStatusView = status
        bubbleVisualView = visual

        visual.addView(halo)
        visual.addView(ring)
        visual.addView(burst)
        visual.addView(core)
        bubble.addView(visual)
        bubble.addView(status)

        setBubbleMode(BubbleMode.STANDBY, animate = false)
        startBubbleAnimations()

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val params = bubbleParams ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        moved = false
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        bubbleVisualView?.animate()?.scaleX(0.96f)?.scaleY(0.96f)?.setDuration(120)?.start()
                        playTapBurst()
                        setBubbleMode(BubbleMode.ACTIVE)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6) {
                            moved = true
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(view, params)
                        if (panelView != null) {
                            panelParams?.x = params.x + dp(10)
                            panelParams?.y = params.y + dp(122)
                            panelView?.let { panel -> panelParams?.let { windowManager?.updateViewLayout(panel, it) } }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        bubbleVisualView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
                        if (!moved) {
                            togglePanel()
                        } else {
                            setBubbleMode(if (panelView == null) BubbleMode.QUIET else BubbleMode.ACTIVE)
                        }
                        return true
                    }
                }
                return false
            }
        })

        bubbleView = bubble
        windowManager?.addView(bubble, bubbleParams)
    }

    private fun showPanel() {
        if (panelView != null) return

        val wm = windowManager ?: return
        val anchorParams = bubbleParams ?: return
        val activeSkill = skillSessionStore.getActiveSkillId()
            ?.let(skillRegistry::findSkill)
            ?: skillRegistry.defaultSkill()

        panelParams = WindowManager.LayoutParams(
            dp(310),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorParams.x + dp(10)
            y = anchorParams.y + dp(122)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                colors = intArrayOf(Color.parseColor("#FFFEE0"), Color.parseColor("#FFF9BF"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                setStroke(dp(1), Color.parseColor("#EFE3AF"))
            }
            elevation = dp(12).toFloat()
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val headerDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                rightMargin = dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#8C8B80"))
            }
        }

        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "HalfFriend"
            textSize = 24f
            setTextColor(Color.parseColor("#38392C"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Listening mode"
            textSize = 12f
            setTextColor(Color.parseColor("#6C6B65"))
        }

        val stateBadge = TextView(this).apply {
            text = ""
            width = dp(10)
            height = dp(10)
            textSize = 10f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#8C8B80"))
            }
        }

        val dismissBadge = TextView(this).apply {
            text = "×"
            textSize = 24f
            setTextColor(Color.parseColor("#6C6B65"))
            gravity = Gravity.CENTER
            setOnClickListener { hidePanel() }
        }

        val voiceGlow = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(150)
            )
        }

        val glow = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(118), dp(118), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#667A706C"))
            }
            alpha = 0.35f
        }

        val micOrb = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6A657C"))
            }
            elevation = dp(10).toFloat()
        }

        val micIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
        }
        micOrb.addView(micIcon)
        voiceGlow.addView(glow)
        voiceGlow.addView(micOrb)

        resultTextView = TextView(this).apply {
            text = buildString {
                append("Listening...")
                append("\n")
                append("\"How can I help you today?\"")
            }
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#38392C"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        commandInput = EditText(this).apply {
            hint = "Type a command..."
            setTextColor(Color.parseColor("#1F2933"))
            setHintTextColor(Color.parseColor("#87929B"))
            inputType = InputType.TYPE_CLASS_TEXT
            minLines = 1
            maxLines = 2
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#FFFFFCF7"))
                setStroke(dp(1), Color.parseColor("#EBE2C9"))
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val sendButton = actionButton("➜", "#E7DEFC", "#544E66") {
            val command = commandInput?.text?.toString().orEmpty().trim()
            if (command.isEmpty()) {
                updatePanelResult("Type a command first.")
            } else {
                runCommand(command)
                commandInput?.setText("")
            }
        }

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val overlayActions = listOf(
            "set timer for 5 minutes",
            "message Sunil hello",
            "open maps"
        )
        overlayActions.forEachIndexed { index, prompt ->
            actionRow.addView(circleAction(prompt))
            if (index != overlayActions.lastIndex) {
                actionRow.addView(space(0).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(16), 1)
                })
            }
        }

        titleWrap.addView(title)
        titleWrap.addView(space(4))
        titleWrap.addView(subtitle)
        headerRow.addView(headerDot)
        headerRow.addView(stateBadge)
        headerRow.addView(space(0).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        headerRow.addView(titleWrap)
        headerRow.addView(space(0).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 1)
        })
        headerRow.addView(dismissBadge)

        root.addView(headerRow)
        root.addView(space(10))
        root.addView(voiceGlow)
        root.addView(space(6))
        root.addView(resultTextView)
        root.addView(space(18))
        root.addView(actionRow)
        root.addView(space(20))
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#FFFFFCF7"))
                setStroke(dp(1), Color.parseColor("#EBE2C9"))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        inputRow.addView(commandInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(sendButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(inputRow)

        panelView = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        wm.addView(panelView, panelParams)
        bringBubbleToFront()
        setBubbleMode(BubbleMode.ACTIVE)
    }

    private fun togglePanel() {
        if (panelView == null) showPanel() else hidePanel()
    }

    private fun hidePanel() {
        panelView?.let { view -> windowManager?.removeView(view) }
        panelView = null
        panelParams = null
        setBubbleMode(BubbleMode.QUIET)
        bringBubbleToFront()
    }

    private fun runCommand(command: String) {
        setBubbleMode(BubbleMode.ACTIVE)
        serviceScope.launch {
            val userMessage = ChatMessage(
                id = System.currentTimeMillis(),
                role = ChatMessage.Role.USER,
                content = command
            )
            val result = executionPipeline.run(command, listOf(userMessage))
            memoryStore.recordCommand(command, result.resolution)
            chatHistoryStore.append(userMessage)
            val assistantMessage = ChatMessage(
                id = System.currentTimeMillis() + 1,
                role = ChatMessage.Role.ASSISTANT,
                content = buildString {
                    append(result.reply)
                    if (result.fallbackActions.isNotEmpty()) {
                        append("\n\nTry next:\n")
                        append(result.fallbackActions.take(2).joinToString("\n") { "- $it" })
                    }
                }
            )
            chatHistoryStore.append(assistantMessage)
            val message = assistantMessage.content
            updatePanelResult(message)
            setBubbleMode(if (panelView == null) BubbleMode.QUIET else BubbleMode.STANDBY)
        }
    }

    private fun updatePanelResult(message: String) {
        resultTextView?.text = message
    }

    private fun refreshHistoryRows(messages: List<ChatMessage>) {
        val wrap = historyWrapView ?: return
        wrap.removeAllViews()

        val latest = messages.takeLast(6)
        if (latest.isEmpty()) {
            wrap.addView(
                TextView(this).apply {
                    text = "No chat yet. Send a command and it will appear here."
                    textSize = 12f
                    setTextColor(Color.parseColor("#5B6770"))
                }
            )
            return
        }

        latest.forEachIndexed { index, message ->
            wrap.addView(historyBubble(message))
            if (index != latest.lastIndex) {
                wrap.addView(space(8))
            }
        }
    }

    private fun historyBubble(message: ChatMessage): LinearLayout {
        val isUser = message.role == ChatMessage.Role.USER
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (isUser) Color.parseColor("#E7F6F1") else Color.parseColor("#FFFBF4"))
                setStroke(dp(1), if (isUser) Color.parseColor("#BEE4D8") else Color.parseColor("#E5DCCA"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        bubble.addView(
            TextView(this).apply {
                text = if (isUser) "You" else "HalfFriend"
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (isUser) Color.parseColor("#126A58") else Color.parseColor("#8B5E3C"))
            }
        )
        bubble.addView(space(4))
        bubble.addView(
            TextView(this).apply {
                text = message.content
                textSize = 12f
                setTextColor(Color.parseColor("#1F2933"))
                maxLines = 4
            }
        )
        return bubble
    }

    private fun circleAction(prompt: String): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { runCommand(prompt) }
        }

        val circle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(circleColor(prompt))
            }
        }

        val icon = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            text = circleSymbol(prompt)
            textSize = 24f
            setTextColor(Color.parseColor("#38392C"))
        }
        circle.addView(icon)

        wrap.addView(circle)
        wrap.addView(space(8))
        wrap.addView(
            TextView(this).apply {
                text = chipLabel(prompt).uppercase()
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#6C6B65"))
                gravity = Gravity.CENTER
            }
        )
        return wrap
    }

    private fun actionButton(
        label: String,
        backgroundColor: String,
        textColor: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            setTextColor(Color.parseColor(textColor))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor(backgroundColor))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            textSize = 12f
            transformationMethod = null
        }
    }

    private fun chipLabel(prompt: String): String {
        return when {
            prompt.startsWith("set timer", ignoreCase = true) -> "Timer"
            prompt.startsWith("remind me", ignoreCase = true) -> "Reminder"
            prompt.startsWith("set alarm", ignoreCase = true) -> "Alarm"
            prompt.startsWith("open ", ignoreCase = true) -> prompt.substringAfter("open ").replaceFirstChar { it.uppercase() }
            prompt.startsWith("write ", ignoreCase = true) -> "Write"
            prompt.startsWith("summarize", ignoreCase = true) -> "Summarize"
            else -> prompt.take(12)
        }
    }

    private fun circleSymbol(prompt: String): String {
        return when {
            prompt.startsWith("set timer", ignoreCase = true) -> "⏱"
            prompt.startsWith("remind me", ignoreCase = true) -> "💬"
            prompt.startsWith("open maps", ignoreCase = true) -> "🗺"
            prompt.startsWith("open ", ignoreCase = true) -> "↗"
            else -> "•"
        }
    }

    private fun circleColor(prompt: String): Int {
        return when {
            prompt.startsWith("set timer", ignoreCase = true) -> Color.parseColor("#E7DEFC")
            prompt.startsWith("remind me", ignoreCase = true) -> Color.parseColor("#D5F8EF")
            prompt.startsWith("open maps", ignoreCase = true) -> Color.parseColor("#F1EEE6")
            else -> Color.parseColor("#F1EEE6")
        }
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply {
        rightMargin = dp(8)
    }

    private fun space(heightDp: Int = 8) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(heightDp)
        )
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun bringBubbleToFront() {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        runCatching { wm.removeView(bubble) }
        runCatching { wm.addView(bubble, params) }
    }

    private fun startBubbleAnimations() {
        val bubble = bubbleVisualView ?: return
        val halo = bubbleHaloView ?: return
        val ring = bubbleRingView ?: return

        bubbleAnimatorSet?.cancel()
        haloAnimator?.cancel()

        val scaleX = ObjectAnimator.ofFloat(bubble, View.SCALE_X, 1f, 1.06f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(bubble, View.SCALE_Y, 1f, 1.06f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
        }
        val ringScaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.92f, 1.08f, 0.92f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
        }
        val ringScaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.92f, 1.08f, 0.92f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
        }
        val ringAlpha = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.36f, 0.9f, 0.36f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
        }
        val haloScaleX = ObjectAnimator.ofFloat(halo, View.SCALE_X, 0.94f, 1.16f, 0.94f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
        }
        val haloScaleY = ObjectAnimator.ofFloat(halo, View.SCALE_Y, 0.94f, 1.16f, 0.94f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
        }
        haloAnimator = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.16f, 0.55f, 0.16f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        bubbleAnimatorSet = AnimatorSet().apply {
            playTogether(scaleX, scaleY, ringScaleX, ringScaleY, ringAlpha, haloScaleX, haloScaleY)
            start()
        }
    }

    private fun setBubbleMode(mode: BubbleMode, animate: Boolean = true) {
        currentMode = mode
        val halo = bubbleHaloView ?: return
        val ring = bubbleRingView ?: return
        val core = bubbleCoreView ?: return
        val burst = bubbleBurstView ?: return
        val status = bubbleStatusView ?: return

        val palette = when (mode) {
            BubbleMode.QUIET -> BubblePalette(
                start = "#5C6470",
                end = "#3E4651",
                halo = "#8E99A8",
                ring = "#BAC4D3",
                status = "Quiet"
            )
            BubbleMode.STANDBY -> BubblePalette(
                start = "#1A9B83",
                end = "#126A58",
                halo = "#76F1D0",
                ring = "#B7FFF0",
                status = "Ready"
            )
            BubbleMode.ACTIVE -> BubblePalette(
                start = "#8B5CF6",
                end = "#4F46E5",
                halo = "#D9C2FF",
                ring = "#F0E6FF",
                status = "Live"
            )
        }

        core.background = bubbleCoreDrawable(palette.start, palette.end)
        ring.background = bubbleRingDrawable(palette.ring)
        halo.background = bubbleHaloDrawable(palette.halo)
        burst.background = bubbleBurstDrawable(palette.ring)
        status.text = palette.status
        status.background = bubbleStatusDrawable(palette.start, palette.end)

        val targetAlpha = when (mode) {
            BubbleMode.QUIET -> 0.18f
            BubbleMode.STANDBY -> 0.34f
            BubbleMode.ACTIVE -> 0.56f
        }
        val targetRingAlpha = when (mode) {
            BubbleMode.QUIET -> 0.26f
            BubbleMode.STANDBY -> 0.52f
            BubbleMode.ACTIVE -> 0.92f
        }

        if (animate) {
            halo.animate().alpha(targetAlpha).setDuration(240).start()
            ring.animate().alpha(targetRingAlpha).setDuration(240).start()
        } else {
            halo.alpha = targetAlpha
            ring.alpha = targetRingAlpha
        }
    }

    private fun playTapBurst() {
        val burst = bubbleBurstView ?: return
        burst.scaleX = 0.82f
        burst.scaleY = 0.82f
        burst.alpha = 0.7f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(burst, View.SCALE_X, 0.82f, 1.28f),
                ObjectAnimator.ofFloat(burst, View.SCALE_Y, 0.82f, 1.28f),
                ObjectAnimator.ofFloat(burst, View.ALPHA, 0.7f, 0f)
            )
            duration = 320
            start()
        }
    }

    private fun bubbleCoreDrawable(start: String, end: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        colors = intArrayOf(Color.parseColor(start), Color.parseColor(end))
        gradientType = GradientDrawable.LINEAR_GRADIENT
        setStroke(dp(1), Color.parseColor("#26FFFFFF"))
    }

    private fun bubbleRingDrawable(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), Color.parseColor(color))
    }

    private fun bubbleHaloDrawable(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(42).toFloat()
        colors = intArrayOf(
            adjustAlpha(Color.parseColor(color), 0.42f),
            adjustAlpha(Color.parseColor(color), 0.10f),
            Color.TRANSPARENT
        )
    }

    private fun bubbleBurstDrawable(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), adjustAlpha(Color.parseColor(color), 0.9f))
    }

    private fun bubbleStatusDrawable(start: String, end: String) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        colors = intArrayOf(adjustAlpha(Color.parseColor(start), 0.96f), adjustAlpha(Color.parseColor(end), 0.96f))
        gradientType = GradientDrawable.LINEAR_GRADIENT
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private data class BubblePalette(
        val start: String,
        val end: String,
        val halo: String,
        val ring: String,
        val status: String
    )

    companion object {
        const val ACTION_START = "com.halffriend.nativeapp.overlay.START"
        const val ACTION_STOP = "com.halffriend.nativeapp.overlay.STOP"
        const val EXTRA_OPEN_PANEL = "extra_open_panel"
        private const val CHANNEL_ID = "halffriend_overlay_service"
        private const val NOTIFICATION_ID = 1001
    }
}
