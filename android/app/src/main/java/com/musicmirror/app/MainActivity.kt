package com.musicmirror.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var settings: SecretStore
    private lateinit var database: MusicDatabase
    private lateinit var djClient: DjClient
    private lateinit var permissionStatus: TextView
    private lateinit var recordStatus: TextView
    private lateinit var tasteStatus: TextView
    private lateinit var mixTitle: TextView
    private lateinit var mixSummary: TextView
    private lateinit var recommendationList: LinearLayout
    private lateinit var refreshButton: Button
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecretStore(this)
        database = MusicDatabase(this)
        djClient = DjClient(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (settings.configured()) loadDjDashboard()
    }

    override fun onDestroy() {
        djClient.close()
        database.close()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(36))
        }

        content.addView(label("Music Mirror DJ", 31f, bold = true))
        content.addView(label("내 청취 기록을 배우는 ChatGPT 음악 비서", 16f).apply {
            setPadding(0, dp(5), 0, dp(24))
        })

        content.addView(sectionTitle("내 취향 · 최근 30일"))
        tasteStatus = label("서버에서 취향 데이터를 불러오는 중…", 16f)
        content.addView(tasteStatus)

        content.addView(sectionTitle("ChatGPT AI 추천"))
        mixTitle = label("아직 생성된 추천 믹스가 없습니다.", 20f, bold = true)
        content.addView(mixTitle)
        mixSummary = label("ChatGPT에서 Music Mirror를 켜고 추천 믹스를 만들어 달라고 해보세요.", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(4), 0, dp(10))
        }
        content.addView(mixSummary)
        refreshButton = Button(this).apply {
            text = "AI 추천 새로고침"
            setOnClickListener { loadDjDashboard() }
        }
        content.addView(refreshButton, matchWidth())
        recommendationList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(recommendationList)

        content.addView(sectionTitle("수집 및 동기화"))
        permissionStatus = label("", 17f)
        content.addView(permissionStatus)
        content.addView(Button(this).apply {
            text = "알림 접근 권한 열기"
            setOnClickListener { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        }, matchWidth())

        recordStatus = label("", 15f).apply { setPadding(0, dp(12), 0, dp(12)) }
        content.addView(recordStatus)

        content.addView(sectionTitle("서버 설정"))
        content.addView(label("개인 서버 주소", 14f))
        serverInput = EditText(this).apply {
            hint = "https://music-mirror.example.workers.dev"
            setText(settings.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        content.addView(serverInput)

        content.addView(label("수집용 비밀 키", 14f).apply { setPadding(0, dp(12), 0, 0) })
        tokenInput = EditText(this).apply {
            hint = "배포 후 발급된 INGEST_TOKEN"
            setText(settings.ingestToken)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        content.addView(tokenInput)

        content.addView(Button(this).apply {
            text = "저장하고 지금 동기화"
            setOnClickListener { saveAndSync() }
        }, matchWidth(dp(18)))

        content.addView(label(
            "Music Mirror는 곡명, 아티스트, 앨범, 재생 시간, 완주·스킵과 추천 피드백만 저장합니다. Google 비밀번호와 YouTube 로그인 토큰은 수집하지 않습니다.",
            13f,
            Color.DKGRAY
        ).apply { setPadding(0, dp(22), 0, 0) })

        return ScrollView(this).apply { addView(content) }
    }

    private fun loadDjDashboard() {
        if (!settings.configured()) {
            tasteStatus.text = "먼저 아래 서버 설정을 저장해주세요."
            return
        }
        refreshButton.isEnabled = false
        refreshButton.text = "불러오는 중…"
        djClient.loadDashboard { result ->
            runOnUiThread {
                refreshButton.isEnabled = true
                refreshButton.text = "AI 추천 새로고침"
                result.fold(
                    onSuccess = ::renderDashboard,
                    onFailure = {
                        tasteStatus.text = "서버 데이터를 불러오지 못했습니다."
                        Toast.makeText(this, it.message ?: "알 수 없는 오류", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun renderDashboard(dashboard: DjDashboard) {
        val minutes = dashboard.summary.listenedMs / 60_000
        tasteStatus.text = buildString {
            append("${dashboard.summary.sessions}회 재생 · ${dashboard.summary.uniqueTracks}곡 · ${minutes}분 청취")
            append("\n완주율 ${String.format(Locale.KOREA, "%.1f", dashboard.summary.completionRate)}% · 스킵 ${dashboard.summary.skipCount}회")
        }

        val mix = dashboard.mix
        if (mix == null) {
            mixTitle.text = "아직 생성된 추천 믹스가 없습니다."
            mixSummary.text = "ChatGPT에서 ‘내 청취 기록으로 추천 믹스 10곡을 만들고 Music Mirror 앱에 저장해줘’라고 말해보세요."
        } else {
            mixTitle.text = mix.name
            val date = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(mix.createdAt))
            mixSummary.text = listOfNotNull(mix.summary, date).joinToString("\n")
        }
        renderRecommendations(dashboard.recommendations)
    }

    private fun renderRecommendations(items: List<RecommendationItem>) {
        recommendationList.removeAllViews()
        if (items.isEmpty()) {
            recommendationList.addView(label("추천이 생성되면 여기에 곡 카드가 나타납니다.", 14f, Color.GRAY))
            return
        }
        items.forEach { item -> recommendationList.addView(recommendationCard(item)) }
    }

    private fun recommendationCard(item: RecommendationItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(246, 243, 250))
                cornerRadius = dp(16).toFloat()
            }
        }
        card.addView(label("${item.position}. ${item.title}", 18f, bold = true))
        card.addView(label(item.artist + (item.album?.let { " · $it" } ?: ""), 14f, Color.DKGRAY))
        item.reason?.let {
            card.addView(label(it, 14f, Color.rgb(82, 66, 92)).apply { setPadding(0, dp(7), 0, dp(7)) })
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "▶ 듣기"
            setOnClickListener { openRecommendation(item) }
        }, weighted())
        actions.addView(Button(this).apply {
            text = if (item.feedback == "like") "♥ 좋아요" else "♡ 좋아요"
            setOnClickListener { sendFeedback(item, "like") }
        }, weighted())
        actions.addView(Button(this).apply {
            text = if (item.feedback == "dislike") "별로 ✓" else "별로"
            setOnClickListener { sendFeedback(item, "dislike") }
        }, weighted())
        card.addView(actions)
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun openRecommendation(item: RecommendationItem) {
        djClient.sendFeedback(item.id, "played")
        val queryUrl = "https://music.youtube.com/search?q=${Uri.encode("${item.artist} ${item.title}")}"
        val target = item.youtubeUrl?.takeIf { it.startsWith("https://") } ?: queryUrl
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).setPackage("com.google.android.apps.youtube.music")
        runCatching { startActivity(appIntent) }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
    }

    private fun sendFeedback(item: RecommendationItem, feedback: String) {
        djClient.sendFeedback(item.id, feedback) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        Toast.makeText(this, if (feedback == "like") "취향에 반영했어요." else "다음 추천에서 줄일게요.", Toast.LENGTH_SHORT).show()
                        loadDjDashboard()
                    },
                    onFailure = { Toast.makeText(this, "피드백 저장 실패: ${it.message}", Toast.LENGTH_LONG).show() }
                )
            }
        }
    }

    private fun saveAndSync() {
        val url = serverInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "서버 주소는 https://로 시작해야 합니다.", Toast.LENGTH_LONG).show()
            return
        }
        if (token.length < 20) {
            Toast.makeText(this, "비밀 키가 너무 짧습니다.", Toast.LENGTH_LONG).show()
            return
        }
        settings.serverUrl = url
        settings.ingestToken = token
        val client = SyncClient(this, database)
        Toast.makeText(this, "동기화를 시작했습니다.", Toast.LENGTH_SHORT).show()
        client.schedule(force = true) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { Toast.makeText(this, "동기화 완료: ${it}개 기록", Toast.LENGTH_LONG).show() },
                    onFailure = { Toast.makeText(this, "동기화 실패: ${it.message}", Toast.LENGTH_LONG).show() }
                )
                refreshStatus()
                loadDjDashboard()
                client.close()
            }
        }
    }

    private fun refreshStatus() {
        val enabled = isNotificationAccessEnabled()
        permissionStatus.text = if (enabled) "✓ 알림 접근 권한이 켜져 있습니다." else "! 알림 접근 권한을 켜주세요."
        permissionStatus.setTextColor(if (enabled) Color.rgb(36, 120, 69) else Color.rgb(180, 55, 55))
        recordStatus.text = "이 폰에 저장된 청취 세션: ${database.listenCount()}개\n서버 연결: ${if (settings.configured()) "설정됨" else "아직 설정 안 됨"}"
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val target = ComponentName(this, MusicNotificationListener::class.java)
        return flat.split(":").mapNotNull(ComponentName::unflattenFromString).any { it == target }
    }

    private fun label(text: String, size: Float, color: Int = Color.rgb(35, 31, 38), bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun sectionTitle(text: String) = label(text, 21f, bold = true).apply {
        setPadding(0, dp(24), 0, dp(10))
    }

    private fun matchWidth(topMargin: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
        }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
