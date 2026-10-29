#!/usr/bin/env python3
# Generates core/src/commonMain/kotlin/io/pdfkt/core/text/UnicodeTables.kt from
# the Unicode Character Database.
#
#   python3 core/scripts/generate_unicode_tables.py <UCD directory> > UnicodeTables.kt
#
# The directory holds, from https://www.unicode.org/Public/<version>/ucd/:
# DerivedBidiClass.txt and DerivedGeneralCategory.txt (from extracted/),
# DerivedJoiningType.txt and DerivedCombiningClass.txt (extracted/), BidiMirroring.txt, BidiBrackets.txt,
# Scripts.txt, IndicSyllabicCategory.txt, IndicPositionalCategory.txt,
# DerivedNormalizationProps.txt and UnicodeData.txt.
#
# Every table is a list of ranges `first..last -> value`, written as one string
# of base-36 numbers so that the Kotlin source stays a handful of string
# constants rather than an array initialiser the JVM's method size limit
# would refuse.
import os
import re
import sys

UCD = sys.argv[1]
VERSION = None


def lines(name):
    global VERSION
    with open(os.path.join(UCD, name), encoding="utf-8") as f:
        for raw in f:
            if VERSION is None:
                m = re.search(r"-(\d+\.\d+)\.\d+\.txt", raw)
                if m:
                    VERSION = m.group(1)
            line = raw.split("#", 1)[0].strip()
            if line:
                yield [field.strip() for field in line.split(";")]


def code_range(field):
    if ".." in field:
        a, b = field.split("..")
        return int(a, 16), int(b, 16)
    return int(field, 16), int(field, 16)


def ranges_of(name, values, keep=lambda v: True, column=1):
    """first, last, value-index triples for the property in column `column`."""
    out = []
    for fields in lines(name):
        value = fields[column]
        if not keep(value):
            continue
        a, b = code_range(fields[0])
        out.append((a, b, values.index(value)))
    return merged(out)


def merged(triples):
    triples.sort()
    out = []
    for a, b, v in triples:
        if out and out[-1][2] == v and out[-1][1] + 1 == a:
            out[-1] = (out[-1][0], b, v)
        else:
            out.append((a, b, v))
    return out


def b36(n):
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    if n == 0:
        return "0"
    s = ""
    while n:
        n, r = divmod(n, 36)
        s = digits[r] + s
    return s


def encoded(numbers):
    text = ",".join(b36(n) for n in numbers)
    chunks = [text[i:i + 100] for i in range(0, len(text), 100)]
    return " +\n        ".join('"' + c + '"' for c in chunks)


def table(name, doc, triples):
    numbers = [n for t in triples for n in t]
    return f"""    /** {doc} */
    val {name}: String =
        {encoded(numbers)}
"""


BIDI = ["L", "R", "AL", "EN", "ES", "ET", "AN", "CS", "NSM", "BN", "B", "S", "WS", "ON",
        "LRE", "LRO", "RLE", "RLO", "PDF", "LRI", "RLI", "FSI", "PDI"]
JOINING = ["U", "R", "L", "D", "C", "T"]
ISC = ["Other", "Avagraha", "Bindu", "Brahmi_Joining_Number", "Cantillation_Mark", "Consonant",
       "Consonant_Dead", "Consonant_Final", "Consonant_Head_Letter", "Consonant_Initial_Postfixed",
       "Consonant_Killer", "Consonant_Medial", "Consonant_Placeholder", "Consonant_Preceding_Repha",
       "Consonant_Prefixed", "Consonant_Subjoined", "Consonant_Succeeding_Repha", "Consonant_With_Stacker",
       "Gemination_Mark", "Invisible_Stacker", "Joiner", "Modifying_Letter", "Non_Joiner", "Nukta",
       "Number", "Number_Joiner", "Pure_Killer", "Register_Shifter", "Reordering_Killer",
       "Syllable_Modifier", "Tone_Letter", "Tone_Mark", "Virama", "Visarga", "Vowel",
       "Vowel_Dependent", "Vowel_Independent"]
IPC = ["NA", "Bottom", "Bottom_And_Left", "Bottom_And_Right", "Left", "Left_And_Right", "Overstruck",
       "Right", "Top", "Top_And_Bottom", "Top_And_Bottom_And_Left", "Top_And_Bottom_And_Right",
       "Top_And_Left", "Top_And_Left_And_Right", "Top_And_Right", "Visual_Order_Left"]

# The blocks the Indic shaper serves, and the characters every script shares.
INDIC_BLOCKS = [(0x0900, 0x0DFF), (0x1CD0, 0x1CFF), (0xA8E0, 0xA8FF), (0x200C, 0x200D), (0x25CC, 0x25CC),
                (0x0951, 0x0954)]


def in_indic(triple):
    a, b, _ = triple
    return any(a <= hi and b >= lo for lo, hi in INDIC_BLOCKS)


def clipped(triples):
    out = []
    for a, b, v in triples:
        for lo, hi in INDIC_BLOCKS:
            if a <= hi and b >= lo:
                out.append((max(a, lo), min(b, hi), v))
    return merged(out)


