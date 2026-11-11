package net.blanchard.frameagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Middle overlay layer: a full-screen scene over Aura's photos, silent, until it's
 * cleared or times out. The photo stays visible until the scene is ready. Only one
 * scene is ever held, since the frames have 1 GB of RAM and Aura is using a quarter.
 */
class MediaOverlay(
    private val context: Context,
    private val layer: OverlayLayer,
    private val onStateChanged: () -> Unit,
    private val onError: (url: String, reason: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val timeout = Runnable { clear() }

    // Bumped on every change, so a slow download can't show a scene already replaced.
    private var generation = 0
    private var bitmap: Bitmap? = null
    private var player: ExoPlayer? = null

    var currentUrl: String? = null
        private set

    /** Set while the panel is off: nobody can see the video, so don't decode it. */
    var paused = false
        set(value) {
            field = value
            player?.playWhenReady = !value
        }

    fun show(request: MediaRequest) {
        release()
        currentUrl = request.url
        when (request.type) {
            MediaType.IMAGE -> loadImage(request)
            else -> playVideo(request)
        }
        onStateChanged()
    }

    fun clear() {
        release()
        if (currentUrl != null) {
            currentUrl = null
            onStateChanged()
        }
    }

    fun stop() {
        clear()
        io.shutdown()
    }

    private fun release() {
        generation++
        main.removeCallbacks(timeout)
        layer.clear()
        player?.release()
        player = null
        bitmap?.recycle()
        bitmap = null
    }

    private fun loadImage(request: MediaRequest) {
        val requestGeneration = generation
        io.execute {
            val result = runCatching { fetchBitmap(request.url) }
            main.post {
                if (requestGeneration != generation) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                result.fold(
                    onSuccess = { showImage(it, request) },
                    onFailure = { fail(request.url, it.toString()) },
                )
            }
        }
    }

    private fun showImage(image: Bitmap, request: MediaRequest) {
        bitmap = image
        layer.show(ImageView(context).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = if (request.fit == MediaFit.COVER) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            setImageBitmap(image)
        })
        scheduleTimeout(request)
    }

    private fun fetchBitmap(url: String): Bitmap {
        val file = File(context.cacheDir, "scene")
        try {
            download(url, file)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) throw IOException("not an image")
            val options = BitmapFactory.Options().apply {
                inSampleSize = MediaScaling.sampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_PIXELS)
            }
            val decoded = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("couldn't decode")
            return upright(decoded, file)
        } finally {
            file.delete()
        }
    }

    private fun download(url: String, file: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) throw IOException("image over $MAX_IMAGE_BYTES bytes")
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Phone photos are often stored sideways, with the rotation in EXIF. */
    private fun upright(decoded: Bitmap, file: File): Bitmap {
        val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return decoded
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    @OptIn(UnstableApi::class)
    private fun playVideo(request: MediaRequest) {
        val texture = TextureView(context).apply { alpha = 0f }
        val frame = FrameLayout(context).apply { addView(texture) }
        var video = VideoSize.UNKNOWN

        // Size the view to the picture and let the frame clip it. Scaling it with setTransform
        // instead showed a strip of the decoder's padding rows below letterboxed video.
        fun fit() {
            if (video.width == 0 || video.height == 0 || frame.width == 0 || frame.height == 0) return
            val (sx, sy) = MediaScaling.fitScale(
                frame.width.toFloat(), frame.height.toFloat(),
                video.width * video.pixelWidthHeightRatio, video.height.toFloat(),
                request.fit,
            )
            val width = (frame.width * sx).roundToInt()
            val height = (frame.height * sy).roundToInt()
            if (texture.layoutParams.width == width && texture.layoutParams.height == height) return
            texture.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> frame.post { fit() } }

        // ExoPlayer's default video buffer can reach 128 MB, which these frames can't spare.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(VIDEO_BUFFER_MIN_MS, VIDEO_BUFFER_MAX_MS, VIDEO_START_MS, VIDEO_START_MS)
            .setTargetBufferBytes(VIDEO_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val exo = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                video = videoSize
                fit()
            }

            override fun onRenderedFirstFrame() {
                texture.alpha = 1f
                frame.setBackgroundColor(Color.BLACK)
                scheduleTimeout(request)
            }

            override fun onPlayerError(error: PlaybackException) {
                fail(request.url, error.errorCodeName)
            }
        })
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.setVideoTextureView(texture)
        exo.setMediaItem(MediaItem.fromUri(request.url))
        exo.playWhenReady = !paused
        exo.prepare()
        player = exo
        layer.show(frame)
    }

    private fun scheduleTimeout(request: MediaRequest) {
        main.removeCallbacks(timeout)
        if (request.timeoutS > 0) main.postDelayed(timeout, request.timeoutS * 1000)
    }

    private fun fail(url: String, reason: String) {
        Log.w(TAG, "scene failed: $url: $reason")
        clear()
        onError(url, reason)
    }

    companion object {
        private const val TAG = "FrameAgent"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

        // Twice the panel's 1600x1200, about 15 MB once decoded.
        private const val MAX_IMAGE_PIXELS = 3_840_000L
        private const val VIDEO_BUFFER_MIN_MS = 2_500
        private const val VIDEO_BUFFER_MAX_MS = 10_000
        private const val VIDEO_START_MS = 1_000
        private const val VIDEO_BUFFER_BYTES = 8 * 1024 * 1024
    }
}
