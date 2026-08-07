/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.content

import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfReal
import io.pdfkt.core.obj.PdfString
import io.pdfkt.core.obj.writePdfObject
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString

// ISO 32000-2:2020 §7.8.2 "Content streams", §8.2 "Graphics objects",
// §8.4.2 "Graphics state stack", §8.4.4 "Graphics state operators",
// §8.5.2 "Path construction operators", §8.5.3 "Path-painting operators",
// §9.3.1 "Text state parameters and operators — General",
// §9.4.1 "Text objects — General", §9.4.2 "Text-positioning operators",
// §9.4.3 "Text-showing operators", §9.6.4 "Type 3 fonts" (Table 111)

/**
 * Builds the data of one content stream: "a sequence of instructions describing
 * the graphical elements to be painted on a page" (§7.8.2).
 *
 * The instructions are operands followed by their operator, in postfix notation
 * — "In PDF, all of the operands needed by an operator shall immediately precede
 * that operator" — and the operands are ordinary direct objects, so the numbers
 * this builder writes are the numeric objects of §7.3.3 and go out through the
 * one serializer that writes those.
 *
 * ## What this class is for
 *
 * A content stream is text, and anything can be concatenated into text. What
 * cannot be concatenated is the ordering rules the clauses state as
 * requirements, every one of which is a property of a *sequence* of operators
 * and so cannot be checked at the point any single one is appended:
 *
 * - "Occurrences of the q and Q operators shall be balanced within a given
 *   content stream" (§8.4.2);
 * - "Only those operators that are listed in Figure 9 — Graphics objects for
 *   each type of graphics object or in the intervals between graphics objects
 *   (called the content stream level in the figure) shall be used in that
 *   context" (§8.2). For the operators here that means a path object, begun by
 *   [moveTo] or [rectangle] and ended by [fill] or [stroke], admits path
 *   construction operators and nothing else — the clause's own example spells it
 *   out: "Inside the path object, additional path construction operators are
 *   permitted, as are the clipping path operators W and W*, but not general
 *   graphics state operators such as w or J." A text object, begun by
 *   [beginText] and ended by [endText], admits the text state, text-positioning
 *   and text-showing operators, and §9.4.1 names what else may join them: "The
 *   other operators that may appear in a text object are those related to the
 *   general graphics state, colour, and marked-content";
 * - the text-positioning and text-showing operators "shall only appear within
 *   text objects" (§9.4.2, §9.4.3), and text objects do not nest — "a second BT
 *   shall not appear before an ET" (Table 105);
 * - "each pair of matching operators (q … Q) shall be properly (separately)
 *   nested" with `BT` … `ET` (§9.4.1), which the clause illustrates by four
 *   sequences, two valid and two not.
 *
 * All of them are enforced here rather than left to the caller:
 * a violation throws from the call that commits
 * it, and the conditions that only a finished stream can be judged by — a `q`
 * never matched by a `Q`, a path never painted, a text object never ended —
 * throw from [build].
 *
 * NOTE 2 of §8.2 is why this matters in a library that only writes: a reader
 * "can process a content stream whose operations violate these rules … even
 * though it can display and print the stream correctly", so a malformed stream
 * is not something the output will show. It is caught here or not at all.
 *
 * ## What it deliberately does not know
 *
 * Coordinates are user space numbers taken as given; this class holds no
 * graphics state and computes no geometry. It does not know what the current
 * transformation matrix is, only that a matrix was concatenated. Layout is L4.
 *
 * ## Using it
 *
 * ```
 * val content = ContentStreamBuilder()
 * content.save()
 * content.lineWidth(2.0)
 * content.rectangle(100.0, 100.0, 200.0, 300.0)
 * content.stroke()
 * content.restore()
 * val stream = PdfStream(PdfDict(emptyMap()), content.build())
 * ```
 *
 * One instance builds one content stream and is not thread-safe. The operators
 * it does not have yet are the ones the later tasks of this layer add: the
 * clipping paths of §8.5.4, and the colour spaces of §8.6.8 beyond the device
 * RGB the plan model paints in.
 */
public class ContentStreamBuilder {
    /** The instructions written so far. [build] hands these over and ends. */
    private val buffer = Buffer()

    /**
     * How many `q` operators are not yet matched by a `Q` — the depth of the
     * graphics state stack (§8.4.2) this stream will have reached at this
     * point, which is what "balanced" is a statement about.
     */
    private var depth: Int = 0

