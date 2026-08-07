/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts) (PDF 1.0-1.7)";
// Adobe Font Metrics File Format Specification, technical note #5004

/**
 * The copy against the original.
 *
 * `Standard14Afm.kt` carries the fourteen AFM files reduced — the header
 * verbatim, each character metric cut down to its `C`, `WX` and `N` fields, the
 * kerning and composite sections dropped, as the notice at the top of the
 * generated file records. Reducing published metrics by machine is
 * only safe if the result is checked against what was published, so this test
 * parses Adobe's unmodified files out of third_party/adobe-core14-afm/ and
 * compares every single number the reduced copy claims to carry.
 *
 * It is a JVM test because it reads files, and files are exactly what commonMain
 * cannot reach — the reason the metrics are compiled into the source in the
 * first place. It also parses the originals whole, kerning and composites
 * included, so [AfmFont] is exercised against real AFM files and not only
 * against the reduced form it is fed at runtime.
 */
class Standard14AfmSourceTest {
    /**
     * Every character of every one of the fourteen: same count, same codes, same
     * glyph names, same widths, in the same order.
     */
    @Test
    fun `the compiled-in metrics are the metrics Adobe published`() {
        for (name in Standard14Name.entries) {
            val published = AfmFont.parse(Files.readString(afmFileOf(name)))
            val compiledIn = AfmFont.parse(standard14AfmLines(name))

            assertEquals(published.fontName, compiledIn.fontName)
            assertEquals(published.fontBBox.llx, compiledIn.fontBBox.llx, "FontBBox llx of $name")
            assertEquals(published.fontBBox.lly, compiledIn.fontBBox.lly, "FontBBox lly of $name")
            assertEquals(published.fontBBox.urx, compiledIn.fontBBox.urx, "FontBBox urx of $name")
            assertEquals(published.fontBBox.ury, compiledIn.fontBBox.ury, "FontBBox ury of $name")
            assertEquals(published.capHeight, compiledIn.capHeight, "CapHeight of $name")
            assertEquals(published.xHeight, compiledIn.xHeight, "XHeight of $name")
            assertEquals(published.ascender, compiledIn.ascender, "Ascender of $name")
            assertEquals(published.descender, compiledIn.descender, "Descender of $name")

            assertEquals(
                published.charMetrics.size,
                compiledIn.charMetrics.size,
                "number of character metrics of $name",
            )
            for ((index, expected) in published.charMetrics.withIndex()) {
                val actual = compiledIn.charMetrics[index]
                assertEquals(expected.code, actual.code, "character $index of $name: C")
                assertEquals(expected.glyphName, actual.glyphName, "character $index of $name: N")
                assertEquals(expected.width, actual.width, "character $index of $name: WX")
            }
        }
    }

    /**
     * Adobe's licence for these files requires that the copyright notices be
     * retained and that the licence itself accompany them wherever they go. The
     * files here are unmodified and keep their own notices; the generated Kotlin
     * is a modified copy, so it has to carry the licence text, the modification
     * notice, and every file's notice lines.
     *
     * A licence obligation that only lives in a review comment is one refactor
     * away from being gone, so it is asserted.
     */
    @Test
    fun `the generated source carries Adobe's licence and notices`() {
        val generated = Files.readString(repositoryRoot().resolve(GENERATED))
        // The licence sits in a block comment, so its lines carry a " * "
        // margin and wrap where the margin leaves room. Compare the words.
        val unwrapped = generated.replace("\n", " ").replace("*", " ").replace(Regex("\\s+"), " ")
        val licence = Files.readString(repositoryRoot().resolve("$ADOBE_FILES/MustRead.html"))
            .substringAfter("<td width=\"300\">")
            .substringBefore("<font color=\"white\">")
            .replace(Regex("\\s+"), " ")
            .trim()

        assertTrue(licence.startsWith("This file and the 14 PostScript(R) AFM files"), licence)
        assertTrue(
            unwrapped.contains(licence),
            "the generated source must carry Adobe's licence for the AFM files verbatim:\n$licence",
        )
        assertTrue(
            generated.contains("MODIFICATION NOTICE"),
            "the licence requires modifications to be prominently noted",
        )

        for (name in Standard14Name.entries) {
            val notice = Files.readAllLines(afmFileOf(name)).first { it.startsWith("Notice ") }.trim()
            assertTrue(
                standard14AfmLines(name).any { it.trim() == notice },
                "the copyright notice of ${name.baseFont.name} must be retained",
            )
        }
    }

    /** Adobe's file for one of the fourteen: the `FontName`, which is also the `/BaseFont`. */
    private fun afmFileOf(name: Standard14Name): Path =
        repositoryRoot().resolve("$ADOBE_FILES/${name.baseFont.name}.afm")

    /**
     * The repository root, from wherever Gradle chose to run the tests: `:core`
     * by default, the root when a test is launched from an IDE.
     */
    private fun repositoryRoot(): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()
        while (directory != null) {
            if (Files.isDirectory(directory.resolve(ADOBE_FILES))) return directory
            directory = directory.parent
        }
        throw AssertionError(
            "no $ADOBE_FILES above ${Paths.get("").toAbsolutePath()} — Adobe's AFM files are the " +
                "source the compiled-in metrics are generated from and cannot be checked without them",
        )
    }

    private companion object {
        const val ADOBE_FILES = "third_party/adobe-core14-afm"
        const val GENERATED = "core/src/commonMain/kotlin/io/pdfkt/core/font/Standard14Afm.kt"
    }
}
