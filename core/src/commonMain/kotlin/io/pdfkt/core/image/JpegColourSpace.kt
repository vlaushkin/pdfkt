/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.obj.PdfName

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter", §8.6.4 "Device colour spaces",
// §8.9.5.1 "Image dictionaries" (Table 87, ColorSpace)
// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.2.2 "Frame header syntax" (Nf)

/**
 * The colour space of a JPEG image, chosen by how many components its frame
 * header declares.
 *
 * The number of components is all there is to choose from. §7.4.8 says the
 * samples the filter decodes consist "of one, three, or four colour
 * components", and the encoded data name no colour space — a three-component
 * frame is stored as YCbCr and decoded back to RGB by the filter itself, so
 * what comes out of DCTDecode is device RGB (see [JpegHeader.colourTransform]
 * for the rule that decides whether that transformation happens).
 *
 * A two-component frame is legal JPEG and not a PDF image: no device colour
 * space has two components. Such a frame is refused where it is read rather
 * than mapped onto something that would have been drawn wrongly.
 *
 * @property componentCount how many components a frame with this colour space
 *   has, which is the field it was chosen by.
 * @property pdfName the name this space is written as in an image XObject's
 *   `/ColorSpace` entry.
 */
public enum class JpegColourSpace(public val componentCount: Int, public val pdfName: PdfName) {
    /** One component: `/DeviceGray`. */
    DEVICE_GRAY(1, PdfName("DeviceGray")),

    /** Three components: `/DeviceRGB`. */
    DEVICE_RGB(3, PdfName("DeviceRGB")),

    /** Four components: `/DeviceCMYK`. */
    DEVICE_CMYK(4, PdfName("DeviceCMYK")),

    ;

    internal companion object {
        /**
         * The space a frame of [componentCount] components has, or null if that
         * count is not one §7.4.8 admits.
         *
         * Null rather than a raised error because the caller is the one holding
         * the marker whose frame header said so, and a refusal that cannot name
         * the marker is not worth much.
         */
        fun forComponentCount(componentCount: Int): JpegColourSpace? =
            entries.firstOrNull { it.componentCount == componentCount }
    }
}
