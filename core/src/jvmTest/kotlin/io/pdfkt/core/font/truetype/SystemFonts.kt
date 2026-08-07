/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locally installed font programs (`fonts-dejavu-core` and
 * `fonts-noto-color-emoji` on Debian), for the tests that need a font
 * somebody else wrote.
 *
 * No font binary is committed: the fonts are read from the system, and a
 * missing one fails the test rather than skipping it — a check that quietly
 * ran over nothing would report a font as verified when it was verified
 * against nothing.
 *
 * @param fileName the file to read, e.g. `DejaVuSans.ttf`.
 * @param why what is left unchecked if the font is missing, for the message a
 *   developer without the package installed will read.
 * @param debianPackage the package that installs [fileName], named in that same
 *   message.
 */
internal fun systemFontBytes(fileName: String, why: String, debianPackage: String = "fonts-dejavu-core"): ByteArray {
    val path = FONT_DIRECTORIES.map { Path.of(it, fileName) }.firstOrNull { Files.isReadable(it) }
    requireNotNull(path) {
        "$fileName is not installed. $why against a real font program rather than a " +
            "committed binary, and skipping would report it as checked against a real font when it " +
            "was checked against nothing. Install the $debianPackage package, or point " +
            "PDFKT_TEST_FONT_DIR at a directory holding the file; the paths tried " +
            "were ${FONT_DIRECTORIES.joinToString(", ")}"
    }
    return Files.readAllBytes(path)
}

/**
 * Where the font packages put their files, after the `PDFKT_TEST_FONT_DIR`
 * override. More than one path is tried because a Debian layout and a plain
 * one differ; none of them is a fallback to a *different* font.
 */
private val FONT_DIRECTORIES: List<String> = listOfNotNull(
    System.getenv("PDFKT_TEST_FONT_DIR"),
    "/usr/share/fonts/truetype/dejavu",
    "/usr/share/fonts/truetype/noto",
    "/usr/share/fonts/dejavu",
    "/usr/share/fonts/noto",
    "/usr/share/fonts/TTF",
)
