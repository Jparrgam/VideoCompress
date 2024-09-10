package com.example.video_compress

import android.content.Context
import android.net.Uri
import android.util.Log
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.source.TrimDataSource
import com.otaliastudios.transcoder.source.UriDataSource
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import com.otaliastudios.transcoder.strategy.RemoveTrackStrategy
import com.otaliastudios.transcoder.strategy.TrackStrategy
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import com.otaliastudios.transcoder.internal.utils.Logger
import com.otaliastudios.transcoder.source.FilePathDataSource
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future

/**
 * VideoCompressPlugin
 */
class VideoCompressPlugin : MethodCallHandler, FlutterPlugin {


    private var _context: Context? = null
    private var _channel: MethodChannel? = null
    private val TAG = "VideoCompressPlugin"
    private val LOG = Logger(TAG)
    private var transcodeFuture:Future<Void>? = null
    var channelName = "video_compress"

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val context = _context;
        val channel = _channel;

        if (context == null || channel == null) {
            Log.w(TAG, "Calling VideoCompress plugin before initialization")
            return
        }

        when (call.method) {
            "getByteThumbnail" -> {
                val path = call.argument<String>("path")
                val quality = call.argument<Int>("quality")!!
                val position = call.argument<Int>("position")!! // to long
                ThumbnailUtility(channelName).getByteThumbnail(path!!, quality, position.toLong(), result)
            }
            "getFileThumbnail" -> {
                val path = call.argument<String>("path")
                val quality = call.argument<Int>("quality")!!
                val position = call.argument<Int>("position")!! // to long
                ThumbnailUtility("video_compress").getFileThumbnail(context, path!!, quality,
                        position.toLong(), result)
            }
            "getMediaInfo" -> {
                val path = call.argument<String>("path")
                result.success(Utility(channelName).getMediaInfoJson(context, path!!).toString())
            }
            "deleteAllCache" -> {
                result.success(Utility(channelName).deleteAllCache(context, result));
            }
            "setLogLevel" -> {
                val logLevel = call.argument<Int>("logLevel")!!
                Logger.setLogLevel(logLevel)
                result.success(true);
            }
            "cancelCompression" -> {
                transcodeFuture?.cancel(true)
                result.success(false);
            }
            "compressVideo" -> {
                try {
                    val path = call.argument<String>("path")!!
                    val quality = call.argument<Int>("quality")!!
                    val deleteOrigin = call.argument<Boolean>("deleteOrigin")!!
                    val includeAudio = call.argument<Boolean>("includeAudio") ?: true
                    val frameRate = if (call.argument<Int>("frameRate")==null) 30 else call.argument<Int>("frameRate")

                    val tempDir: String = context.cacheDir!!.absolutePath
                    Log.e("VideoCompressPlugin", "path cache dir $tempDir")
                    val destFile = File(tempDir, "VID_${UUID.randomUUID()}${path.hashCode()}.mp4")
                    val destPath: String = destFile.absolutePath

                    Log.e("VideoCompressPlugin", "dest dir $destPath")
                    var videoTrackStrategy: TrackStrategy = DefaultVideoStrategy.atMost(340).build();
                    val audioTrackStrategy: TrackStrategy

                    when (quality) {

                        0 -> {
                            videoTrackStrategy = DefaultVideoStrategy.atMost(720).build()
                        }

                        1 -> {
                            videoTrackStrategy = DefaultVideoStrategy.atMost(360).build()
                        }
                        2 -> {
                            videoTrackStrategy = DefaultVideoStrategy.atMost(640).build()
                        }
                        3 -> {

                            assert(value = frameRate != null)
                            videoTrackStrategy = DefaultVideoStrategy.Builder()
                                .keyFrameInterval(3f)
                                .bitRate(1280 * 720 * 4.toLong())
                                .frameRate(frameRate!!) // will be capped to the input frameRate
                                .build()
                        }
                        4 -> {
                            videoTrackStrategy = DefaultVideoStrategy.atMost(480, 640).build()
                        }
                        5 -> {
                            videoTrackStrategy = DefaultVideoStrategy.atMost(540, 960).build()
                        }
                        6 -> {
                            videoTrackStrategy = DefaultVideoStrategy.atMost(720, 1280).build()
                        }
                        7 -> {
                            videoTrackStrategy = DefaultVideoStrategy.atMost(1080, 1920).build()
                        }
                    }

                    audioTrackStrategy = if (includeAudio) {
                        val sampleRate = DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT
                        val channels = DefaultAudioStrategy.CHANNELS_AS_INPUT

                        DefaultAudioStrategy.builder()
                            .channels(channels)
                            .sampleRate(sampleRate)
                            .build()
                    } else {
                        RemoveTrackStrategy()
                    }

                    val file = File(path)
                    if (!file.exists()) {
                        Log.e(TAG, "El archivo de entrada no existe: $path")
                        result.error("FILE_NOT_FOUND", "El archivo de entrada no existe en la ruta: $path", null)
                    }

                    Logger.setLogLevel(Logger.LEVEL_VERBOSE)
                    Log.e(TAG, "init compress native kotlon $destPath")

                    val cacheDir = context.cacheDir
                    if (!cacheDir.canWrite()) {
                        Log.e(TAG, "No se puede escribir en el directorio de caché")
                    } else {
                        Log.e(TAG, "Se puede escribir en el directorio de caché: ${cacheDir.absolutePath}")
                    }

                    transcodeFuture = Transcoder.into(destPath)
                        .addDataSource(FilePathDataSource(path))
                        .setAudioTrackStrategy(audioTrackStrategy)
                        .setVideoTrackStrategy(videoTrackStrategy)
                        .setListener(object : TranscoderListener {
                            override fun onTranscodeProgress(progress: Double) {
                                Log.e(TAG, "onTranscodeProgress $progress")
                                channel.invokeMethod("updateProgress", progress * 100.00)
                            }
                            override fun onTranscodeCompleted(successCode: Int) {
                                Log.e(TAG, "onTranscodeCompleted $successCode")
                                channel.invokeMethod("updateProgress", 100.00)
                                val json = Utility(channelName).getMediaInfoJson(context, destPath)
                                Log.e(TAG, "onTranscodeCompleted $json")
                                json.put("isCancel", false)
                                result.success(json.toString())
                                if (deleteOrigin) {
                                    File(path).delete()
                                }
                            }

                            override fun onTranscodeCanceled() {
                                Log.e(TAG, "onTranscodeCanceled")
                                result.success(null)
                            }

                            override fun onTranscodeFailed(exception: Throwable) {
                                exception.printStackTrace()
                                Log.e(TAG, "onTranscodeFailed: error converter file ${exception.message}")
                                result.success(null)
                            }
                        }).transcode()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    Log.e(TAG, "Error al iniciar la transcodificación: ${ex.message}")
                    result.error("TRANSCODE_INIT_FAILED", "Fallo en la inicialización de la transcodificación: ${ex.message}", null)
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        init(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        _channel?.setMethodCallHandler(null)
        _context = null
        _channel = null
    }

    private fun init(context: Context, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, channelName)
        channel.setMethodCallHandler(this)
        _context = context
        _channel = channel
    }

    companion object {
        private const val TAG = "video_compress"

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val instance = VideoCompressPlugin()
            instance.init(registrar.context(), registrar.messenger())
        }
    }

}
