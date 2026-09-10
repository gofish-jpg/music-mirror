package com.musicmirror.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class DjClient(context: Context) {
    private val settings = SecretStore(context)
    private val executor = Executors.newSingleThreadExecutor()

    fun loadDashboard(callback: (Result<DjDashboard>) -> Unit) {
        executor.execute { callback(runCatching { loadDashboardNow() }) }
    }

    fun sendFeedback(itemId: String, feedback: String, callback: (Result<Unit>) -> Unit = {}) {
        executor.execute {
            callback(runCatching {
                val body = JSONObject().apply {
                    put("recommendationId", itemId)
                    put("feedback", feedback)
                    put("deviceId", settings.deviceId)
                }.toString()
                request("/v1/feedback", "POST", body)
                Unit
            })
        }
    }

    fun close() = executor.shutdownNow()

    private fun loadDashboardNow(): DjDashboard {
        val root = JSONObject(request("/v1/dashboard"))
        val summaryJson = root.optJSONObject("summary") ?: JSONObject()
        val recommendationRoot = root.optJSONObject("recommendationMix") ?: JSONObject()
        val mixJson = recommendationRoot.optJSONObject("mix")
        val mix = mixJson?.let {
            RecommendationMix(
                id = it.optString("id"),
                name = it.optString("name", "AI 추천 믹스"),
                summary = it.stringOrNull("summary"),
                createdAt = it.optLong("createdAt")
            )
        }
        val items = mutableListOf<RecommendationItem>()
        val array = recommendationRoot.optJSONArray("items")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                items += RecommendationItem(
                    id = item.optString("id"),
                    position = item.optInt("position", index + 1),
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    album = item.stringOrNull("album"),
                    reason = item.stringOrNull("reason"),
                    youtubeUrl = item.stringOrNull("youtubeUrl"),
                    feedback = item.stringOrNull("feedback")
                )
            }
        }
        return DjDashboard(
            periodDays = root.optInt("periodDays", 30),
            summary = ListeningSummary(
                sessions = summaryJson.optInt("sessions"),
                uniqueTracks = summaryJson.optInt("uniqueTracks"),
                listenedMs = summaryJson.optLong("listenedMs"),
                completionRate = summaryJson.optDouble("completionRate", 0.0),
                skipCount = summaryJson.optInt("skipCount")
            ),
            mix = mix,
            recommendations = items
        )
    }

    private fun request(path: String, method: String = "GET", body: String? = null): String {
        check(settings.configured()) { "먼저 서버 주소와 수집용 비밀 키를 저장해주세요." }
        val connection = (URL(settings.serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${settings.ingestToken}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MusicMirror-Android/0.2")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("서버 응답 오류 ($code): $response")
        return response
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
