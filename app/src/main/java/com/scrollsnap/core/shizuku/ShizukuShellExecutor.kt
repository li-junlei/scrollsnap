package com.scrollsnap.core.shizuku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.ByteArrayOutputStream

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
        val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.trim()
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }.trim()
        val exitCode = process.waitFor()

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
        val stdoutBytes = process.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            val output = ByteArrayOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }.trim()
        val exitCode = process.waitFor()

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
}
