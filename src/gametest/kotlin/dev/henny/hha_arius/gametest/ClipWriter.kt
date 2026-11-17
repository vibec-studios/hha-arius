package dev.henny.hha_arius.gametest

import net.minecraft.client.texture.NativeImage
import org.lwjgl.system.MemoryUtil
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Schreibt Bildfolgen ohne Zwischen-PNGs direkt in ffmpeg (Rohbilder RGBA über stdin → H.264, fast verlustfrei).
 * Die Bilder kommen aus [net.minecraft.client.util.ScreenshotRecorder]-Rückrufen im Render-Thread; ein eigener Thread
 * schreibt sie in Reihenfolge weiter, die Warteschlange bremst den Render-Thread, wenn ffmpeg nicht nachkommt.
 */
class ClipWriter(private val file: File, private val fps: Int, private val ffmpeg: String) {

    private var proc: Process? = null
    private var stream: OutputStream? = null
    private var thread: Thread? = null
    private val queue = ArrayBlockingQueue<ByteArray>(20)
    private val count = AtomicInteger()
    var width = 0
        private set
    var height = 0
        private set
    var format: String = "?"
        private set

    val received: Int get() = count.get()

    private fun open(w: Int, h: Int) {
        width = w
        height = h
        file.parentFile.mkdirs()
        val p = ProcessBuilder(
            ffmpeg, "-y", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "${w}x$h", "-r", fps.toString(), "-i", "-",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "14", "-pix_fmt", "yuv420p",
            file.absolutePath,
        ).redirectErrorStream(true).redirectOutput(File(file.absolutePath + ".log")).start()
        proc = p
        val out = BufferedOutputStream(p.outputStream, 1 shl 22)
        stream = out
        thread = Thread({
            try {
                while (true) {
                    val frame = queue.take()
                    if (frame.isEmpty()) break
                    out.write(frame)
                }
            } catch (e: Exception) {
                System.err.println("[TrailerCapture] Schreibfehler ${file.name}: $e")
                // Weiter abnehmen, damit der Render-Thread nicht hängen bleibt.
                while (true) {
                    val frame = queue.take()
                    if (frame.isEmpty()) break
                }
            }
        }, "trailer-writer-${file.nameWithoutExtension}").also { it.isDaemon = true; it.start() }
    }

    /** Im Render-Thread: Bild kopieren, freigeben, einreihen. */
    fun push(image: NativeImage) {
        image.use { img ->
            if (proc == null) {
                format = img.format.name
                open(img.width, img.height)
            }
            if (img.width != width || img.height != height) {
                System.err.println("[TrailerCapture] Bildgröße geändert (${img.width}x${img.height}), Bild verworfen")
                return
            }
            val n = width * height * 4
            val frame = ByteArray(n)
            MemoryUtil.memByteBuffer(img.imageId(), n).get(frame)
            queue.put(frame)
            count.incrementAndGet()
        }
    }

    fun close() {
        val p = proc ?: return
        queue.put(ByteArray(0))
        thread?.join()
        stream?.close()
        p.waitFor()
    }
}
