/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

// No clause applies: interop sugar is this library's own design.

/**
 * The rendered PDF as [NSData], copied in one block.
 *
 * A `KotlinByteArray` crosses the Objective-C bridge as an object whose bytes
 * Swift can reach only one `get(index:)` call at a time — tens of millions of
 * bridge calls for an album of photographs. This is [toByteArray] without them.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Throws(Exception::class)
public fun Document.toNSData(): NSData {
    val bytes = toByteArray()
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
}

/**
 * The bytes of [data] as a [ByteArray], copied in one block — for an image or
 * a font handed over from Swift, the same bridge cost the other way round.
 */
@OptIn(ExperimentalForeignApi::class)
public fun bytesOf(data: NSData): ByteArray {
    val size = data.length.toInt()
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return bytes
}