    /**
     * Whether a path object is under construction: [moveTo] or [rectangle] has
     * begun one and no painting operator has ended it.
     *
     * This is also the answer to whether the current point is defined. "If the
     * current path is empty, the current point shall be undefined" (§8.5.2.1),
     * and a path object can only be begun by `m` or `re`, both of which leave a
     * current point behind; so within a path object there is always one, and
     * outside one there is never one.
     */
    private var pathOpen: Boolean = false

    /**
     * Whether a text object is under construction: [beginText] has written a
     * `BT` that [endText] has not yet closed with an `ET`.
     *
     * This is the condition the text-positioning and text-showing operators are
     * admitted by — they "shall only appear within text objects" (§9.4.2,
     * §9.4.3) — and, being the same flag, the condition a second `BT` is refused
     * by: "Text objects shall not be nested" (Table 105).
     */
    private var inTextObject: Boolean = false

    /**
     * The value [depth] had when the current text object was begun, which is the
     * depth `ET` must find it at again.
     *
     * §9.4.1 requires that "each pair of matching operators (q … Q) shall be
     * properly (separately) nested" with `BT` and `ET`, and rules out both ways
     * of interleaving them: a `q` opened before `BT` may not be closed before
     * the `ET`, and one opened after `BT` may not outlive it. Remembering the
     * depth at `BT` answers both — a `Q` may not take the depth below this mark,
     * and `ET` may not be reached above it.
     */
    private var depthAtTextObjectStart: Int = 0

    /**
     * Whether a `Tf` has appeared in this stream, which is what showing text
     * requires: "There is no initial value for either font or size; they shall
     * be specified explicitly by using Tf before any text is shown" (Table 103).
     *
     * Once set it stays set, including across text objects, because the clause
     * says so: "The text state operators may appear outside text objects, and
     * the values they set are retained across text objects in a single content
     * stream" (§9.3.1).
     */
    private var fontSelected: Boolean = false

    private var built: Boolean = false

    /**
     * Whether any operator has been written yet — the condition `d0` is admitted
     * by, since it "shall only be permitted as the first operator in a content
     * stream appearing in a Type 3 font's CharProcs dictionary" (Table 111).
     */
    private var anyOperatorWritten: Boolean = false

    /**
     * `q` — "Save the current graphics state on the graphics state stack"
     * (Table 56).
     *
     * The stack is what lets a graphical element "make local changes to the
     * graphics state without disturbing the graphics state of the surrounding
     * environment" (§8.4.2). Every call must be matched by a [restore] before
     * [build].
     */
    public fun save() {
        beginOperatorOutsidePathObject("q")
        depth++
        operator("q")
    }

    /**
     * `Q` — "Restore the graphics state by removing the most recently saved
     * state from the stack and making it the current state" (Table 56).
     *
     * @throws IllegalStateException if nothing has been saved that this could
     *   restore. The stack is "a LIFO (last in, first out) data structure"
     *   (§8.4.2) and popping an empty one has no meaning; the state this stream
     *   was entered with belongs to whatever invoked it and is not this
     *   stream's to discard.
     * @throws IllegalStateException if this would restore, from inside a text
     *   object, a state saved before the text object began. §9.4.1 prints that
     *   sequence — `q`, `BT`, …, `Q`, `ET` — among the ones that "are not
     *   valid", against `q`, `BT`, …, `ET`, `Q` and `BT`, `q`, …, `Q`, `ET`,
     *   which are.
     */
    public fun restore() {
        beginOperatorOutsidePathObject("Q")
        check(depth > 0) {
            "Q without a matching q: the graphics state stack of this content stream is empty " +
                "(ISO 32000-2:2020 §8.4.2)"
        }
        check(!inTextObject || depth > depthAtTextObjectStart) {
            "Q inside a text object restoring a state saved before the BT: when q and Q are " +
                "combined with BT and ET, each pair of matching operators shall be properly and " +
                "separately nested (ISO 32000-2:2020 §9.4.1)"
        }
        depth--
        operator("Q")
    }

    /**
     * `cm` — "Modify the current transformation matrix (CTM) by concatenating
     * the specified matrix" (Table 56).
     *
     * The six numbers are the matrix `[a b c d e f]` of §8.3.3, and Table 56 is
     * explicit about how they are written: "Although the operands specify a
     * matrix, they shall be written as six separate numbers, not as an array."
     * That is why this takes six parameters and not one array — the operand
     * list is six numeric objects, and an array object here would be a
     * different, wrong stream.
     */
    public fun concat(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
        beginContentStreamLevelOperator("cm")
        operands(a, b, c, d, e, f)
        operator("cm")
    }

