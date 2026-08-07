/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

// ITU-T T.81 (09/92) | ISO/IEC 10918-1, the format the JDK's encoder writes
// ISO 32000-2:2020 §7.4.8 "DCTDecode filter", the format a PDF embeds

/**
 * JPEG files made when the test runs, by an encoder that never saw this library.
 *
 * The JDK's image writer is used as a *tool* here, exactly as qpdf and pdftotext
 * are: what it produces is read and embedded, and none of
 * its source informs any of ours. Making the bytes rather than committing them
 * is also what lets a test state the dimensions it expects — it chose them.
 */
internal fun colourImage(width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            image.setRGB(x, y, ((x * 5) % 256 shl 16) or ((y * 11) % 256 shl 8) or ((x + y) % 256))
        }
    }
    return image
}

/** [image] as a JPEG file, baseline unless [progressive] asks for the SOF2 frame. */
internal fun encodedJpeg(image: BufferedImage, progressive: Boolean = false): ByteArray {
    val bytes = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    ImageIO.createImageOutputStream(bytes).use { output ->
        writer.output = output
        val parameters = writer.defaultWriteParam
        if (progressive) parameters.progressiveMode = ImageWriteParam.MODE_DEFAULT
        writer.write(null, IIOImage(image, null, null), parameters)
    }
    writer.dispose()
    return bytes.toByteArray()
}

/** A colour photograph of [width] × [height] samples, as a JPEG file. */
internal fun colourJpeg(width: Int, height: Int): ByteArray = encodedJpeg(colourImage(width, height))
