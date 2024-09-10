package com.example.video_compress

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.abedelazizshe.lightcompressorlibrary.config.CacheStorageConfiguration
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.otaliastudios.transcoder.internal.utils.Logger
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.Registrar
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * VideoCompressPlugin
 */
class VideoCompressPlugin : MethodCallHandler, FlutterPlugin {


    private var _context: Context? = null
    private var _channel: MethodChannel? = null
    private val TAG = "VideoCompressPlugin"
    private val LOG = Logger(TAG)
    var channelName = "video_compress"

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val context = _context;
        val channel = _channel;

        if (context == null || channel == null) {
            Log.w(TAG, "Calling VideoCompress plugin before initialization")
            return
        }

        when (call.method) {
            "getMediaInfo" -> {
                val path = call.argument<String>("path")
                result.success(getMediaInfoJson(context, path!!).toString())
            }

            "setLogLevel" -> {
                val logLevel = call.argument<Int>("logLevel")!!
                Logger.setLogLevel(logLevel)
                result.success(true);
            }

            "cancelCompression" -> {
                VideoCompressor.cancel()
                result.success(false);
            }

            "compressVideo" -> {
                try {
                    val path = call.argument<String>("path")!!
                    val tempDir: String = context.cacheDir!!.absolutePath
                    Log.e("VideoCompressPlugin", "path cache dir $tempDir")
                    val destFile = File(tempDir, "VID_${UUID.randomUUID()}${path.hashCode()}.mp4")
                    val destPath: String = destFile.absolutePath

                    Log.e("VideoCompressPlugin", "dest dir $destPath")

                    val file = File(path)
                    if (!file.exists()) {
                        Log.e(TAG, "El archivo de entrada no existe: $path")
                        result.error(
                            "FILE_NOT_FOUND",
                            "El archivo de entrada no existe en la ruta: $path",
                            null
                        )
                    }

                    Logger.setLogLevel(Logger.LEVEL_VERBOSE)
                    Log.e(TAG, "init compress native kotlon $destPath")

                    val cacheDir = context.cacheDir
                    if (!cacheDir.canWrite()) {
                        Log.e(TAG, "No se puede escribir en el directorio de caché")
                    } else {
                        Log.e(
                            TAG,
                            "Se puede escribir en el directorio de caché: ${cacheDir.absolutePath}"
                        )
                    }

                    VideoCompressor.start(
                        context = context,
                        uris = listOf(Uri.fromFile(file)),
                        isStreamable = false,
                        storageConfiguration = CacheStorageConfiguration(),
                        configureWith = Configuration(
                            quality = VideoQuality.LOW,
                            isMinBitrateCheckEnabled = true,
                            videoBitrateInMbps = 5,
                            disableAudio = true,
                            videoNames = listOf("VID_${UUID.randomUUID()}${path.hashCode()}.mp4")
                        ),
                        listener = object : CompressionListener {
                            override fun onProgress(index: Int, percent: Float) {
                                Log.e(TAG, "onProgress $percent")
                                channel.invokeMethod("updateProgress", percent * 100.00)
                            }

                            override fun onStart(index: Int) {
                                Log.e(TAG, "onSuccess $index")
                            }

                            override fun onSuccess(index: Int, size: Long, path: String?) {
                                channel.invokeMethod("updateProgress", 100.00)
                                val json = getMediaInfoJson(context, destPath)
                                json.put("isCancel", false)
                                result.success(json.toString())
                                Log.e(TAG, "onSuccess $path $path")
                            }

                            override fun onFailure(index: Int, failureMessage: String) {
                                Log.e(TAG, "onFailure $failureMessage")
                            }

                            override fun onCancelled(index: Int) {
                                Log.e(TAG, "onCancelled")
                            }
                        }
                    )
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    Log.e(TAG, "Error al iniciar la transcodificación: ${ex.message}")
                    result.error(
                        "TRANSCODE_INIT_FAILED",
                        "Fallo en la inicialización de la transcodificación: ${ex.message}",
                        null
                    )
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

    private fun getMediaInfoJson(context: Context, path: String): JSONObject {
        val file = File(path)
        val retriever = MediaMetadataRetriever()

        retriever.setDataSource(context, Uri.fromFile(file))

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
        val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR) ?: ""
        val duration = java.lang.Long.parseLong(durationStr)
        val filesize = file.length()
        retriever.release()

        val json = JSONObject()

        json.put("path", path)
        json.put("title", title)
        json.put("author", author)
        json.put("duration", duration)
        json.put("filesize", filesize)

        return json
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