    /**
     * `w` — "Set the line width in the graphics state" (Table 56).
     *
     * @param width the thickness of the line used to stroke a path, in user
     *   space units. §8.4.3.2 requires "a non-negative number", and a negative
     *   one is rejected: it is not a thickness, and the clause gives no meaning
     *   to it. Zero is allowed, since the clause defines it — "the thinnest line
     *   that can be rendered at device resolution: 1 device pixel wide" — while
     *   also saying such lines "should not be used", which is advice to the
     *   caller and not a rule this can enforce.
     * @throws IllegalArgumentException if [width] is negative.
     */
    public fun lineWidth(width: Double) {
        beginOperatorOutsidePathObject("w")
        require(width >= 0.0) {
            "a line width shall be a non-negative number expressed in user space units; " +
                "$width is not (ISO 32000-2:2020 §8.4.3.2)"
        }
        operands(width)
        operator("w")
    }

    /**
     * `rg` — "Set the colour to use for nonstroking operations" in the
     * `DeviceRGB` colour space (Table 73), which is the colour a `f` fills a
     * path with and the colour a `Tj` paints its glyphs in.
     *
     * The operator sets the colour space as well as the colour, which is why
     * there is no separate `cs` call beside it: "Set the … colour space to
     * DeviceRGB (or the DefaultRGB colour space; see 8.6.5.6) and set the colour
     * to use for nonstroking operations."
     *
     * Its position is free in the way §8.6.8 states — "Colour operators may
     * appear at the page description level or inside text objects (see Figure 9
     * — Graphics objects)" — so both are accepted, and a path object is not.
     *
     * @throws IllegalArgumentException if a component is outside the range the
     *   table gives: "Each operand shall be a number between 0.0 (minimum
     *   intensity) and 1.0 (maximum intensity)."
     */
    public fun nonStrokingColor(red: Double, green: Double, blue: Double) {
        beginOperatorOutsidePathObject("rg")
        requireComponents("rg", red, green, blue)
        operands(red, green, blue)
        operator("rg")
    }

    /**
     * `RG` — the same for stroking operations: "Set the stroking colour space to
     * DeviceRGB … and set the colour to use for stroking operations" (Table 73).
     *
     * A separate operator because the graphics state holds the two colours
     * separately (§8.4.1), which is what lets one `B` fill and stroke a path in
     * two colours: "The filling and stroking portions of the operation consult
     * different values of several graphics state parameters, such as the current
     * colour" (Table 59).
     *
     * @throws IllegalArgumentException if a component is outside 0.0 … 1.0.
     */
    public fun strokingColor(red: Double, green: Double, blue: Double) {
        beginOperatorOutsidePathObject("RG")
        requireComponents("RG", red, green, blue)
        operands(red, green, blue)
        operator("RG")
    }

    /**
     * `Do` — "Paint the specified XObject" (Table 86).
     *
     * @param name "The operand name shall appear as a key in the XObject
     *   subdictionary of the current resource dictionary (see 7.8.3, "Resource
     *   dictionaries"). The associated value shall be a stream whose Type entry,
     *   if present, is XObject." As with `Tf`, which name an XObject was
     *   registered under is the caller's knowledge; this builder holds no
     *   resource dictionary and cannot tell whether the name resolves.
     *
     * What it paints depends on the object, not on this operator: "The effect of
     * Do depends on the value of the XObject's Subtype entry, which may be Image
     * … or Form". An image XObject occupies the unit square of user space
     * (§8.9.4), so the `cm` that sizes and places it is written before this —
     * typically inside a `q` … `Q` pair, per the example of that clause.
     *
     * An XObject is a graphics object of its own in Figure 9, reached from the
     * content stream level: not inside a path object, and not inside a text
     * object, which admits only the text operators and the general graphics
     * state, colour and marked-content ones (§9.4.1).
     */
    public fun paintXObject(name: PdfName) {
        beginContentStreamLevelOperator("Do")
        operand(name)
        operator("Do")
    }

