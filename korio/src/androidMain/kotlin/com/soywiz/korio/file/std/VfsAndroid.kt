package com.soywiz.korio.file.std

import androidContext
import com.soywiz.klock.*
import com.soywiz.kmem.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.*
import com.soywiz.korio.lang.Closeable
import com.soywiz.korio.stream.*
import com.soywiz.korio.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.io.*
import java.io.IOException
import java.net.*

private val absoluteCwd by lazy { File(".").absolutePath }
val tmpdir: String by lazy { System.getProperty("java.io.tmpdir") }

actual val resourcesVfs: VfsFile by lazy { ResourcesVfsProviderAndroid()().root.jail() }
actual val rootLocalVfs: VfsFile by lazy { localVfs(absoluteCwd) }
actual val applicationVfs: VfsFile by lazy { localVfs(absoluteCwd) }
actual val applicationDataVfs: VfsFile by lazy { localVfs(absoluteCwd) }
actual val cacheVfs: VfsFile by lazy { MemoryVfs() }
actual val externalStorageVfs: VfsFile by lazy { localVfs(absoluteCwd) }
actual val userHomeVfs: VfsFile by lazy { localVfs(absoluteCwd) }
actual val tempVfs: VfsFile by lazy { localVfs(tmpdir) }

actual fun localVfs(path: String): VfsFile = LocalVfsJvm()[path]

// Extensions
operator fun LocalVfs.Companion.get(base: File) = localVfs(base)

fun localVfs(base: File): VfsFile = localVfs(base.absolutePath)
fun jailedLocalVfs(base: File): VfsFile = localVfs(base.absolutePath).jail()
suspend fun File.open(mode: VfsOpenMode) = localVfs(this).open(mode)
fun File.toVfs() = localVfs(this)
fun UrlVfs(url: URL): VfsFile = UrlVfs(url.toString())

private class ResourcesVfsProviderAndroid {
	operator fun invoke(): Vfs {
		val merged = MergedVfs()

		return object : Vfs.Decorator(merged.root) {
			override suspend fun init() = run { merged += AndroidResourcesVfs(androidContext()).root }
			override fun toString(): String = "ResourcesVfs"
		}
	}
}

class AndroidResourcesVfs(val context: android.content.Context) : Vfs() {
	override suspend fun open(path: String, mode: VfsOpenMode): AsyncStream {
		return readRange(path, LONG_ZERO_TO_MAX_RANGE).openAsync(mode.cmode)
	}

	override suspend fun readRange(path: String, range: LongRange): ByteArray {
		//val path = "/assets/" + path.trim('/')
		val path = path.trim('/')

		val actualLength = context.assets.openFd(path).use {
			it.length
		}

		val endInclusive = kotlin.math.min(range.endInclusive, Long.MAX_VALUE - 1)
		val endExclusive = endInclusive + 1

		val start = range.start.clamp(0L, endExclusive)
		val end = endExclusive.clamp(0L, actualLength)

		val fs = context.assets.open(path)
		fs.skip(start)
		val len = (end - start).toInt()
		val out = ByteArray(len)
		var pos = 0
		while (pos < out.size) {
			val read = fs.read(out, pos, out.size - pos)
			if (read <= 0) break
			pos += read
		}
		return out
	}
}


//private val IOContext by lazy { newSingleThreadContext("IO") }
private val IOContext by lazy { Dispatchers.Unconfined }

private class LocalVfsJvm : LocalVfs() {
	val that = this
	override val absolutePath: String = ""

	private suspend inline fun <T> executeIo(crossinline callback: suspend () -> T): T =
		withContext(IOContext) { callback() }
	//private suspend inline fun <T> executeIo(callback: suspend () -> T): T = callback()

	fun resolve(path: String) = path
	fun resolveFile(path: String) = File(resolve(path))

	override suspend fun exec(
		path: String,
		cmdAndArgs: List<String>,
		env: Map<String, String>,
		handler: VfsProcessHandler
	): Int = executeIo {
		val actualCmd = if (OS.isWindows) listOf("cmd", "/c") + cmdAndArgs else cmdAndArgs
		val pb = ProcessBuilder(actualCmd)
		pb.environment().putAll(LinkedHashMap())
		pb.directory(resolveFile(path))

		val p = pb.start()
		var closing = false
		while (true) {
			val o = p.inputStream.readAvailableChunk(readRest = closing)
			val e = p.errorStream.readAvailableChunk(readRest = closing)
			if (o.isNotEmpty()) handler.onOut(o)
			if (e.isNotEmpty()) handler.onErr(e)
			if (closing) break
			if (o.isEmpty() && e.isEmpty() && !p.isAliveJre7) {
				closing = true
				continue
			}
			Thread.sleep(1L)
		}
		p.waitFor()
		//handler.onCompleted(p.exitValue())
		p.exitValue()
	}

