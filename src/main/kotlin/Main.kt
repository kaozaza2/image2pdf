package me.mikore

import com.appmattus.crypto.Algorithm
import com.lowagie.text.Document
import com.lowagie.text.Image
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.system.exitProcess

class Counter {
    var count = 0
        private set

    fun tap() = ++count
}

fun main() {
    while (true) {
        print("\u001b[H\u001b[2J")
        println("image2pdf")
        println("------")
        println(" [1] - create")
        println(" [2] - copy")
        println(" [0] - exit")
        println()

        print("choice: ")
        val choice = readlnOrNull()?.toInt()

        when (choice) {
            0 -> break
            1 -> createTask()
            2 -> copyTask()
            else -> {
                println("Invalid choice, please enter again.")
                Thread.sleep(1000)
            }
        }
    }
}

fun readInput(message: String): String {
    print(message)
    val read = readlnOrNull()

    if (read.isNullOrEmpty()) {
        println("please fill input.")
        exitProcess(1)
    }

    return read
}

fun copyTask() {

    val from = readInput("from: ")
    val to = readInput("to: ")

    val semaphore = Semaphore(8)
    val counter = Counter()

    runBlocking(Dispatchers.IO) {

        val sps = File(from).files { it.isDirectory }
        val dps = File(to).files { it.isDirectory }.toMutableList()

        sps.forEach { sp ->
            val dp = sp.destination(dps) ?: File(to, sp.name).also {
                println("create project ${sp.name} in destination path.")

                it.mkdirs()
                dps.add(it)
            }

            sp.files { it.isFile }.map { ep ->
                async {
                    try {
                        semaphore.acquire()

                        val dep = File(dp, ep.name)

                        if (dep.exists()) {
                            val sc = ep.checksum()
                            val dc = dep.checksum()

                            if (sc == dc) {
                                println("skip: ${dep.path} exists.")

                                val sd = ep.lastModified()
                                val dd = dep.lastModified()

                                if (sd != dd) {
                                    println("${dep.path} mismatched last modified date, updating...")
                                    dep.setLastModified(sd)
                                }

                                return@async
                            }

                            dep.delete()
                        }

                        Files.copy(
                            ep.toPath(),
                            dep.toPath(),
                            StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING
                        )

                        println("copied: ${ep.path} -> ${dep.path}")

                        counter.tap()
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
    }

    println("${counter.count} file(s) copied.")

    exitProcess(0)
}

fun createTask() {

    val src = readInput("src: ")
    val dst = readInput("dst: ")

    val semaphore = Semaphore(8)
    val counter = Counter()

    try {
        runBlocking(Dispatchers.IO) {
            val projects = File(src).files { it.isDirectory }

            projects.map { project ->
                project.name to project.files { file -> file.isDirectory }
            }.forEach { (name, eps) ->
                eps.map {
                    async {
                        semaphore.acquire()

                        try {
                            val images = it.imageFiles()
                            val out = File(dst, name)

                            val pdf = File(out, "${it.name}.pdf")
                            create(images, pdf)

                            println("created: ${pdf.path}")
                            counter.tap()
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
        }

        println("total pdf created: ${counter.count}")
    } catch (e: Exception) {
        e.printStackTrace()
    }

    exitProcess(0)
}

fun File.destination(list: Collection<File>): File? {
    val id = name.substring(0, 4).trim()

    if (id.length != 4) {
        throw Exception("Invalid source project id: found $id")
    }

    return list.find { it.name.trim().startsWith(id) }
}

fun File.checksum(): String? {
    if (!exists()) return null

    val md = Algorithm.XXH3_64().createDigest()

    // Read through stream buffer instead readAllBytes to prevent memory leaks
    inputStream().use { fis ->
        val buffer = ByteArray(8192)
        var bufferRead = fis.read(buffer)

        while (bufferRead != -1) {
            md.update(buffer, 0, bufferRead)
            bufferRead = fis.read(buffer)
        }
    }

    return md.digest().joinToString { "%02x".format(it) }
}

fun File.imageFiles(): Array<File> {
    val jpgPaths = listOf("jpg", "jpeg", "JPG", "JPEG")

    val jpgPath = jpgPaths
        .map { path -> File(this, path) }
        .find { it.exists() && it.isDirectory }

    requireNotNull(jpgPath) { "Not found or invalid jpg path inside episode dir." }

    val jpgFiles = jpgPath.listFiles { _, name ->
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)
    }

    return jpgFiles ?: emptyArray()
}

fun File.files(filter: ((File) -> Boolean)? = null): Array<File> {
    val files = listFiles() ?: return emptyArray()

    return if (filter != null) {
        files.filter(filter).toTypedArray()
    } else {
        files
    }
}

val windowsComparator = Comparator<File> { a, b ->
    val aName = a.name.substringBeforeLast(".")
    val bName = b.name.substringBeforeLast(".")

    // Natural order sort strings 001-1 comes before 001,
    // but 001 should comes before 001-1, let's fix.
    when {
        bName.length > aName.length && bName.startsWith(aName) -> 1
        else -> naturalOrder<String>().compare(aName, bName)
    }
}

fun create(images: Array<File>, out: File) {
    images.sortWith(windowsComparator)

    Document().use { document ->
        PdfWriter.getInstance(document, FileOutputStream(out))
        document.open()

        for (image in images) {
            val bImage = ImageIO.read(image)
            val iImage = Image.getInstance(bImage, null)

            document.pageSize = Rectangle(iImage.width, iImage.height)
            document.setMargins(0f, 0f, 0f, 0f)
            document.newPage()

            document.add(iImage)
        }
    }
}