    /**
     * `d0` — "Set width information for the glyph and declare that the glyph
     * description specifies both its shape and its colour" (Table 111).
     *
     * The operator a Type 3 glyph description begins with (§9.6.4): "The glyph
     * description shall execute one of the operators described in Table 111 to
     * pass width and bounding box information to the font machinery. This shall
     * precede the execution of any path construction or path-painting operators
     * describing the glyph."
     *
     * This builder writes `d0` and not `d1`, because the glyphs it is used for
     * paint their own colour. Table 111 divides the two exactly there: a `d1`
     * glyph "specifies only shape, not colour", "shall not include an image",
     * and is painted in whatever colour the graphics state carries; a `d0` glyph
     * is "typically used only if the glyph description executes operators to set
     * the colour", which NOTE 2 says an image does — "An image does set the
     * colour space and colour explicitly". A colour bitmap is an image, so its
     * glyph is a `d0` glyph, and a `d1` one would be a declaration the stream
     * then contradicts.
     *
     * @param wx "the horizontal displacement in the glyph coordinate system; it
     *   shall be consistent with the corresponding width in the font's Widths
     *   array". `wy` is written as 0 rather than taken, because Table 111
     *   requires it — "wy shall be 0 (see 9.2.4, Glyph positioning and
     *   metrics)" — of every glyph shown by a text-showing operator, whose
     *   displacement §9.2.4 makes horizontal.
     * @throws IllegalStateException if anything has been written to this stream
     *   already: the operator "shall only be permitted as the first operator in
     *   a content stream appearing in a Type 3 font's CharProcs dictionary".
     */
    public fun glyphWidth(wx: Double) {
        check(!built) { BUILT }
        check(!anyOperatorWritten) {
            "d0 after another operator: it shall only be permitted as the first operator in a " +
                "content stream appearing in a Type 3 font's CharProcs dictionary " +
                "(ISO 32000-2:2020 §9.6.4, Table 111)"
        }
        operands(wx, HORIZONTAL_GLYPH_DISPLACEMENT)
        operator("d0")
    }

    /**
     * `m` — "Begin a new subpath by moving the current point to coordinates
     * (x, y), omitting any connecting line segment" (Table 58).
     *
     * Called at the content stream level this begins a path object; called
     * inside one it begins a further subpath of the same path. Either way the
     * current point afterwards is (x, y).
     */
    public fun moveTo(x: Double, y: Double) {
        beginPathConstructionOperator("m")
        operands(x, y)
        operator("m")
        pathOpen = true
    }

    /**
     * `l` — "Append a straight line segment from the current point to the point
     * (x, y). The new current point shall be (x, y)" (Table 58).
     *
     * @throws IllegalStateException if no path is under construction. This is
     *   one of the operators that "add a segment to the current path start at
     *   the current point; if the current point is undefined, an error shall be
     *   generated" (§8.5.2.1), and it is also the rule that "the first one
     *   invoked shall be m or re to begin a new subpath".
     */
    public fun lineTo(x: Double, y: Double) {
        beginPathConstructionOperator("l")
        check(pathOpen) {
            "l without a current point: a path shall be begun with m or re " +
                "(ISO 32000-2:2020 §8.5.2.1)"
        }
        operands(x, y)
        operator("l")
    }

    /**
     * `re` — "Append a rectangle to the current path as a complete subpath, with
     * lower-left corner (x, y) and dimensions width and height in user space"
     * (Table 58).
     *
     * Table 58 defines it by an equivalence rather than by prose, and the
     * equivalence is the whole specification of the operator:
     *
     * ```
     * x y width height re
     * ```
     *
     * is
     *
     * ```
     * x y m
     * (x + width) y l
     * (x + width) (y + height) l
     * x (y + height) l
     * h
     * ```
     *
     * Two consequences are taken from it directly rather than decided here.
     * [width] and [height] are not restricted to positive numbers: the
     * equivalence is arithmetic that holds for any values, and a rectangle given
     * a negative width is the same subpath as one given the mirrored corner and
     * a positive width. And a rectangle leaves a current point behind — the `h`
     * closing the subpath returns it to (x, y) — so, like [moveTo], this may
     * begin a path object.
     */
    public fun rectangle(x: Double, y: Double, width: Double, height: Double) {
        beginPathConstructionOperator("re")
        operands(x, y, width, height)
        operator("re")
        pathOpen = true
    }

    /**
     * `f` — "Fill the path, using the non-zero winding number rule to determine
     * the region to fill. Any subpaths that are open shall be implicitly closed
     * before being filled" (Table 59).
     *
     * Table 59 also lists `F`, "Equivalent to f; deprecated in PDF 2.0 and
     * included only for compatibility", with the instruction that "PDF writers
     * should use f instead". This writer has no reason to emit the deprecated
     * spelling, so it has no way to.
     *
     * @throws IllegalStateException if no path is under construction —
     *   "Attempting to execute a painting operator when the current path is
     *   undefined … shall generate an error" (§8.5.3.1).
     */
    public fun fill() {
        paint("f")
    }

    /**
     * `S` — "Stroke the path" (Table 59), with the line width, cap, join and
     * dash pattern the graphics state carries at this point (§8.4.3.1).
     *
     * @throws IllegalStateException if no path is under construction (§8.5.3.1).
     */
    public fun stroke() {
        paint("S")
    }

