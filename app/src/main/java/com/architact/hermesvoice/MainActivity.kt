package com.architact.hermesvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.architact.hermesvoice.security.PairingTokenStore
import com.architact.hermesvoice.session.Turn
import com.architact.hermesvoice.session.VoiceMessages
import com.architact.hermesvoice.session.VoiceSessionViewModel
import com.architact.hermesvoice.session.VoiceState
import com.architact.hermesvoice.ui.MainAction
import com.architact.hermesvoice.ui.MaxWidthLayout
import com.architact.hermesvoice.ui.OrbView
import com.architact.hermesvoice.ui.PillButton
import com.architact.hermesvoice.ui.color
import com.architact.hermesvoice.ui.dp
import com.architact.hermesvoice.ui.dpi
import com.architact.hermesvoice.ui.iconButton
import com.architact.hermesvoice.ui.rounded
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: VoiceSessionViewModel by viewModels()
    private val controller get() = viewModel.controller

    private lateinit var content: LinearLayout
    private lateinit var orbFrame: FrameLayout
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var approvalCard: LinearLayout
    private lateinit var approvalText: TextView
    private lateinit var approvalCommand: TextView
    private lateinit var history: LinearLayout
    private lateinit var historyScroll: ScrollView
    private lateinit var settings: View
    private lateinit var leftSlot: FrameLayout
    private lateinit var rightSlot: FrameLayout
    private lateinit var mainAction: MainAction
    private lateinit var actionRow: LinearLayout
    private lateinit var approvalRow: LinearLayout
    private var compactOrb: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createView())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    controller.state.combine(controller.turns) { state, turns -> state to turns }
                        .collect { (state, turns) -> render(state, turns) }
                }
                launch { viewModel.micLevel.collect { orb.level = it } }
            }
        }
        // A fold/unfold recreation keeps the running session in the ViewModel; only a fresh launch starts one.
        if (savedInstanceState == null && !consumePairing(intent)) handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!consumePairing(intent)) handleLaunch(intent)
    }

    /**
     * App icon / Bixby: spoken greeting, then listen. Side button (assistant): listen at once, or
     * cut into a reply being read. A running task or pending approval is shown, never restarted;
     * opening from the task notification only shows the screen.
     */
    private fun handleLaunch(intent: Intent) {
        when (controller.state.value) {
            is VoiceState.Waiting, is VoiceState.Approving -> return
            is VoiceState.Listening -> if (intent.action == ACTION_LISTEN) return
            else -> Unit
        }
        when (intent.action) {
            ACTION_LISTEN -> withMicrophone { controller.quickStart() }
            Intent.ACTION_MAIN -> if (controller.state.value !is VoiceState.Speaking) withMicrophone { controller.start() }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) controller.onBackground()
        super.onStop()
    }

    // ---------------------------------------------------------------- layout

    private fun createView(): View {
        drawEdgeToEdge()
        val root = FrameLayout(this).apply { setBackgroundColor(color(R.color.hv_background)) }
        content = MaxWidthLayout(this, dpi(600)).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), 0, dpi(20), dpi(16))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        content.addView(topBar(), LinearLayout.LayoutParams(-1, dpi(56)))

        orb = OrbView(this)
        orbFrame = FrameLayout(this).apply { addView(orb, FrameLayout.LayoutParams(-1, -1)) }
        content.addView(orbFrame, LinearLayout.LayoutParams(-1, dpi(ORB_LARGE)))

        status = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.hv_on_background))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2))
        detail = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.hv_on_muted))
        }
        content.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4) })

        content.addView(approvalCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(16) })

        history = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpi(8), 0, dpi(8))
        }
        historyScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dpi(24))
            addView(history, ViewGroup.LayoutParams(-1, -2))
        }
        content.addView(historyScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(12) })

        content.addView(bottomBar(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
        return root
    }

    private fun drawEdgeToEdge() {
        // Our background already contrasts with the nav buttons; skip the system's extra scrim band.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun topBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(R.color.hv_on_background))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        settings = iconButton(R.drawable.ic_tune, "목소리 설정") { showVoiceMenu(settings) }
        addView(settings, LinearLayout.LayoutParams(dpi(48), dpi(48)))
    }

    private fun approvalCard(): View {
        approvalText = TextView(this).apply {
            textSize = 16f
            setTextColor(color(R.color.hv_on_background))
        }
        approvalCommand = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.hv_on_muted))
            setTextIsSelectable(true)
        }
        approvalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
            background = rounded(color(R.color.hv_attention_container), dp(16f))
            addView(TextView(context).apply {
                text = "확인이 필요해요"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(color(R.color.hv_attention))
            })
            addView(approvalText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6) })
            addView(approvalCommand, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6) })
            visibility = View.GONE
        }
        return approvalCard
    }

    private fun bottomBar(): View {
        leftSlot = FrameLayout(this)
        rightSlot = FrameLayout(this)
        mainAction = MainAction(this)
        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(leftSlot, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mainAction, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpi(12); marginEnd = dpi(12) })
            addView(rightSlot, LinearLayout.LayoutParams(0, -2, 1f))
        }
        approvalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(PillButton(context, filled = false, icon = R.drawable.ic_close).apply {
                text = "거부"
                setOnClickListener { controller.decideApproval(false) }
            }, LinearLayout.LayoutParams(0, dpi(56), 1f))
            addView(PillButton(context, filled = true, icon = R.drawable.ic_check).apply {
                text = "진행"
                setOnClickListener { controller.decideApproval(true) }
            }, LinearLayout.LayoutParams(0, dpi(56), 1f).apply { marginStart = dpi(12) })
            visibility = View.GONE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionRow, LinearLayout.LayoutParams(-1, -2))
            addView(approvalRow, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ---------------------------------------------------------------- render

    private fun render(state: VoiceState, turns: List<Turn>) {
        orb.mode = when (state) {
            VoiceState.Idle, is VoiceState.Ended -> OrbView.Mode.Idle
            VoiceState.Prompting, is VoiceState.Speaking -> OrbView.Mode.Speaking
            is VoiceState.Listening -> OrbView.Mode.Listening
            is VoiceState.Waiting -> OrbView.Mode.Thinking
            is VoiceState.Approving -> OrbView.Mode.Attention
            is VoiceState.Error -> OrbView.Mode.Error
        }
        status.text = VoiceMessages.status(state)
        status.setTextColor(
            color(
                when (state) {
                    is VoiceState.Error -> R.color.hv_danger
                    is VoiceState.Approving -> R.color.hv_attention
                    else -> R.color.hv_on_background
                },
            ),
        )
        detail.text = when (state) {
            is VoiceState.Listening -> if (state.followUp) "말이 없으면 잠시 쉬어 갈게요" else "말씀이 끝나면 잠시 기다려 주세요"
            is VoiceState.Waiting -> "다른 앱을 써도 작업은 계속돼요"
            else -> ""
        }
        detail.visibility = if (detail.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        if (state is VoiceState.Approving) {
            approvalText.text = state.description.ifBlank { "Hermes가 다음 작업을 실행하려고 해요." }
            approvalCommand.text = state.command
            approvalCommand.visibility = if (state.command.isBlank()) View.GONE else View.VISIBLE
            approvalCard.visibility = View.VISIBLE
        } else {
            approvalCard.visibility = View.GONE
        }

        renderHistory(turns, state)
        renderActions(state)
        val quiet = state is VoiceState.Idle || state is VoiceState.Ended || state is VoiceState.Error
        settings.isEnabled = quiet
        settings.alpha = if (quiet) 1f else 0.38f
    }

    private fun renderActions(state: VoiceState) {
        val approving = state is VoiceState.Approving
        actionRow.visibility = if (approving) View.GONE else View.VISIBLE
        approvalRow.visibility = if (approving) View.VISIBLE else View.GONE
        leftSlot.removeAllViews()
        rightSlot.removeAllViews()

        fun pill(slot: FrameLayout, label: String, icon: Int, action: () -> Unit) {
            slot.addView(PillButton(this, filled = false, icon = icon).apply {
                text = label
                setOnClickListener { action() }
            }, FrameLayout.LayoutParams(-2, -2, if (slot === leftSlot) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL))
        }

        when (state) {
            VoiceState.Idle -> mainAction.bind(R.drawable.ic_mic, "말하기") { withMicrophone { controller.start() } }
            VoiceState.Prompting, is VoiceState.Listening -> mainAction.bind(R.drawable.ic_close, "대화 끝내기") { controller.cancel() }
            is VoiceState.Waiting -> mainAction.bind(R.drawable.ic_stop, "작업 멈추기") { controller.cancel() }
            is VoiceState.Speaking -> {
                mainAction.bind(R.drawable.ic_mic, "끊고 말하기") { withMicrophone { controller.interrupt() } }
                pill(rightSlot, "끝내기", R.drawable.ic_close) { controller.cancel() }
            }
            is VoiceState.Ended, is VoiceState.Error -> {
                mainAction.bind(R.drawable.ic_mic, "이어서 말하기") { withMicrophone { controller.continueConversation() } }
                pill(leftSlot, "새 대화", R.drawable.ic_add) { withMicrophone { controller.start() } }
                if ((state as? VoiceState.Error)?.retry != null) pill(rightSlot, "다시 보내기", R.drawable.ic_refresh) { controller.retry() }
            }
            is VoiceState.Approving -> Unit
        }
    }

    private fun renderHistory(turns: List<Turn>, state: VoiceState) {
        val shown = turns.toMutableList()
        // Show the question being worked on and the reply as it streams in, before they become turns.
        when (state) {
            is VoiceState.Waiting -> if (shown.lastOrNull()?.fromUser != true) shown += Turn(true, state.transcript)
            is VoiceState.Approving -> if (shown.lastOrNull()?.fromUser != true) shown += Turn(true, state.transcript)
            is VoiceState.Speaking -> if (!state.complete) shown += Turn(false, state.reply)
            else -> Unit
        }
        setCompactOrb(shown.isNotEmpty())
        history.removeAllViews()
        if (shown.isEmpty()) {
            history.addView(TextView(this).apply {
                text = "측면 버튼을 길게 누르거나\n“하이 빅스비, 헤르메스”라고 불러 보세요."
                gravity = Gravity.CENTER
                textSize = 15f
                setLineSpacing(0f, 1.3f)
                setTextColor(color(R.color.hv_on_muted))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(24) })
            return
        }
        shown.forEach { history.addView(bubble(it)) }
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(turn: Turn): View {
        val big = dp(20f)
        val small = dp(6f)
        return TextView(this).apply {
            text = turn.text
            textSize = 16f
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
            setTextColor(color(if (turn.fromUser) R.color.hv_on_user_bubble else R.color.hv_on_background))
            setPadding(dpi(16), dpi(12), dpi(16), dpi(12))
            contentDescription = (if (turn.fromUser) "나: " else "헤르메스: ") + turn.text
            background = GradientDrawable().apply {
                // Corner order: top-left, top-right, bottom-right, bottom-left; the "tail" corner is tighter.
                cornerRadii = if (turn.fromUser) floatArrayOf(big, big, big, big, small, small, big, big)
                else floatArrayOf(big, big, big, big, big, big, small, small)
                setColor(color(if (turn.fromUser) R.color.hv_user_bubble else R.color.hv_surface))
                if (!turn.fromUser) setStroke(dpi(1), color(R.color.hv_outline))
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dpi(10)
                gravity = if (turn.fromUser) Gravity.END else Gravity.START
                if (turn.fromUser) marginStart = dpi(56) else marginEnd = dpi(32)
            }
        }
    }

    /** The orb steps back (smaller) once a conversation is on screen. */
    private fun setCompactOrb(compact: Boolean) {
        if (compactOrb == compact) return
        if (compactOrb != null) TransitionManager.beginDelayedTransition(content, AutoTransition().setDuration(280))
        compactOrb = compact
        orbFrame.layoutParams = orbFrame.layoutParams.apply { height = dpi(if (compact) ORB_COMPACT else ORB_LARGE) }
    }

    private fun showVoiceMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "목소리 바꾸기")
            menu.add(0, 2, 1, if (viewModel.edgeVoiceEnabled) "휴대폰 음성으로 전환" else "PC 고품질 음성 사용")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> viewModel.changeVoice(::toast)
                    2 -> viewModel.toggleEdgeVoice(::toast)
                }
                true
            }
        }.show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- permissions & pairing

    private var afterPermission: (() -> Unit)? = null

    private fun withMicrophone(action: () -> Unit) {
        if (hasMicrophone()) {
            askNotificationsOnce()
            return action()
        }
        afterPermission = action
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        // The task notification needs this on Android 13+; asked together with the microphone, once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(wanted.toTypedArray(), REQUEST_MICROPHONE)
    }

    /** The "working on it" notification needs this on Android 13+; asked at most once, never blocking. */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun hasMicrophone() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_MICROPHONE) return
        val action = afterPermission
        afterPermission = null
        if (hasMicrophone()) action?.invoke()
        else status.text = "마이크 권한이 필요합니다. 설정에서 허용해 주세요."
    }

    /** Handles `adb shell am start ... --es pairing_token <token>`; returns true if the intent was a pairing. */
    private fun consumePairing(intent: Intent?): Boolean {
        val token = intent?.getStringExtra(EXTRA_PAIRING_TOKEN) ?: return false
        intent.removeExtra(EXTRA_PAIRING_TOKEN)
        val message = if (PairingTokenStore.isValid(token)) {
            PairingTokenStore(this).save(token)
            "페어링되었습니다"
        } else {
            "페어링 토큰 형식이 올바르지 않습니다"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        return true
    }

    companion object {
        /** Sent by [AssistActivity]: start listening immediately. */
        const val ACTION_LISTEN = "com.architact.hermesvoice.action.LISTEN"
        private const val REQUEST_MICROPHONE = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private const val EXTRA_PAIRING_TOKEN = "pairing_token"
        private const val ORB_LARGE = 260
        private const val ORB_COMPACT = 132
    }
}
