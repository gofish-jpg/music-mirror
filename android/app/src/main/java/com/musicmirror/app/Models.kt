package com.musicmirror.app

data class TrackInfo(
    val mediaId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long
) {
    val key: String = mediaId?.takeIf { it.isNotBlank() }
        ?: listOf(title.trim(), artist.trim(), durationMs.toString()).joinToString("|")
}

data class PendingListen(
    val id: String,
    val trackKey: String,
    val mediaId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val listenedMs: Long,
    val durationMs: Long,
    val lastPositionMs: Long,
    val completed: Boolean,
    val deviceId: String
)

data class PendingEvent(
    val id: Long,
    val listenId: String,
    val type: String,
    val occurredAt: Long,
    val positionMs: Long,
    val payload: String?
)

data class RecommendationMix(
    val id: String,
    val name: String,
    val summary: String?,
    val createdAt: Long
)

data class RecommendationItem(
    val id: String,
    val position: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val reason: String?,
    val youtubeUrl: String?,
    val feedback: String?
)

data class ListeningSummary(
    val sessions: Int,
    val uniqueTracks: Int,
    val listenedMs: Long,
    val completionRate: Double,
    val skipCount: Int
)

data class DjDashboard(
    val periodDays: Int,
    val summary: ListeningSummary,
    val mix: RecommendationMix?,
    val recommendations: List<RecommendationItem>
)