    /**
     * `B` — "Fill and then stroke the path, using the non-zero winding number
     * rule to determine the region to fill. This operator shall produce the same
     * result as constructing two identical path objects, painting the first with
     * f and the second with S" (Table 59).
     *
     * The two halves read different graphics state, which is the whole point of
     * having the operator rather than writing the path twice: "NOTE The filling
     * and stroking portions of the operation consult different values of several
     * graphics state parameters, such as the current colour." So a rectangle
     * with a fill of one colour and a border of another is one path, one `B`,
     * and one pair of colour operators before it.
     *
     * @throws IllegalStateException if no path is under construction (§8.5.3.1).
     */
    public fun fillAndStroke() {
        paint("B")
    }

    /**
     * `Tf` — "Set the text font, Tf, to font and the text font size, Tfs, to
     * size" (Table 103).
     *
     * @param font "the name of a font resource in the Font subdictionary of the
     *   current resource dictionary" (Table 103). It is a name object, written
     *   as one, and it is nothing else: "The font resource name presented to the
     *   Tf operator is arbitrary, as are the names for all kinds of resources.
     *   It bears no relationship to an actual font name, such as Helvetica"
     *   (§9.2.2, NOTE 1). Which name a font was registered under is the caller's
     *   knowledge; this builder does not hold a resource dictionary.
     * @param size "a number representing a scale factor" (Table 103) — the
     *   factor the standard-size font is scaled by, "1 unit in user space, or 1
     *   ⁄ 72 inch" before scaling (§9.2.2). Neither sign nor zero is excluded:
     *   §9.3.1 states "Negative text font size is permitted", and Table 103
     *   gives zero a meaning rather than refusing it — "Zero sized text shall
     *   not mark or clip any pixels".
     *
     * A text state operator, so its position is free in the way §9.3.1 states:
     * "The text state operators may appear outside text objects, and the values
     * they set are retained across text objects in a single content stream."
     * Both positions are therefore accepted, and one `Tf` serves every text
     * object after it.
     */
    public fun textFont(font: PdfName, size: Double) {
        beginOperatorOutsidePathObject("Tf")
        operand(font)
        operand(PdfReal(size))
        operator("Tf")
        fontSelected = true
    }

    /**
     * `BT` — "Begin a text object, initializing the text matrix, Tm, and the
     * text line matrix, Tlm, to the identity matrix" (Table 105).
     *
     * The two matrices are among the three parameters that "may be specified
     * only within a text object and shall not persist from one text object to
     * the next" (§9.4.1), which is why the operators that read and write them —
     * [nextLine], [textMatrix], [showText], [showTextWithPositioning] — are
     * admitted only between this and [endText].
     *
     * @throws IllegalStateException if a text object is already under
     *   construction: "Text objects shall not be nested; a second BT shall not
     *   appear before an ET" (Table 105).
     * @throws IllegalStateException if a path object is under construction —
     *   `BT` is not one of the operators a path object admits (§8.2, Figure 9).
     */
    public fun beginText() {
        beginOperatorOutsidePathObject("BT")
        check(!inTextObject) {
            "a second BT before an ET: text objects shall not be nested " +
                "(ISO 32000-2:2020 §9.4.1 Table 105)"
        }
        depthAtTextObjectStart = depth
        inTextObject = true
        operator("BT")
    }

    /**
     * `ET` — "End a text object, discarding the text matrix" (Table 105).
     *
     * @throws IllegalStateException if no text object is under construction:
     *   there is no text matrix to discard, and an `ET` alone is not a text
     *   object, which "begins with the BT operator and ends with the ET
     *   operator" (§9.4.1).
     * @throws IllegalStateException if a `q` written inside the text object is
     *   still unmatched. §9.4.1 prints `BT`, `q`, …, `ET`, `Q` among the
     *   sequences that "are not valid": the pairs would interleave rather than
     *   nest.
     */
    public fun endText() {
        check(!built) { BUILT }
        check(inTextObject) {
            "ET without a matching BT: a text object begins with the BT operator and ends with " +
                "the ET operator (ISO 32000-2:2020 §9.4.1)"
        }
        check(depth == depthAtTextObjectStart) {
            "${depth - depthAtTextObjectStart} q operator(s) written inside the text object are " +
                "still unmatched at ET: when q and Q are combined with BT and ET, each pair of " +
                "matching operators shall be properly and separately nested " +
                "(ISO 32000-2:2020 §9.4.1)"
        }
        inTextObject = false
        operator("ET")
    }

