# Compares the outlines of a CFF subset written by pdfkt with the font it was
# cut from, glyph by glyph, using fontTools as the reading side.
#
#   python cff_outline_check.py <font file> <font index> <subset.cff>
#
# Every glyph of the subset is drawn through its charset CID, which is the glyph
# index it had in the source; the source glyph is drawn at the default instance
# (fontTools draws a CFF2 glyph at the default location when none is set). The
# two drawings have to be the same path, operator for operator. Prints "ok <n>"
# or the first glyph that differs, and exits non-zero on a difference.
import io
import sys

from fontTools.cffLib import CFFFontSet
from fontTools.pens.recordingPen import DecomposingRecordingPen, RecordingPen
from fontTools.ttLib import TTFont


def main():
    path, index, subset_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    source = TTFont(path, fontNumber=index)
    glyph_set = source.getGlyphSet()
    order = source.getGlyphOrder()

    cff = CFFFontSet()
    with open(subset_path, "rb") as f:
        cff.decompile(io.BytesIO(f.read()), None)
    top = cff[cff.fontNames[0]]
    assert hasattr(top, "ROS"), "the subset is not CID-keyed"
    assert top.ROS == ("Adobe", "Identity", 0), top.ROS
    assert len(top.GlobalSubrs) == 0, "the subset still has global subroutines"
    for fd in top.FDArray:
        assert not hasattr(fd.Private, "Subrs"), "the subset still has local subroutines"
    charset = top.charset
    strings = top.CharStrings

    for subset_gid, name in enumerate(charset):
        cid = 0 if subset_gid == 0 else int(name[3:])
        want = DecomposingRecordingPen(glyph_set)
        glyph_set[order[cid]].draw(want)
        got = RecordingPen()
        strings[name].draw(got)
        if normalised(want.value) != normalised(got.value):
            print(f"glyph {cid} ({order[cid]}) differs:\n want {want.value[:8]}\n  got {got.value[:8]}")
            sys.exit(1)
    print(f"ok {len(charset)}")


def normalised(value):
    return [(op, tuple(tuple(round(c, 3) for c in pt) for pt in args)) for op, args in value]


main()