	private val Process.isAliveJre7: Boolean
		get() = try {
			exitValue()
			false
		} catch (e: IllegalThreadStateException) {
			true
		}


	private fun InputStream.readAvailableChunk(readRest: Boolean): ByteArray {
		val out = ByteArrayOutputStream()
		while (if (readRest) true else available() > 0) {
			val c = this.read()
			if (c < 0) break
			out.write(c)
		}
		return out.toByteArray()
	}

	private fun InputStreamReader.readAvailableChunk(i: InputStream, readRest: Boolean): String {
		val out = java.lang.StringBuilder()
		while (if (readRest) true else i.available() > 0) {
			val c = this.read()
			if (c < 0) break
			out.append(c.toChar())
		}
		return out.toString()
	}

	override suspend fun readRange(path: String, range: LongRange): ByteArray = executeIo {
		RandomAccessFile(resolveFile(path), "r").use { raf ->
			val fileLength = raf.length()
			val start = kotlin.math.min(range.start, fileLength)
			val end = kotlin.math.min(range.endInclusive, fileLength - 1) + 1
			val totalRead = (end - start).toInt()
			val out = ByteArray(totalRead)
			raf.seek(start)
			val read = raf.read(out)
			if (read != totalRead) out.copyOf(read) else out
		}
	}

	@Suppress("BlockingMethodInNonBlockingContext")
	override suspend fun open(path: String, mode: VfsOpenMode): AsyncStream {
		val raf = executeIo {
			val file = resolveFile(path)
			if (file.exists() && (mode == VfsOpenMode.CREATE_NEW)) {
				throw IOException("File $file already exists")
			}
			RandomAccessFile(
				file, when (mode) {
					VfsOpenMode.READ -> "r"
					VfsOpenMode.WRITE -> "rw"
					VfsOpenMode.APPEND -> "rw"
					VfsOpenMode.CREATE -> "rw"
					VfsOpenMode.CREATE_NEW -> "rw"
					VfsOpenMode.CREATE_OR_TRUNCATE -> "rw"
				}
			).apply {
				if (mode.truncate) {
					setLength(0L)
				}
				if (mode == VfsOpenMode.APPEND) {
					seek(length())
				}
			}
		}

		return object : AsyncStreamBase() {
			override suspend fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = executeIo {
				raf.seek(position)
				raf.read(buffer, offset, len)
			}

			override suspend fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) = executeIo {
				raf.seek(position)
				raf.write(buffer, offset, len)
			}

			override suspend fun setLength(value: Long): Unit = executeIo {
				raf.setLength(value)
			}

			override suspend fun getLength(): Long = executeIo { raf.length() }
			override suspend fun close() = raf.close()

			override fun toString(): String = "$that($path)"
		}.toAsyncStream()
	}

	override suspend fun setSize(path: String, size: Long): Unit = executeIo {
		val file = resolveFile(path)
		FileOutputStream(file, true).channel.use { outChan ->
			outChan.truncate(size)
		}
		Unit
	}

	override suspend fun stat(path: String): VfsStat = executeIo {
		val file = resolveFile(path)
		val fullpath = "$path/${file.name}"
		if (file.exists()) {
			val lastModified = DateTime.fromUnix(file.lastModified())
			createExistsStat(
				fullpath,
				isDirectory = file.isDirectory,
				size = file.length(),
				createTime = lastModified,
				modifiedTime = lastModified,
				lastAccessTime = lastModified
			)
		} else {
			createNonExistsStat(fullpath)
		}
	}

	override suspend fun list(path: String): ReceiveChannel<VfsFile> =
		executeIo { (File(path).listFiles() ?: arrayOf()).map { that.file("$path/${it.name}") }.toChannel() }

	override suspend fun mkdir(path: String, attributes: List<Attribute>): Boolean =
		executeIo { resolveFile(path).mkdirs() }

	override suspend fun touch(path: String, time: DateTime, atime: DateTime): Unit =
		executeIo { resolveFile(path).setLastModified(time.unixMillisLong); Unit }

	override suspend fun delete(path: String): Boolean = executeIo { resolveFile(path).delete() }
	override suspend fun rmdir(path: String): Boolean = executeIo { resolveFile(path).delete() }
	override suspend fun rename(src: String, dst: String): Boolean =
		executeIo { resolveFile(src).renameTo(resolveFile(dst)) }

	override suspend fun watch(path: String, handler: (FileEvent) -> Unit): Closeable = TODO()

	override fun toString(): String = "LocalVfs"
}