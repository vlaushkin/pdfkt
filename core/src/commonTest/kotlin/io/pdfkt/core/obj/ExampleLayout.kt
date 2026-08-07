/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

// ISO 32000-2:2020 §7.2.3 "Character set"

/**
 * Strips from [text] the white-space that only lays it out, keeping the
 * white-space that separates one token from the next.
 *
 * The clause prints its examples across several lines and indents nested
 * constructs; a writer producing one line is writing the same object, which
 * the clause says outright:
 *
 * > The examples in this document use a convention that arranges tokens into
 * > lines. However, the examples' use of white-space for indentation is purely
 * > for clarity of exposition and need not be included in practical use.
 *
 * So an example and a rendering of it may be compared only up to white-space,
 * and this is that comparison written down: a run of white-space next to a
 * delimiter is dropped, because a delimiter already ends the token beside it,
 * and any other run becomes the one SPACE that "PDF syntax treats any sequence
 * of consecutive white-space characters … as one character" makes it.
 *
 * The transformation is applied to both sides of a comparison, so it does not
 * have to be information-preserving — only blind to which side it came from.
 * It is not applied to arbitrary bytes, though: white-space inside a string
 * object is data rather than layout (§7.2.3, "The rules defined in this
 * subclause apply to all characters in the file except within strings, streams,
 * and comments"), and this function cannot tell the two apart. It is used on
 * the clause's examples, in which no string holds white-space next to a
 * delimiter or at its edge, and where every string therefore survives.
 */
internal fun withoutLayoutWhiteSpace(text: String): String {
    val stripped = StringBuilder()
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (!isWhiteSpace(character)) {
            stripped.append(character)
            index++
            continue
        }
        var end = index
        while (end < text.length && isWhiteSpace(text[end])) end++
        val before = if (stripped.isEmpty()) null else stripped[stripped.length - 1]
        val after = if (end < text.length) text[end] else null
        val separates = before != null && after != null && !isDelimiter(before) && !isDelimiter(after)
        if (separates) stripped.append(' ')
        index = end
    }
    return stripped.toString()
}

/** Table 1 — White-space characters, by the hexadecimal codes the table gives. */
private fun isWhiteSpace(character: Char): Boolean = when (character.code) {
    0x00, 0x09, 0x0A, 0x0C, 0x0D, 0x20 -> true
    else -> false
}

/** The delimiter characters of the clause, including `{` and `}`. */
private fun isDelimiter(character: Char): Boolean = when (character) {
    '(', ')', '<', '>', '[', ']', '/', '%', '{', '}' -> true
    else -> false
}