bidi = ranges_of("DerivedBidiClass.txt", BIDI)
ccc = merged([(*code_range(f[0]), int(f[1])) for f in lines("DerivedCombiningClass.txt") if f[1] != "0"])
joining = ranges_of("DerivedJoiningType.txt", JOINING)
scripts = sorted({fields[1] for fields in lines("Scripts.txt")})
script = ranges_of("Scripts.txt", scripts)
marks = ranges_of("DerivedGeneralCategory.txt", ["Mn", "Mc", "Me"], keep=lambda v: v in ("Mn", "Mc", "Me"))
isc = clipped(ranges_of("IndicSyllabicCategory.txt", ISC))
ipc = clipped(ranges_of("IndicPositionalCategory.txt", IPC))

mirror = []
for fields in lines("BidiMirroring.txt"):
    mirror += [int(fields[0], 16), int(fields[1], 16)]
brackets = []
for fields in lines("BidiBrackets.txt"):
    brackets += [int(fields[0], 16), int(fields[1], 16), 0 if fields[2] == "o" else 1]
excluded = set()
for fields in lines("DerivedNormalizationProps.txt"):
    if fields[1] == "Full_Composition_Exclusion":
        a, b = code_range(fields[0])
        excluded.update(range(a, b + 1))
decompositions = []
with open(os.path.join(UCD, "UnicodeData.txt"), encoding="utf-8") as f:
    for row in f:
        fields = row.split(";")
        mapping = fields[5]
        if not mapping or mapping.startswith("<"):
            continue
        parts = [int(p, 16) for p in mapping.split()]
        code = int(fields[0], 16)
        # The second number carries whether the character is composed back:
        # 2 × second + 1 for a Full_Composition_Exclusion.
        second = parts[1] if len(parts) > 1 else 0
        decompositions += [code, parts[0], second * 2 + (1 if code in excluded else 0)]

print(f"""/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.text

/*
 * GENERATED by core/scripts/generate_unicode_tables.py from the Unicode
 * Character Database {VERSION}. Do not edit by hand: regenerate from the UCD.
 */

// Unicode Standard Annex #44 "Unicode Character Database": DerivedBidiClass.txt,
// BidiMirroring.txt, BidiBrackets.txt, DerivedJoiningType.txt, Scripts.txt,
// DerivedGeneralCategory.txt, IndicSyllabicCategory.txt,
// IndicPositionalCategory.txt and the decompositions of UnicodeData.txt.

/**
 * The character properties text layout needs, as the UCD publishes them, in the
 * encoded form [UnicodeProperties] decodes: comma-separated base-36 numbers, in
 * threes `first, last, value` for a property over ranges — the value an index
 * into the names listed with it — and in pairs or threes for the mappings.
 */
internal object UnicodeTables {{
    const val UNICODE_VERSION: String = "{VERSION}"

    val BIDI_CLASSES: List<String> = listOf(
        {(","+chr(10)+"        ").join('"' + v + '"' for v in BIDI)},
    )
    val JOINING_TYPES: List<String> = listOf({", ".join('"' + v + '"' for v in JOINING)})
    val SCRIPTS: List<String> = listOf(
        {(","+chr(10)+"        ").join('"' + v + '"' for v in scripts)},
    )
    val INDIC_SYLLABIC_CATEGORIES: List<String> = listOf(
        {(","+chr(10)+"        ").join('"' + v + '"' for v in ISC)},
    )
    val INDIC_POSITIONAL_CATEGORIES: List<String> = listOf(
        {(","+chr(10)+"        ").join('"' + v + '"' for v in IPC)},
    )

{table("BIDI_CLASS", "Bidi_Class, over every range DerivedBidiClass.txt lists; anything else is L.", bidi)}
{table("JOINING_TYPE", "Joining_Type; anything not listed is U, Non_Joining.", joining)}
{table("SCRIPT", "Script; anything not listed is Unknown.", script)}
{table("COMBINING_CLASS", "Canonical_Combining_Class, the value itself; anything not listed is 0.", ccc)}
{table("MARK", "General_Category Mn, Mc or Me — value 0, 1 or 2.", marks)}
{table("INDIC_SYLLABIC_CATEGORY", "Indic_Syllabic_Category, over the blocks the Indic shaper serves.", isc)}
{table("INDIC_POSITIONAL_CATEGORY", "Indic_Positional_Category, over the same blocks.", ipc)}
    /** Bidi_Mirroring_Glyph: pairs `character, mirror`. */
    val MIRRORING: String =
        {encoded(mirror)}

    /** Bidi_Paired_Bracket and Bidi_Paired_Bracket_Type: `character, pair, 0 open | 1 close`. */
    val BRACKETS: String =
        {encoded(brackets)}

    /**
     * Canonical decompositions: `character, first, 2 × second + excluded` —
     * second 0 for a singleton, excluded 1 for a Full_Composition_Exclusion,
     * which decomposes but is never composed.
     */
    val DECOMPOSITIONS: String =
        {encoded(decompositions)}
}}""")
