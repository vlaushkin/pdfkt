/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"glyf — Glyph Data" — "Table organization", "Glyph headers",
// "Simple glyph description", "Composite glyph description"; §"loca"
// ISO 32000-2:2020 §9.9 "Embedded font programs"

/**
 * The header every glyph description begins with, simple or composite.
 *
 * @property numberOfContours "If the number of contours is greater than or equal
 *   to zero, this is a simple glyph. If negative, this is a composite glyph — the
 *   value -1 should be used for composite glyphs."
 * @property xMin "Minimum x for coordinate data."
 * @property yMin "Minimum y for coordinate data."
 * @property xMax "Maximum x for coordinate data."
 * @property yMax "Maximum y for coordinate data."
 */
internal class GlyphHeader(val numberOfContours: Int, val xMin: Int, val yMin: Int, val xMax: Int, val yMax: Int) {
    override fun toString(): String = "GlyphHeader($numberOfContours contours, bbox=[$xMin $yMin $xMax $yMax])"
}

/**
 * One child of a composite glyph: "a flags field, and a glyph ID … followed by
 * two argument fields".
 *
 * The two arguments are read and carried, not acted on. Where a component is
 * placed — "an offset vector (x, y) = (argument1, argument2) is added to the
 * coordinates of each control point" or the alignment of two point numbers — is
 * a question for something that draws outlines, and this library draws none: it
 * hands the font program to the viewer, and the only thing it has to know about
 * a composite glyph is which other glyphs go missing if it is subset without
 * them.
 *
 * @property flags "component flag" — the `Component Glyph flags` of the clause.
 * @property glyphId "glyph index of component", the child.
 * @property argument1 "x-offset for component or point number".
 * @property argument2 "y-offset for component or point number".
 * @property glyphIdOffset where the `glyphIndex` field sits, in bytes from the
 *   start of the glyph's data block. A subset renumbers glyphs, so the two bytes
 *   it has to rewrite in a description it otherwise copies verbatim are these.
 */
internal class GlyphComponent(
    val flags: Int,
    val glyphId: Int,
    val argument1: Int,
    val argument2: Int,
    val glyphIdOffset: Int,
) {
    /** "Bit 0: If this is set, the arguments are 16-bit … otherwise, they are bytes". */
    val argumentsAreWords: Boolean get() = flags and ARG_1_AND_2_ARE_WORDS != 0

    /** "Bit 1: If this is set, the arguments are signed xy values; otherwise, they are unsigned point numbers." */
    val argumentsAreXyValues: Boolean get() = flags and ARGS_ARE_XY_VALUES != 0

    /** "Bit 5: Indicates at least one more glyph after this one." */
    val hasMoreComponents: Boolean get() = flags and MORE_COMPONENTS != 0

    /**
     * "Bit 9: If set, this forces the aw and lsb (and rsb) for the composite to
     * be equal to those from this component glyph."
     */
    val suppliesTheMetrics: Boolean get() = flags and USE_MY_METRICS != 0

    override fun toString(): String = "GlyphComponent(glyph $glyphId, flags=${hex(flags.toLong())})"

    companion object {
        const val ARG_1_AND_2_ARE_WORDS: Int = 0x0001
        const val ARGS_ARE_XY_VALUES: Int = 0x0002
        const val WE_HAVE_A_SCALE: Int = 0x0008
        const val MORE_COMPONENTS: Int = 0x0020
        const val WE_HAVE_AN_X_AND_Y_SCALE: Int = 0x0040
        const val WE_HAVE_A_TWO_BY_TWO: Int = 0x0080
        const val WE_HAVE_INSTRUCTIONS: Int = 0x0100
        const val USE_MY_METRICS: Int = 0x0200
    }
}

