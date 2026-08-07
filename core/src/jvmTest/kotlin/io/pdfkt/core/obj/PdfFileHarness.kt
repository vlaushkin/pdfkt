/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import io.pdfkt.core.file.PdfFileWriter
import io.pdfkt.core.file.TEST_FILE_IDENTIFIER
import io.pdfkt.core.file.pdfFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5 "File structure"

/**
 * A file around the objects under test, and qpdf pointed at it.
 *
 * Byte-level claims about a clause are asserted against the clause text in the
 * common tests; what those cannot show is that the bytes are what an
 * independent reader needs in order to find the objects again. qpdf is used as
 * a *tool* for that — its output is compared, never its source
 *
 * The file is written by [PdfFileWriter] (§7.5), which numbers [objects] from 1
 * in the order given and takes the first of them as the document catalog. A
 * test of one object type therefore runs through the writer as well, which is
 * the arrangement worth having: the writer is exercised by every file test in
 * the suite, and no test carries a second, hand-written idea of what a file
 * looks like that could drift from the one the library ships.
 */
internal fun minimalPdfFile(objects: List<PdfObject>): ByteArray = pdfFile { writer ->
    val ids = objects.map { writer.define(it) }
    writer.finish(root = ids.first(), fileIdentifier = TEST_FILE_IDENTIFIER)
}

/** Writes [document] to a temporary file for the duration of [block]. */
internal fun withPdfFile(document: ByteArray, block: (Path) -> Unit) {
    val path = Files.createTempFile("pdfkt", ".pdf")
    try {
        Files.write(path, document)
        block(path)
    } finally {
        Files.deleteIfExists(path)
    }
}

internal class QpdfRun(val status: Int, val bytes: ByteArray) {
    val output: String get() = bytes.decodeToString()
}

internal fun qpdf(vararg arguments: String): QpdfRun {
    val process = ProcessBuilder(listOf("qpdf") + arguments).redirectErrorStream(true).start()
    val bytes = process.inputStream.readBytes()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "qpdf did not finish")
    return QpdfRun(process.exitValue(), bytes)
}

/** `qpdf --check` found nothing to say about the file. */
internal fun assertQpdfCheckIsClean(path: Path) {
    val check = qpdf("--check", path.toString())
    assertEquals(0, check.status, "qpdf --check rejected the file:\n${check.output}")
    assertFalse(check.output.contains("WARNING"), "qpdf --check warned about the file:\n${check.output}")
}

/**
 * The text of [path], as pdftotext reads it, with the outer white-space cut.
 *
 * @param layout whether to ask the tool to keep the arrangement of the page.
 *   Off, it reports the text in the reading order it works out, which puts a
 *   column of list markers in one place and the lines they mark in another. On,
 *   it reconstructs the lines from where the marks actually are — which is what
 *   a test asking whether a *layout* came out in the order it was written wants,
 *   since the tool is then answering from the geometry rather than from a guess
 *   about it.
 */
internal fun extractedText(path: Path, layout: Boolean = false): String {
    val process =
        ProcessBuilder(listOfNotNull("pdftotext", "-q", if (layout) "-layout" else null, path.toString(), "-"))
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.readBytes().decodeToString()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "pdftotext did not finish")
    assertEquals(0, process.exitValue(), "pdftotext could not read the file:\n$output")
    return output.trim()
}

/**
 * Whether pdftotext is missing — the same arrangement as [skipWithoutQpdf], for
 * the same reason: the tool is a dependency of the check, not of the library.
 */
internal fun skipWithoutPdftotext(clause: String): Boolean {
    val installed =
        try {
            ProcessBuilder("pdftotext", "-v").redirectErrorStream(true).start().waitFor() == 0
        } catch (absent: IOException) {
            println("pdftotext could not be started: ${absent.message}")
            false
        }
    if (!installed) println("pdftotext is not installed — skipping the extraction check of $clause")
    return !installed
}

/**
 * Whether qpdf is missing, in which case the test that asked says so and stops.
 * The tool is an external dependency of the check, not of the library, and a
 * machine without it should still be able to run the suite; where it is
 * installed the assertions do run.
 */
internal fun skipWithoutQpdf(clause: String): Boolean {
    val installed =
        try {
            ProcessBuilder("qpdf", "--version").redirectErrorStream(true).start().waitFor() == 0
        } catch (absent: IOException) {
            println("qpdf could not be started: ${absent.message}")
            false
        }
    if (!installed) println("qpdf is not installed — skipping the structural check of $clause")
    return !installed
}
