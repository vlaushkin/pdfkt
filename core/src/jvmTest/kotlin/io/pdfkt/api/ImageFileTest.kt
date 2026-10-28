/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import kotlinx.io.files.Path
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.8.2 "Stream extent" — the /Length a JPEG copied from a
// file is written under; §7.4.8 "DCTDecode filter", which is why the file's
// bytes are the stream. Reading pictures from files is this library's own design.

/**
 * `image(path)`: a picture the document reads from a file, of which a JPEG is
 * held only as its frame header until the PDF is written.
 */
class ImageFileTest {
    @TempDir
    lateinit var dir: File

    @Test
    @DisplayName("a JPEG and a PNG added by path write the PDF their bytes write")
    fun pathAndBytesWriteTheSameFile() {
        val jpeg = testJpeg(640, 480)
        val png = testPng(3, 2)
        val jpegFile = File(dir, "photo.jpg").apply { writeBytes(jpeg) }
        val pngFile = File(dir, "icon.png").apply { writeBytes(png) }

        val byBytes = album {
            image(jpeg)
            image(png)
        }
        val byPath = album {
            image(Path(jpegFile.path))
            image(Path(pngFile.path))
        }

        assertContentEquals(byBytes.toByteArray(), byPath.toByteArray())
    }

    @Test
    @DisplayName("a frame header past 128 KB of application segments is found")
    fun aHeaderPastTheFirstGuessIsFound() {
        val jpeg = File(dir, "exif.jpg").apply { writeBytes(withApplicationSegments(testJpeg(321, 123), 4)) }

        val page = document {
            clock = PINNED_CLOCK
            margins = Margins.Fixed(0.pt, 0.pt, 0.pt, 0.pt)
            pageSize = PageSize.Custom(321.pt, 123.pt)
            image(Path(jpeg.path))
        }.toByteArray().decodeToString()

        assertTrue("/Width 321" in page && "/Height 123" in page, "the image dictionary is not 321 x 123")
        assertTrue("/Length ${jpeg.length()}" in page, "the stream is not the whole file")
    }

    @Test
    @DisplayName("the same path in the document and in a section is one image")
    fun theSamePathIsOneImage() {
        val photo = File(dir, "photo.jpg").apply { writeBytes(testJpeg(64, 48)) }
        val pdf = document {
            clock = PINNED_CLOCK
            image(Path(photo.path))
            section(PageSize.Custom(64.pt, 48.pt), margins = Margins.Fixed(0.pt, 0.pt, 0.pt, 0.pt)) {
                image(Path(photo.path))
            }
        }.toByteArray().decodeToString()

        assertEquals(1, Regex("/Subtype ?/Image").findAll(pdf).count())
    }

    @Test
    @DisplayName("a file that changed after the document was built is refused when it is written")
    fun aChangedFileIsRefused() {
        val photo = File(dir, "photo.jpg").apply { writeBytes(testJpeg(64, 48)) }
        val doc = album { image(Path(photo.path)) }
        photo.appendBytes(ByteArray(10))

        assertFailsWith<IllegalStateException> { doc.toByteArray() }
    }

    @Test
    @DisplayName("no file at the path is refused when the picture is added")
    fun aMissingFileIsRefused() {
        assertFailsWith<IllegalArgumentException> { album { image(Path(File(dir, "absent.jpg").path)) } }
    }

    /**
     * The acceptance test of the whole feature, in a JVM of its own with a heap
     * of 64 MB: ten 12 MB photographs, 120 MB of them, written by path to a file.
     * The same ten by bytes run out of memory in the same heap — which is what
     * says the first run proves something.
     */
    @Test
    @DisplayName("photographs added by path are written in a heap smaller than they are")
    fun photographsByPathFitInASmallHeap() {
        val photos = (1..PHOTOS).map { index ->
            File(dir, "photo-$index.jpg").apply {
                outputStream().use { out ->
                    out.write(testJpeg(4000, 3000))
                    val filler = ByteArray(1 shl 20) { (it + index).toByte() }
                    repeat(PHOTO_MEGABYTES) { out.write(filler) }
                }
            }
        }

        val byPath = probe("path", photos)
        assertEquals(0, byPath.first, "by path, in a 64 MB heap:\n${byPath.second}")
        assertTrue(File(dir, "out-path.pdf").length() > PHOTOS.toLong() * PHOTO_MEGABYTES * (1 shl 20))

        val byBytes = probe("bytes", photos)
        assertNotEquals(0, byBytes.first, "by bytes the same photographs fit too, so the heap proves nothing")
        assertTrue("OutOfMemoryError" in byBytes.second, byBytes.second)
    }

    private fun probe(mode: String, photos: List<File>): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-Xmx64m",
            "-cp",
            System.getProperty("java.class.path"),
            "io.pdfkt.api.ImageFileMemoryProbe",
            mode,
            File(dir, "out-$mode.pdf").path,
            *photos.map { it.path }.toTypedArray(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "the probe did not finish" }
        return process.exitValue() to output
    }

    private fun album(block: DocumentScope.() -> Unit): Document = document {
        clock = PINNED_CLOCK
        block()
    }

    /**
     * [jpeg] with [count] APP1 segments of the largest size a segment has put
     * after its SOI, as a camera puts its Exif data there — so its frame header
     * is further into the file than the first read of it reaches.
     */
    private fun withApplicationSegments(jpeg: ByteArray, count: Int): ByteArray {
        val segment = ByteArray(MAX_SEGMENT + 2).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xE1.toByte()
            this[2] = (MAX_SEGMENT shr 8).toByte()
            this[3] = MAX_SEGMENT.toByte()
        }
        return jpeg.copyOfRange(0, 2) + (1..count).fold(ByteArray(0)) { all, _ -> all + segment } +
            jpeg.copyOfRange(2, jpeg.size)
    }

    private companion object {
        const val PHOTOS: Int = 10
        const val PHOTO_MEGABYTES: Int = 12

        /** A segment's length field counts itself and is 16 bits wide. */
        const val MAX_SEGMENT: Int = 0xFFFF
    }
}

/** The child JVM of [ImageFileTest.photographsByPathFitInASmallHeap]. */
object ImageFileMemoryProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val (mode, out) = args
        val photos = args.drop(2)
        val doc = document {
            clock = PINNED_CLOCK
            for (photo in photos) {
                section(PageSize.Custom(400.pt, 300.pt), margins = Margins.Fixed(0.pt, 0.pt, 0.pt, 0.pt)) {
                    if (mode == "path") image(Path(photo)) else image(File(photo).readBytes())
                }
            }
        }
        doc.writeTo(Path(out))
    }
}