/**
 * A glyph description, as far as this library reads one.
 *
 * "Each glyph description uses one of two formats: Simple glyph descriptions
 * specify a glyph outline directly using Bezier control points. Composite glyph
 * descriptions specify a glyph outline indirectly by referencing one or more
 * glyph IDs to use as components." The third case is not a format at all: a
 * glyph whose `loca` entries are equal has no description, and the clause names
 * the space character and an outline-less `.notdef` as the everyday examples.
 *
 * @property componentGlyphIds the children this description references, in the
 *   order the font gives them — empty for anything but a composite. This is the
 *   whole point of the parse (see [GlyfTable.componentClosureOf]).
 */
internal sealed interface Glyph {
    val componentGlyphIds: List<Int>

    /** A glyph with no description: "loca[n] = loca[n+1]". */
    data object Blank : Glyph {
        override val componentGlyphIds: List<Int> get() = emptyList()
    }

    /**
     * A glyph that "specifies a glyph outline directly using Bezier control
     * points", read as far as its header.
     *
     * The contours themselves — `endPtsOfContours`, the instructions, and the
     * packed `flags`, `xCoordinates` and `yCoordinates` arrays whose "actual
     * stored size … must be determined by parsing the flags array entries" — are
     * left in the font program's own bytes. Nothing this library does needs a
     * point of them: it embeds glyph descriptions, it does not rasterise them,
     * and a decoder written for no reader is a decoder no test can hold to
     * account.
     */
    class Simple(val header: GlyphHeader) : Glyph {
        override val componentGlyphIds: List<Int> get() = emptyList()

        override fun toString(): String = "Simple($header)"
    }

    /** A glyph "designed by referencing one or more glyph IDs to use as components". */
    class Composite(val header: GlyphHeader, val components: List<GlyphComponent>) : Glyph {
        override val componentGlyphIds: List<Int> get() = components.map { it.glyphId }

        override fun toString(): String = "Composite($header, components=$componentGlyphIds)"
    }
}

/**
 * `glyf` — "This table contains information that describes the glyphs in the
 * font in the TrueType outline format."
 *
 * The table has no structure of its own to parse: "The 'glyf' table does not
 * include any overall table header or records providing offsets to glyph data
 * blocks. Rather, the 'loca' table provides an array of offsets, indexed by
 * glyph IDs, which provide the location of each glyph data block within the
 * 'glyf' table." So this class is a [LocaTable] and a window, and the two are
 * useless apart — "the 'glyf' table must always be used in conjunction with the
 * 'loca' and 'maxp' tables".
 *
 * ## What is read, and what is left as bytes
 *
 * Every glyph is read as far as its [GlyphHeader]; a composite one is read
 * through its component records as well. A simple glyph's contours are not
 * decoded — see [Glyph.Simple].
 *
 * That is the whole of what the layers above need. A PDF embeds a font program
 * (§9.9) rather than outlines, so the two questions this library asks of `glyf`
 * are *where is this glyph's description* — [readerOrNullOf], for the subset
 * writer that copies it — and *what else must travel with it*, which is
 * [componentClosureOf].
 *
 * ## Why the closure is not optional
 *
 * A composite glyph "describes an outline indirectly by referencing other glyphs
 * that get incorporated into the composite glyph as components", by glyph id.
 * A subset that kept only the glyphs a document draws would keep й and drop the
 * и and the breve it is drawn out of, and the viewer would render the composite
 * out of whatever glyphs those ids point at in the subset — silently, and
 * differently in every subset. Closing over the components is what makes the
 * subset self-contained.
 *
 * "Composite glyphs may be nested within other composite glyphs … Thus, a
 * composite glyph description is a directed graph", which is why the closure is
 * transitive rather than one level deep.
 */
internal class GlyfTable private constructor(private val glyf: BigEndianReader, private val loca: LocaTable) {
    /** How many glyphs the font has — `maxp`'s numGlyphs, by way of `loca`. */
    val numGlyphs: Int get() = loca.numGlyphs

    /**
     * A reader over the data block of the glyph [glyphId], or null if the glyph
     * has none: "if a glyph has no outline or instructions, then
     * loca[n] = loca[n+1]".
     */
    fun readerOrNullOf(glyphId: Int): BigEndianReader? {
        val length = loca.lengthOf(glyphId)
        if (length == 0) return null
        return glyf.sliceAt(loca.offsetOf(glyphId), length, "the description of glyph $glyphId")
    }

