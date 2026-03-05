package com.scrollsnap.core.shizuku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

data class ShellBinaryResult(
    val command: String,
    val exitCode: Int,
    val stdoutBytes: ByteArray,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

class ShizukuShellExecutor(
    private val shizukuManager: ShizukuManager
) {

    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        require(shizukuManager.status.value.isBinderAvailable) { "Shizuku binder is unavailable." }
        require(shizukuManager.status.value.isPermissionGranted) { "Shizuku permission is not granted." }

        val process = startShizukuProcess(command)
        val (stdout, stderr, exitCode) = readProcessText(process, COMMAND_TIMEOUT_MS)

        ShellResult(
            command = command,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr
        )
    }

    suspend fun execBinary(command: String): ShellBinaryResult = withContext(Dispatchers.IO) {
        require(shizukuManager.status.value.isBinderAvailable) { "Shizuku binder is unavailable." }
        require(shizukuManager.status.value.isPermissionGranted) { "Shizuku permission is not granted." }

        val process = startShizukuProcess(command)
        val (stdoutBytes, stderr, exitCode) = readProcessBinary(process, COMMAND_TIMEOUT_MS)

        ShellBinaryResult(
            command = command,
            exitCode = exitCode,
            stdoutBytes = stdoutBytes,
            stderr = stderr
        )
    }

    private fun startShizukuProcess(command: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null
        )
        return process as Process
    }

    private suspend fun readProcessText(
        process: Process,
        timeoutMs: Long
    ): Triple<String, String, Int> = withTimeout(timeoutMs) {
        val stdoutDeferred = async(Dispatchers.IO) {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use {
                it.readText().trim()
            }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).use {
                it.readText().trim()
            }
        }
        val exitCode = waitForProcess(process)
        Triple(stdoutDeferred.await(), stderrDeferred.await(), exitCode)
    }

    private suspend fun readProcessBinary(
        process: Process,
        timeoutMs: Long
    ): Triple<ByteArray, String, Int> = withTimeout(timeoutMs) {
        val stdoutDeferred = async(Dispatchers.IO) {
            process.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                val output = ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8)).use {
                it.readText().trim()
            }
        }
        val exitCode = waitForProcess(process)
        Triple(stdoutDeferred.await(), stderrDeferred.await(), exitCode)
    }

    private fun waitForProcess(process: Process): Int {
        return try {
            process.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { process.destroyForcibly() }
            -1
        } catch (_: Throwable) {
            runCatching { process.destroyForcibly() }
            -1
        }
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 45_000L
    }
}
