/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.ImageBlock
import io.pdfkt.core.image.ImageFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// No clause applies: the DSL is this library's own design. The clauses the result reaches are
// :core's — §7.4.8 for the JPEG that is
// embedded undecoded and §7.4.4 for the PNG that is not.

/**
 * What `image()` puts in the block list: one [ImageBlock] per call, over a
 * reference the bytes themselves decided the format of.
 *
 * These are the questions that do not need a file. Which decoder ran is
 * legible in the reference — a JPEG keeps its own bytes under `/DCTDecode`, a
 * PNG is decoded and deflated — and so are the fit, the turn and the id an
 * image is named by. What the engine then does with the block is asserted on a
 * rendered page, in `RichImageGateTest`, because that is where it can be seen.
 */
class RichImageTest {

    /** A JPEG is taken, and its frame header is what the block's dimensions are. */
    @Test
    fun aJpegBecomesOneImageBlockEmbeddedUndecoded() {
        val document = document { image(testJpeg(width = 320, height = 200)) }

        val block = document.blocks.single()
        assertTrue(block is ImageBlock, "image() appended a ${block::class.simpleName}")
        assertEquals(ImageFilter.DCT_DECODE, block.image.filter, "the JPEG was not embedded under DCTDecode")
        assertEquals(320, block.image.width)
        assertEquals(200, block.image.height)
    }

    /** A PNG is taken too, and takes the other route into the file. */
    @Test
    fun aPngBecomesOneImageBlockDecodedToSamples() {
        val document = document { image(testPng(width = 8, height = 4)) }

        val block = document.blocks.single() as ImageBlock
        assertEquals(ImageFilter.FLATE_DECODE, block.image.filter, "the PNG was not written under FlateDecode")
        assertEquals(8, block.image.width)
        assertEquals(4, block.image.height)
    }

    /**
     * The defaults: an image keeps its aspect ratio and is not turned. Both are
     * the values a caller who writes `image(bytes)` and nothing else gets, and
     * `Contain` is the as-large-as-fits, aspect-ratio-kept rule.
     */
    @Test
    fun theDefaultsAreContainAndNoTurn() {
        val document = document { image(testJpeg(width = 100, height = 50)) }

        val block = document.blocks.single() as ImageBlock
        assertEquals(ImageFit.Contain, block.fit)
        assertEquals(0.0f, block.rotation)
    }

    /** What the caller asks for reaches the block: the fit and the turn, unchanged. */
    @Test
    fun theFitAndTheTurnReachTheBlock() {
        val document = document {
            image(testJpeg(width = 100, height = 50), fit = ImageFit.Stretch, rotationDegrees = 270)
        }

        val block = document.blocks.single() as ImageBlock
        assertEquals(ImageFit.Stretch, block.fit)
        assertEquals(270.0f, block.rotation)
    }

    /**
     * A turn that is not a quarter of a circle is refused, and the refusal says
     * what to write instead. The engine turns an image by any angle but fits it
     * to the column as though it had not been turned, so an image at 30° reaches
     * outside the column — which a caller may want, and may not stumble into.
     */
    @Test
    fun aTurnThatIsNotAQuarterOfACircleIsRefused() {
        val refused = assertFailsWith<IllegalArgumentException> {
            document { image(testJpeg(width = 100, height = 50), rotationDegrees = 30) }
        }

        assertTrue(refused.message.orEmpty().contains("ImageBlock"), "the refusal names no way to say it anyway")
    }

    /**
     * Two pictures are two images of the document, named apart — an id names one
     * image within a document, and two under one name is an error the catalog
     * raises rather than a document that draws the first one twice.
     */
    @Test
    fun twoPicturesAreTwoImagesNamedApart() {
        val document = document {
            image(testJpeg(width = 100, height = 50))
            image(testPng(width = 8, height = 4))
        }

        val ids = document.blocks.map { block -> (block as ImageBlock).image.id }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1], "two different pictures were given one id")
    }

    /**
     * The same array drawn twice is one image in the file, drawn twice: the
     * second call gets the reference the first built, so the catalog resolves one
     * XObject and the bytes are not decoded a second time.
     */
    @Test
    fun theSameArrayDrawnTwiceIsOneImage() {
        val logo = testPng(width = 4, height = 4)
        val document = document {
            image(logo)
            paragraph("Between them.")
            image(logo)
        }

        val drawn = document.blocks.filterIsInstance<ImageBlock>()
        assertEquals(2, drawn.size, "the second image() appended nothing")
        assertSame(drawn[0].image, drawn[1].image, "one array became two images of the document")
    }

    /** Bytes of neither format are refused where they were supplied, not written. */
    @Test
    fun bytesOfNeitherFormatAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            document { image(ByteArray(64) { 0x41 }) }
        }
    }

    /** An image sits in the flow where it was written, between its neighbours. */
    @Test
    fun anImageSitsWhereItWasWrittenInTheFlow() {
        val document = document {
            heading(level = 1, "Q4")
            paragraph("Revenue by month:")
            image(testJpeg(width = 320, height = 120))
            paragraph("Source: the ledger.")
        }

        val kinds = document.blocks.map { block -> block::class.simpleName }
        assertEquals(listOf("HeadingBlock", "ParagraphBlock", "ImageBlock", "ParagraphBlock"), kinds)
    }
}