    /**
     * Reads the description of the glyph [glyphId].
     *
     * @throws IllegalArgumentException if the description is malformed — shorter
     *   than a header, or referring to a glyph the font does not have.
     */
    fun glyphOf(glyphId: Int): Glyph {
        val reader = readerOrNullOf(glyphId) ?: return Glyph.Blank
        require(reader.size >= HEADER_SIZE) {
            "'loca' gives glyph $glyphId a description of ${reader.size} bytes, and every glyph " +
                "description begins with a $HEADER_SIZE-byte header"
        }
        reader.seek(0)
        val header = GlyphHeader(
            numberOfContours = reader.int16(),
            xMin = reader.int16(),
            yMin = reader.int16(),
            xMax = reader.int16(),
            yMax = reader.int16(),
        )
        // "If the number of contours is greater than or equal to zero, this is a
        // simple glyph. If negative, this is a composite glyph."
        return if (header.numberOfContours >= 0) {
            Glyph.Simple(header)
        } else {
            Glyph.Composite(header, componentsOf(reader, glyphId))
        }
    }

    /**
     * The glyphs [glyphIds] cannot be embedded without: themselves, the
     * components of any composite among them, the components of those, and so
     * on. Returned in glyph id order, so a subset built from it does not depend
     * on the order the document happened to use its glyphs in.
     *
     * The traversal keeps a visited set, which makes it terminate on a font
     * whose components form a cycle as well as on one that obeys "This graph
     * must be acyclic". A malformed font is not worth hanging over, and a cycle
     * cannot make the answer wrong: every glyph reachable from [glyphIds] is in
     * the result exactly once.
     *
     * @throws IllegalArgumentException if any of [glyphIds] is not a glyph of
     *   this font.
     */
    fun componentClosureOf(glyphIds: Iterable<Int>): Set<Int> {
        val closed = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        for (glyphId in glyphIds) {
            require(glyphId in 0 until numGlyphs) {
                "there is no glyph $glyphId in a font of $numGlyphs glyphs (they are numbered " +
                    "0..${numGlyphs - 1}, glyph 0 being .notdef)"
            }
            if (closed.add(glyphId)) pending.addLast(glyphId)
        }
        while (pending.isNotEmpty()) {
            for (component in glyphOf(pending.removeFirst()).componentGlyphIds) {
                if (closed.add(component)) pending.addLast(component)
            }
        }
        return closed.sorted().toSet()
    }

    /**
     * Walks the component records of a composite glyph.
     *
     * The record is variable-length, and the specification gives the sizes as a
     * fragment of C that reads it: the flags decide whether the arguments are
     * bytes or words, and which of the three mutually exclusive transformation
     * options follows them. Every field is stepped over at the width the clause
     * gives it, because the next record starts wherever this one ends — there is
     * no count of components and no offset to the next one, only
     * `MORE_COMPONENTS`.
     *
     * The `WE_HAVE_INSTRUCTIONS` block after the last record — "a uint16 value
     * is read immediately after the last component glyph to get the byte length
     * for instructions" — is not read: nothing follows it inside this glyph.
     */
    private fun componentsOf(reader: BigEndianReader, glyphId: Int): List<GlyphComponent> {
        val components = ArrayList<GlyphComponent>()
        do {
            val recordAt = reader.position
            val flags = reader.uint16()
            val componentGlyphId = reader.uint16()
            require(componentGlyphId < numGlyphs) {
                "glyph $glyphId is a composite whose component ${components.size} is glyph " +
                    "$componentGlyphId, and the font has $numGlyphs glyphs"
            }
            val argumentsAreWords = flags and GlyphComponent.ARG_1_AND_2_ARE_WORDS != 0
            val argumentsAreXyValues = flags and GlyphComponent.ARGS_ARE_XY_VALUES != 0
            // "argument1 … type depends on bits 0 and 1 in component flags": bit 0
            // makes them 16-bit, bit 1 makes them signed offsets rather than
            // "unsigned point numbers".
            val argument1 = readArgument(reader, argumentsAreWords, argumentsAreXyValues)
            val argument2 = readArgument(reader, argumentsAreWords, argumentsAreXyValues)
            reader.skip(F2DOT14_SIZE * transformValueCountOf(flags, glyphId, components.size))
            components += GlyphComponent(
                flags = flags,
                glyphId = componentGlyphId,
                argument1 = argument1,
                argument2 = argument2,
                glyphIdOffset = recordAt + GLYPH_ID_OFFSET_IN_RECORD,
            )
        } while (flags and GlyphComponent.MORE_COMPONENTS != 0)
        return components
    }

