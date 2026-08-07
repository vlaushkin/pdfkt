/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"cmap — Character To Glyph Index Mapping Table":
// "'cmap' Header", "Encoding records and encodings", "'cmap' subtable formats"
// ISO 32000-2:2020 §9.7.5 "CMaps", §9.10.3 "ToUnicode CMaps"

/**
 * `cmap` — "This table defines the mapping of character codes to a default glyph
 * index."
 *
 * ## One subtable is used and the others are ignored
 *
 * That is the specification's own instruction — "Apart from a format 14
 * subtable, all other subtables are exclusive: applications should select and
 * use one and ignore the others" — and [characterMap] is the one selected. The
 * choice is by format, because the formats are not equivalent:
 *
 * > If a font includes Unicode subtables for both 16-bit encoding (typically,
 * > format 4) and also 32-bit encoding (formats 10 or 12), then the characters
 * > supported by the subtable for 32-bit encoding should be a superset of the
 * > characters supported by the subtable for 16-bit encoding, and the 32-bit
 * > encoding should be used by applications.
 *
 * Between two records of the same format the first is taken. The specification
 * leaves the choice open but not arbitrary — "an application may choose which to
 * select, but should make this selection consistently each time the font is
 * used" — and the records arrive "sorted first by platform ID, then by
 * platform-specific encoding ID", so first-wins is an order the font itself
 * fixed.
 *
 * ## What is not read
 *
 * Formats 0, 2, 6, 8, 10, 13 and 14. Formats 4 and 12 are what the specification
 * says to write — "Formats 4 or 12 are appropriate for most new fonts, depending
 * on the Unicode character repertoire supported" — and are what every font this
 * library is given for text has. A record in another format is kept in
 * [encodings] without a subtable, so a font that has nothing else can say so.
 *
 * @property encodings every `EncodingRecord` of the header, in file order.
 * @property characterMap the subtable this library maps characters through.
 */
internal class CmapTable(val encodings: List<CmapEncoding>, val characterMap: CmapSubtable) {
    /** The glyph [codePoint] selects, or 0 if the font has no glyph for it. */
    fun glyphIdOf(codePoint: Int): Int = characterMap.glyphIdOf(codePoint)

    /** The parsed subtables in the format [format], for a font that has several. */
    fun subtablesOfFormat(format: Int): List<CmapSubtable> =
        encodings.mapNotNull { it.subtable }.filter { it.format == format }

    override fun toString(): String = "cmap(${encodings.size} encodings, using format ${characterMap.format})"

    companion object {
        /** Reads the table [reader] covers. */
        fun parse(reader: BigEndianReader): CmapTable {
            reader.seek(0)
            val version = reader.uint16()
            require(version == 0) {
                "the 'cmap' table is version $version; the specification defines version 0 and notes " +
                    "that \"the 'cmap' table version number remains at 0x0000 for fonts that make use of " +
                    "the newer subtable formats\""
            }
            val numTables = reader.uint16()
            val encodings = ArrayList<CmapEncoding>(numTables)
            for (index in 0 until numTables) {
                reader.seek(HEADER_SIZE + index * ENCODING_RECORD_SIZE)
                val platformId = reader.uint16()
                val encodingId = reader.uint16()
                val subtableOffset = reader.uint32()
                require(subtableOffset < reader.size) {
                    "a 'cmap' encoding record (platform $platformId, encoding $encodingId) points at " +
                        "offset $subtableOffset of a table that is ${reader.size} bytes"
                }
                encodings += readEncoding(reader, platformId, encodingId, subtableOffset.toInt())
            }
            return CmapTable(encodings, select(encodings))
        }

        private fun readEncoding(
            table: BigEndianReader,
            platformId: Int,
            encodingId: Int,
            subtableOffset: Int,
        ): CmapEncoding {
            val rest = table.sliceAt(
                subtableOffset,
                table.size - subtableOffset,
                "the 'cmap' subtable for platform $platformId, encoding $encodingId",
            )
            val format = rest.uint16At(0)
            val subtable = when (format) {
                CmapFormat4Subtable.FORMAT -> CmapFormat4Subtable.parse(rest)
                CmapFormat12Subtable.FORMAT -> CmapFormat12Subtable.parse(rest)
                else -> null
            }
            return CmapEncoding(platformId, encodingId, format, subtable)
        }

        /**
         * The one subtable to map through: the highest-ranked record, first of
         * its rank.
         */
        private fun select(encodings: List<CmapEncoding>): CmapSubtable {
            val chosen = encodings.filter { it.isUnicode && it.subtable != null }.maxByOrNull { rankOf(it) }
            return requireNotNull(chosen?.subtable) {
                "the font's 'cmap' table has no Unicode subtable in a format this library reads " +
                    "(4 or 12); it offers ${encodings.joinToString("; ")}. Every character this library " +
                    "draws is a Unicode code point, so a font it can use has to say which glyph one selects"
            }
        }

        /**
         * Format 12 over format 4 — "the 32-bit encoding should be used by
         * applications". `maxByOrNull` keeps the first of an equal rank, which is
         * the first record in file order.
         */
        private fun rankOf(encoding: CmapEncoding): Int = if (encoding.format == CmapFormat12Subtable.FORMAT) 1 else 0

        /** version + numTables. */
        private const val HEADER_SIZE: Int = 4

        /** platformID + encodingID + subtableOffset. */
        private const val ENCODING_RECORD_SIZE: Int = 8
    }
}