    /**
     * `Td` — "Move to the start of the next line, offset from the start of the
     * current line by (tx, ty)" (Table 106).
     *
     * The offset is from the start of the *current line*, not from the current
     * text position, because the assignment Table 106 gives is against the text
     * line matrix on both sides — `Tm = Tlm = [1 0 0; 0 1 0; tx ty 1] × Tlm` —
     * and Tlm "captures the value of Tm at the beginning of a line of text"
     * (§9.4.2). Whatever the showing operators did to Tm since is discarded.
     *
     * @param tx the horizontal offset, "expressed in unscaled text space units"
     *   (Table 106).
     * @param ty the vertical one, in the same units. Going down a page means a
     *   negative one: "Going to the next line entails decreasing the y
     *   coordinate" (Table 106, on `T*`).
     * @throws IllegalStateException outside a text object: "The text-positioning
     *   operators shall only appear within text objects" (§9.4.2).
     */
    public fun nextLine(tx: Double, ty: Double) {
        beginTextObjectOperator("Td")
        operands(tx, ty)
        operator("Td")
    }

    /**
     * `Tm` — "Set the text matrix, Tm, and the text line matrix, Tlm" (Table
     * 106) to `[a b 0; c d 0; e f 1]`.
     *
     * Table 106 states what distinguishes this from a concatenation: "The matrix
     * specified by the operands shall not be concatenated onto the current text
     * matrix, but shall replace it" — so this is the operator that puts text at
     * an absolute position in user space, and [nextLine] the one that moves
     * relative to what is already there.
     *
     * As with `cm` (§8.4.4) the operand list is six numbers: "Although the
     * operands specify a matrix, they shall be passed to Tm as six separate
     * numbers, not as an array."
     *
     * @throws IllegalStateException outside a text object (§9.4.2).
     */
    public fun textMatrix(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
        beginTextObjectOperator("Tm")
        operands(a, b, c, d, e, f)
        operator("Tm")
    }

    /**
     * `Tj` — "Show a text string" (Table 107).
     *
     * @param codes the character codes of the glyphs to paint. §9.4.3 fixes what
     *   the operand is and is not: "A string operand of a text-showing operator
     *   shall be interpreted as a sequence of character codes identifying the
     *   glyphs to be painted", one code per byte with a simple font and possibly
     *   several bytes per code with a composite one. Producing those codes from
     *   text is the business of the font (clause 9.6, 9.7); this operator paints
     *   what it is given. An empty array is accepted — "Strings presented to the
     *   text-showing operators may be of any length — even an empty string"
     *   (§9.4.3), and NOTE 3 repeats it: "Empty strings are valid."
     *
     * The bytes reach the stream as a string object, so the escaping §9.4.3
     * requires of them — "bytes whose values are equal to those of the ASCII
     * characters LEFT PARENTHESIS (28h), RIGHT PARENTHESIS (29h), and REVERSE
     * SOLIDUS (5Ch) (backslash) shall be preceded by a REVERSE SOLIDUS" — is the
     * escaping of §7.3.4 applied by the one writer that implements it, which is
     * what the clause asks for: "The strings shall conform to the syntax for
     * string objects."
     *
     * @throws IllegalStateException outside a text object: "The text-showing
     *   operators shall only appear within text objects" (§9.4.3).
     * @throws IllegalStateException if no [textFont] has been written. "There is
     *   no initial value for either font or size; they shall be specified
     *   explicitly by using Tf before any text is shown" (Table 103), and the
     *   text state is "initialised to [its] default values at the beginning of
     *   each page" (§9.3.1) — so a stream that shows text without selecting a
     *   font has named no font at all.
     */
    public fun showText(codes: ByteArray) {
        beginTextShowingOperator("Tj")
        operand(PdfString(codes))
        operator("Tj")
    }

    /**
     * `TJ` — "Show zero or more text strings, allowing individual glyph
     * positioning" (Table 107).
     *
     * The operand is one array mixing both kinds of [TextArrayElement], which is
     * the point of the operator: a string is shown, a number moves the text
     * position by thousandths of a unit of text space, and the two alternate
     * within a single operator so that kerning costs no repositioning operators.
     *
     * @param elements the array, which may be empty — "zero or more text
     *   strings" — and whose strings may be empty for the same reason [showText]
     *   accepts one.
     * @throws IllegalStateException outside a text object (§9.4.3).
     * @throws IllegalStateException if no [textFont] has been written (Table
     *   103).
     */
    public fun showTextWithPositioning(elements: List<TextArrayElement>) {
        beginTextShowingOperator("TJ")
        operand(
            PdfArray(
                elements.map { element ->
                    when (element) {
                        is TextArrayElement.Codes -> PdfString(element.codes)
                        is TextArrayElement.Adjustment -> PdfReal(element.amount)
                    }
                },
            ),
        )
        operator("TJ")
    }