    override fun toString(): String = "glyf(${glyf.size} bytes, $numGlyphs glyphs)"

    companion object {
        /** The five `int16` fields every glyph description opens with. */
        const val HEADER_SIZE: Int = 10

        /** `glyphIndex` follows the `uint16` flags field of a component record. */
        private const val GLYPH_ID_OFFSET_IN_RECORD: Int = 2

        /** "F2DOT14 … Format 2.14" — a 16-bit fixed-point number. */
        private const val F2DOT14_SIZE: Int = 2

        /**
         * Binds the table [reader] covers to the [loca] table that locates
         * glyphs inside it.
         *
         * @throws IllegalArgumentException if `loca` places glyph data outside
         *   the table. The two tables are written together and read together;
         *   disagreeing is a font that would have this library reading glyph
         *   descriptions out of whatever table follows `glyf` in the file.
         */
        fun parse(reader: BigEndianReader, loca: LocaTable): GlyfTable {
            require(loca.glyphDataEnd <= reader.size) {
                "the 'loca' table accounts for ${loca.glyphDataEnd} bytes of glyph data while the " +
                    "'glyf' table is ${reader.size} bytes"
            }
            return GlyfTable(reader, loca)
        }

        private fun readArgument(reader: BigEndianReader, words: Boolean, signed: Boolean): Int = when {
            words && signed -> reader.int16()
            words -> reader.uint16()
            signed -> reader.int8()
            else -> reader.uint8()
        }

        /**
         * How many `F2DOT14` values follow the arguments: "If WE_HAVE_A_SCALE is
         * set, one additional F2DOT14 value is appended to the component glyph
         * data; if WE_HAVE_AN_X_AND_Y_SCALE is set, two F2DOT14 values are
         * appended; if WE_HAVE_A_TWO_BY_TWO, four F2DOT14 values are appended."
         *
         * @throws IllegalArgumentException if more than one of the three is set.
         *   They "are mutually exclusive: no more than one of these may be set",
         *   and a font that sets two says nothing about how long its record is —
         *   every later component of the glyph would be read from the wrong
         *   offset, so there is no reading to fall back on.
         */
        private fun transformValueCountOf(flags: Int, glyphId: Int, componentIndex: Int): Int {
            val scale = flags and GlyphComponent.WE_HAVE_A_SCALE != 0
            val xAndYScale = flags and GlyphComponent.WE_HAVE_AN_X_AND_Y_SCALE != 0
            val twoByTwo = flags and GlyphComponent.WE_HAVE_A_TWO_BY_TWO != 0
            require(!(scale && xAndYScale) && !(scale && twoByTwo) && !(xAndYScale && twoByTwo)) {
                "component $componentIndex of glyph $glyphId has the flags ${hex(flags.toLong())}, " +
                    "setting more than one of WE_HAVE_A_SCALE, WE_HAVE_AN_X_AND_Y_SCALE and " +
                    "WE_HAVE_A_TWO_BY_TWO, which \"are mutually exclusive: no more than one of these " +
                    "may be set\""
            }
            return when {
                scale -> 1
                xAndYScale -> 2
                twoByTwo -> 4
                else -> 0
            }
        }
    }
}
