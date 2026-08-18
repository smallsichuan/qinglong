package com.qinglong.panel.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

object EnvironmentChecker {
    private const val ROOTFS_DIR = "linux-rootfs"
    private const val PROOT_FILE = "proot"
    private const val READY_MARKER = "environment_ready"
    private const val APK_ARCHIVE = "linux-rootfs.tar.gz"
    private const val PROOT_ASSET = "proot-aarch64"
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun rootfs(context: Context) = File(context.filesDir, ROOTFS_DIR)
    private fun proot(context: Context) = File(context.filesDir, PROOT_FILE)

    fun isPRootInstalled(context: Context): Boolean =
        proot(context).canExecute() && File(rootfs(context), "bin/sh").isFile

    fun isNodeJSInstalled(context: Context): Boolean =
        isPRootInstalled(context) && File(rootfs(context), "usr/bin/node").canExecute()

    fun isQingLongInstalled(context: Context): Boolean {
        if (!isPRootInstalled(context)) return false
        val marker = File(context.filesDir, READY_MARKER)
        val candidates = listOf(
            File(rootfs(context), "usr/local/bin/qinglong"),
            File(rootfs(context), "usr/bin/qinglong")
        )
        return marker.exists() && candidates.any { it.isFile && it.canExecute() }
    }

    fun installPRoot(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            val ok = installPRootBlocking(context)
            mainHandler.post { callback(ok) }
        }.start()
    }

    fun installNodeJS(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            val ok = installPackages(context)
            mainHandler.post { callback(ok) }
        }.start()
    }

    fun installQingLong(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            val ok = installQingLongBlocking(context)
            mainHandler.post { callback(ok) }
        }.start()
    }

    fun initialize(context: Context, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                if (!isPRootInstalled(context) && !installPRootBlocking(context)) {
                    return@Thread callbackMain(callback, false, "Linux 环境安装失败")
                }

                if (!isNodeJSInstalled(context) && !installPackages(context)) {
                    return@Thread callbackMain(callback, false, "Node.js/Python 安装失败")
                }

                if (!isQingLongInstalled(context) && !installQingLongBlocking(context)) {
                    return@Thread callbackMain(callback, false, "青龙安装失败，请检查网络后重试")
                }

                callbackMain(callback, true, "环境就绪")
            } catch (e: Exception) {
                Timber.e(e, "Environment initialization failed")
                callbackMain(callback, false, e.message ?: "环境初始化失败")
            }
        }.start()
    }

    private fun installPRootBlocking(context: Context): Boolean {
        return try {
            val p = proot(context)
            if (!p.exists()) {
                context.assets.open(PROOT_ASSET).use { input ->
                    FileOutputStream(p).use { output -> input.copyTo(output) }
                }
            }
            p.setExecutable(true, false)

            val rf = rootfs(context)
            if (!File(rf, "bin/sh").isFile) {
                context.assets.open(APK_ARCHIVE).use { extractTarGz(it, rf) }
            }

            File(rf, "etc/resolv.conf").apply {
                parentFile?.mkdirs()
                writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }
            p.canExecute() && File(rf, "bin/sh").isFile
        } catch (e: Exception) {
            Timber.e(e, "Linux environment installation failed")
            false
        }
    }

    private fun installPackages(context: Context): Boolean {
        val command = """
            set -e
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            apk update
            apk add --no-cache nodejs npm python3 py3-pip bash curl ca-certificates git build-base linux-headers
            update-ca-certificates
            node --version
            npm --version
            python3 --version
        """.trimIndent()
        return runInLinux(context, command)
    }

    private fun installQingLongBlocking(context: Context): Boolean {
        val command = """
            set -e
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export HOME=/root
            export QL_DIR=/ql
            export QL_DATA_DIR=/ql/data
            export QL_PORT=5700
            mkdir -p /ql/data /ql/scripts /ql/log
            npm install -g @whyour/qinglong
            command -v qinglong
            qinglong --version
            touch /ql/data/.android-qinglong-ready
        """.trimIndent()

        val ok = runInLinux(context, command)
        if (ok) File(context.filesDir, READY_MARKER).writeText("ready\n")
        return ok && isQingLongInstalled(context)
    }

    fun runInLinux(context: Context, command: String): Boolean {
        val p = proot(context)
        val rf = rootfs(context)
        if (!p.canExecute() || !File(rf, "bin/sh").isFile) return false

        return try {
            val pb = ProcessBuilder(
                p.absolutePath,
                "--kill-on-exit",
                "-0",
                "-r", rf.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/root",
                "/bin/sh", "-lc", command
            )
            pb.environment()["HOME"] = "/root"
            pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            pb.environment()["LANG"] = "C.UTF-8"
            pb.environment()["LC_ALL"] = "C.UTF-8"
            pb.redirectErrorStream(true)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) Timber.e("Linux command failed ($code): $output")
            else Timber.i("Linux command completed: $output")
            code == 0
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute Linux command")
            false
        }
    }

    private fun callbackMain(callback: (Boolean, String) -> Unit, ok: Boolean, msg: String) {
        mainHandler.post { callback(ok, msg) }
    }

    private fun extractTarGz(input: java.io.InputStream, target: File) {
        target.mkdirs()
        TarArchiveInputStream(GZIPInputStream(input.buffered())).use { tar ->
            var entry = tar.nextTarEntry
            val root = target.canonicalFile
            while (entry != null) {
                val out = File(target, entry.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator) && out != root) {
                    throw SecurityException("Unsafe archive path")
                }
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        if (out.exists() || out.isSymbolicLink) out.delete()
                        Os.symlink(entry.linkName, out.path)
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                        val executable = (entry.mode and 64) != 0 ||
                            (entry.mode and 8) != 0 ||
                            (entry.mode and 1) != 0
                        out.setExecutable(executable, false)
                    }
                }
                entry = tar.nextTarEntry
            }
        }
    }
}
