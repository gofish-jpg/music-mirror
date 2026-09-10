package com.musicmirror.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var settings: SecretStore
    private lateinit var database: MusicDatabase
    private lateinit var permissionStatus: TextView
    private lateinit var recordStatus: TextView
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecretStore(this)
        database = MusicDatabase(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun TextView.commonText(size: Float = 16f) {
            textSize = size
            setTextColor(Color.rgb(35, 31, 38))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        content.addView(TextView(this).apply {
            text = "Music Mirror"
            commonText(30f)
        })
        content.addView(TextView(this).apply {
            text = "YouTube Music 청취 기록을 내 폰에서 자동으로 수집합니다."
            commonText(16f)
            setPadding(0, dp(8), 0, dp(24))
        })

        permissionStatus = TextView(this).apply { commonText(17f) }
        content.addView(permissionStatus)
        content.addView(Button(this).apply {
            text = "알림 접근 권한 열기"
            setOnClickListener { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        recordStatus = TextView(this).apply {
            commonText(16f)
            setPadding(0, dp(18), 0, dp(18))
        }
        content.addView(recordStatus)

        content.addView(TextView(this).apply {
            text = "개인 서버 주소"
            commonText(14f)
        })
        serverInput = EditText(this).apply {
            hint = "https://music-mirror.example.workers.dev"
            setText(settings.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        content.addView(serverInput)

        content.addView(TextView(this).apply {
            text = "수집용 비밀 키"
            commonText(14f)
            setPadding(0, dp(16), 0, 0)
        })
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
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        content.addView(TextView(this).apply {
            text = "수집하는 정보\n곡명, 아티스트, 앨범, 재생 시작/종료, 들은 시간, 일시정지, 스킵, 탐색, 완주 여부\n\n수집하지 않는 정보\nGoogle 비밀번호, YouTube 로그인 토큰, 다른 앱의 일반 알림 내용"
            commonText(14f)
            setPadding(0, dp(24), 0, 0)
        })

        return ScrollView(this).apply { addView(content) }
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
                    onSuccess = { Toast.makeText(this, "동기화 완료: $it개 기록", Toast.LENGTH_LONG).show() },
                    onFailure = { Toast.makeText(this, "동기화 실패: ${it.message}", Toast.LENGTH_LONG).show() }
                )
                refreshStatus()
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
}