    /**
     * The instructions, as the bytes of a content stream, and the end of this
     * builder.
     *
     * @throws IllegalStateException if the stream would be malformed as a whole:
     *   if any `q` is unmatched, since "occurrences of the q and Q operators
     *   shall be balanced within a given content stream" (§8.4.2), or if a path
     *   object was left unpainted, since "a path definition is not complete
     *   until a path-painting operator has been applied to it" (§8.5.2.1) and a
     *   stream ending inside a graphics object is not at the content stream
     *   level Figure 9 requires it to end at, or if a text object was left
     *   unclosed, which is the same rule and the `ET` that §9.4.1 ends a text
     *   object with.
     * @throws IllegalStateException if called twice. One instance builds one
     *   content stream: the bytes are handed over rather than copied, so there
     *   is nothing left to hand over a second time.
     */
    public fun build(): ByteArray {
        check(!built) { BUILT }
        check(depth == 0) {
            "$depth q operator(s) left unmatched by a Q: occurrences of q and Q shall be balanced " +
                "within a content stream (ISO 32000-2:2020 §8.4.2)"
        }
        check(!pathOpen) {
            "the content stream ends inside a path object: a path shall be ended by a painting " +
                "operator (ISO 32000-2:2020 §8.5.2.1, §8.2 Figure 9)"
        }
        check(!inTextObject) {
            "the content stream ends inside a text object: a text object ends with the ET " +
                "operator (ISO 32000-2:2020 §9.4.1, §8.2 Figure 9)"
        }
        built = true
        return buffer.readByteArray()
    }

    /**
     * Checks that [keyword] may appear at the content stream level and nowhere
     * else — the position of `cm`, the sole operator of the special graphics
     * state category of Table 50.
     *
     * §9.4.1 lists what may join the text operators inside a text object —
     * "those related to the general graphics state, colour, and marked-content"
     * — and the special graphics state category is not among them; nor is any of
     * this in a path object, which "admits path construction and clipping
     * operators only" (§8.2, the example after Figure 9).
     */
    private fun beginContentStreamLevelOperator(keyword: String) {
        beginOperatorOutsidePathObject(keyword)
        check(!inTextObject) {
            "$keyword inside a text object: the operators that may appear in a text object besides " +
                "the text ones are those related to the general graphics state, colour and " +
                "marked-content (ISO 32000-2:2020 §9.4.1, §8.2 Figure 9)"
        }
    }

    /**
     * Checks that [keyword], from the general graphics state or the text state
     * category of Table 50, may appear here — which is everywhere but inside a
     * path object.
     *
     * Both categories are admitted into a text object as well as at the content
     * stream level, each by its own sentence. §9.4.1 for the first: "The other
     * operators that may appear in a text object are those related to the
     * general graphics state, colour, and marked-content" — and §9.2.3 shows it
     * doing so, its EXAMPLE 2 setting a line width with `2 w` between `BT` and
     * `Tj` so that "the Tj operator strokes the glyph outlines with a line 2
     * units thick". §9.3.1 for the second: "The text state operators may appear
     * outside text objects, and the values they set are retained across text
     * objects in a single content stream."
     */
    private fun beginOperatorOutsidePathObject(keyword: String) {
        check(!built) { BUILT }
        check(!pathOpen) {
            "$keyword inside a path object: a path object admits path construction and clipping " +
                "operators only, and is ended by a painting operator " +
                "(ISO 32000-2:2020 §8.2 Figure 9)"
        }
    }

    /**
     * Path construction operators are admitted at the content stream level,
     * where `m` and `re` begin a path object, and inside one — but not inside a
     * text object, which is not among the graphics objects Figure 9 lets a path
     * begin in.
     */
    private fun beginPathConstructionOperator(keyword: String) {
        check(!built) { BUILT }
        check(!inTextObject) {
            "$keyword inside a text object: a path object shall not begin inside one, which ends " +
                "at ET (ISO 32000-2:2020 §9.4.1, §8.2 Figure 9)"
        }
    }

    /**
     * Checks that [keyword], a text-positioning or text-showing operator, is
     * inside a text object, which is the only place either category "shall
     * ... appear" (§9.4.2, §9.4.3).
     */
    private fun beginTextObjectOperator(keyword: String) {
        check(!built) { BUILT }
        check(inTextObject) {
            "$keyword outside a text object: the text-positioning and text-showing operators shall " +
                "only appear within text objects, between BT and ET " +
                "(ISO 32000-2:2020 §9.4.2, §9.4.3)"
        }
    }

