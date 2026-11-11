package net.blanchard.frameagent

import org.json.JSONObject

object MediaType {
    const val IMAGE = "image"
    const val VIDEO = "video"
}

object MediaFit {
    const val COVER = "cover"
    const val CONTAIN = "contain"
}

data class MediaRequest(
    val url: String,
    val type: String,
    val fit: String,
    val timeoutS: Long,
) {
    companion object {
        const val CLEAR = "clear"

        /** Returns null for a clear (empty payload or [CLEAR]); throws if the payload is bad. */
        fun parse(payload: String): MediaRequest? {
            val text = payload.trim()
            if (text.isEmpty() || text == CLEAR) return null
            val json = JSONObject(text)
            val url = json.getString("url")
            require(url.startsWith("http://") || url.startsWith("https://")) { "url must be http(s)" }
            val type = json.getString("type")
            require(type == MediaType.IMAGE || type == MediaType.VIDEO) { "unknown type '$type'" }
            val fit = json.optString("fit", MediaFit.COVER)
            require(fit == MediaFit.COVER || fit == MediaFit.CONTAIN) { "unknown fit '$fit'" }
            return MediaRequest(url, type, fit, json.optLong("timeout_s", 0L))
        }
    }
}

object MediaScaling {
    /** Smallest power of two that brings an image down to [maxPixels]. */
    fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > maxPixels) sample *= 2
        return sample
    }

    /** The picture's width and height after [fit], as fractions of the view's. */
    fun fitScale(viewW: Float, viewH: Float, videoW: Float, videoH: Float, fit: String): Pair<Float, Float> {
        val widthRatio = viewW / videoW
        val heightRatio = viewH / videoH
        val scale = if (fit == MediaFit.COVER) maxOf(widthRatio, heightRatio) else minOf(widthRatio, heightRatio)
        return (videoW * scale / viewW) to (videoH * scale / viewH)
    }
}