    /**
     * The same, plus the font that Table 103 requires to have been named before
     * any text is shown.
     */
    private fun beginTextShowingOperator(keyword: String) {
        beginTextObjectOperator(keyword)
        check(fontSelected) {
            "$keyword before any Tf: there is no initial value for either the text font or the " +
                "text font size, they shall be specified explicitly by using Tf before any text " +
                "is shown (ISO 32000-2:2020 §9.3.1 Table 103)"
        }
    }

    /**
     * Checks the operands of a colour operator against the range Table 73 gives
     * them: "Each operand shall be a number between 0.0 (minimum intensity) and
     * 1.0 (maximum intensity)."
     *
     * A component outside it is not a darker or a brighter colour — the clause
     * gives it no meaning at all — so it is refused here rather than clamped,
     * which would put a colour on the page that no caller asked for.
     */
    private fun requireComponents(keyword: String, vararg components: Double) {
        require(components.all { !it.isNaN() && it >= 0.0 && it <= 1.0 }) {
            "each operand of $keyword shall be a number between 0.0 and 1.0; " +
                "${components.toList()} is not (ISO 32000-2:2020 §8.6.8, Table 73)"
        }
    }

    /**
     * Writes a painting operator, which "ends a path object, causing it to be
     * painted on the current page in the manner that the operator specifies"
     * (§8.5.3.1). "After the current path has been painted, it shall become no
     * longer defined" (§8.5.2.1), which is what leaving [pathOpen] false says.
     */
    private fun paint(keyword: String) {
        check(!built) { BUILT }
        check(pathOpen) {
            "$keyword with no current path: attempting to execute a painting operator when the " +
                "current path is undefined shall generate an error (ISO 32000-2:2020 §8.5.3.1)"
        }
        operator(keyword)
        pathOpen = false
    }

    /**
     * Writes the operands of one operator, separated so that no two of them
     * become one token.
     *
     * They are written as numeric objects through the serializer of §7.3.3
     * ([writePdfObject]) and not formatted here: an operand "is a direct object
     * belonging to any of the basic PDF data types except a stream" (§7.8.2), so
     * a number in a content stream is spelled exactly as a number anywhere else
     * in the file, down to the prohibition on exponential notation that a naive
     * rendering of a very small coordinate would walk straight into.
     *
     * [PdfReal] rejects NaN and the infinities at construction, so a coordinate
     * that is not a number fails here rather than reaching the file as something
     * a reader would misread.
     */
    private fun operands(vararg values: Double) {
        for (value in values) operand(PdfReal(value))
    }

    /**
     * Writes one operand, whatever its type, followed by the white-space that
     * keeps it from merging with what comes next.
     *
     * The same argument as for numbers applies to every other operand: a name
     * operand of `Tf` is the name object of §7.3.5, a string operand of `Tj` the
     * string object of §7.3.4 — "The strings shall conform to the syntax for
     * string objects" (§9.4.3) — and an array operand of `TJ` the array object
     * of §7.3.6. Each is written by the serializer that implements its clause,
     * so the escaping rules of a string in a content stream cannot drift from
     * the escaping rules of a string anywhere else in the file.
     */
    private fun operand(value: PdfObject) {
        buffer.writePdfObject(value)
        buffer.writeByte(SPACE)
    }

    /**
     * Writes an operator keyword and ends the line.
     *
     * An operator "is a PDF keyword", "distinguished from a name object by the
     * absence of an initial SOLIDUS character" (§7.8.2) — so it is written as
     * itself, with no marker of any kind. The end-of-line after it is token
     * separation, without which a keyword and whatever follows would run into
     * one another; a SPACE would separate them equally well, and the line feed
     * is chosen for the convention §7.5.1 states, "the tokens in a PDF file are
     * arranged into lines". One operator per line is the arrangement the
     * examples throughout clause 8 are printed in.
     */
    private fun operator(keyword: String) {
        buffer.writeString(keyword)
        buffer.writeByte(LINE_FEED)
        anyOperatorWritten = true
    }
}

/**
 * The `wy` operand of `d0`: "wy shall be 0 (see 9.2.4, Glyph positioning and
 * metrics)" — the displacement of a glyph shown by a text-showing operator is
 * horizontal.
 */
private const val HORIZONTAL_GLYPH_DISPLACEMENT: Double = 0.0

/** SPACE (20h), Table 1 of §7.2.3 — white-space, so it separates two tokens. */
private const val SPACE: Byte = 0x20

/** LINE FEED (0Ah), Table 1 of §7.2.3 — an end-of-line marker on its own. */
private const val LINE_FEED: Byte = 0x0A

private const val BUILT: String = "this content stream has been built and takes no further operators"
