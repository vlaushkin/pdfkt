# Adobe Core 14 AFM files

The metrics of the fourteen fonts ISO 32000-2:2020 §9.6.2.2 calls the
*standard 14 fonts*, as Adobe published them. They are one of the primary
sources this library is written from.

They are here **verbatim**, exactly as they came out of Adobe's archive, so
that the reduced copy compiled into `:core`
(`core/src/commonMain/kotlin/io/pdfkt/core/font/Standard14Afm.kt`) can be
diffed against its source at any time. The reduction rule is stated in that
file's own MODIFICATION NOTICE: each font's header is verbatim, each
character metric keeps its `C`, `WX` and `N` fields and drops the bounding
box `B`, and the kerning and composite-character sections are dropped
entirely.

## Where they came from

Adobe distributed the fourteen AFM files as `Core14_AFMs.tar` from its
developer site. That site is gone; the file is preserved in the Internet
Archive's copy of Adobe's own server, and that is where this copy was
fetched from on 2026-08-04:

| | |
|---|---|
| Original URL | `http://partners.adobe.com/public/developer/en/pdf/Core14_AFMs.tar` |
| Fetched from | `https://web.archive.org/web/20050125221719if_/http://partners.adobe.com/public/developer/en/pdf/Core14_AFMs.tar` |
| Archive snapshot | 2005-01-25 |
| `sha256(Core14_AFMs.tar)` | `fb0736bab04daf130998f5e5b09c61307fd0e48c778bff1a24bf23591a56467a` |

Nothing was taken from any PDF library, its repository or its distribution —
that is the rule these files exist to keep. Adobe's
own hosting, and the archive of Adobe's own hosting, is the only place they
were fetched from. Other Adobe locations of the same file, for the record:

- `http://partners.adobe.com/asn/tech/type/fontinfo/Core14_AFMs.tar`
- `http://partners.adobe.com/asn/developer/technotes/fontinfo/Core14_AFMs.tar`
- `http://partners.adobe.com/asn/acrobat/download/fontinfo/Core14_AFMs.tar`
- `ftp://ftp.adobe.com/pub/adobe/type/win/all/afmfiles/base35/` — the same
  fourteen fonts in the 1990 revision of the metrics under 8.3 names
  (`hv______.afm`, `tir_____.afm`, …), also archived. Not used: the 1997
  files above are the later revision and carry the licence below.

The format itself is specified by the *Adobe Font Metrics File Format
Specification*, Adobe technical note #5004, 7 October 1998, published at
`https://www.adobe.com/content/dam/acom/en/devnet/font/pdfs/5004.AFM_Spec.pdf`
(which now redirects to `https://adobe-type-tools.github.io/font-tech-notes/pdfs/5004.AFM_Spec.pdf`),
`sha256 7adf2c10f4ee581e1d5ecf51284b17946c9430410e46336f9b57889a09d2f793`.
That document, and no implementation of it, is what the parser in
`core/src/commonMain/kotlin/io/pdfkt/core/font/Afm.kt` was written from.

## Licence

`MustRead.html` is Adobe's licence for these files and is part of the
distribution — it accompanied the fourteen files in `Core14_AFMs.tar` and,
by its own terms, has to keep accompanying them. In full:

> This file and the 14 PostScript(R) AFM files it accompanies may be used,
> copied, and distributed for any purpose and without charge, with or
> without modification, provided that all copyright notices are retained;
> that the AFM files are not distributed without this file; that all
> modifications to this file or any of the AFM files are prominently noted
> in the modified file(s); and that this paragraph is not modified. Adobe
> Systems has no responsibility or obligation to support the use of the AFM
> files.

So: use, copy and distribution for any purpose, permission granted, with
three obligations this repository keeps —

1. **Copyright notices retained.** The `Comment Copyright …` and `Notice …`
   lines of every file are kept, here and in the generated Kotlin.
2. **Not distributed without `MustRead.html`.** It sits beside the files
   here, and its text is reproduced in the header of the generated Kotlin
   file, which is the form `:core` ships in.
3. **Modifications prominently noted.** The files in this directory are
   unmodified. The generated Kotlin carries a reduced copy — the header
   verbatim, the character metrics cut down to the `C`, `WX` and `N` fields
   this library reads, kerning and composite sections dropped — and says so
   at the top of the file and in a `Comment` line inside each font's data.

## The files

| File | `sha256` |
|---|---|
| `Courier.afm` | `521e0d7c7521efd4be78a5a9c5398e4c67d0771e396115b0346bc4ef74ada53d` |
| `Courier-Bold.afm` | `ad0150d4bedcc8877742bf94251fcec13e348dd599d4603f679d92027d1e6e99` |
| `Courier-BoldOblique.afm` | `cb82e69ef5f6d421e8f404fe00bb0d993425aae79725331d0ebf847e94e97e92` |
| `Courier-Oblique.afm` | `b27103b2a2ef6030c110626597e2ab47bb8279075a039ab9170facb0aa1f70e1` |
| `Helvetica.afm` | `da33f1870474c8e68bfe3e2353ff107ab6c6eea1f9836ce2aaf1e1a07b17982f` |
| `Helvetica-Bold.afm` | `b880d96baf56d0cc059f258f60b4d764ef49b555ab9db294b959c0016dee41f2` |
| `Helvetica-BoldOblique.afm` | `69984a35ca26973a39f261cf83e0d367ea2e6517c590b0b030d4d4a219d9c269` |
| `Helvetica-Oblique.afm` | `b4609b71b660a392ac09df35060271a876c2ce66617dd83bf826f742bb9d9721` |
| `Symbol.afm` | `3d2128a820375a10de9bc8bf6cfb15ded482c01ca0f95cc0b3277f37ec8bde66` |
| `Times-Roman.afm` | `768e1cabea085d489a63da3e80b96bc5abf0ec98d3073c9b4d6ba76e7bccba64` |
| `Times-Bold.afm` | `b4a000ed85cb22c6cdd985aa0fd3f6f78ed5079b7c6860dc4f0234e0d0e3c522` |
| `Times-BoldItalic.afm` | `93c4744ba955215de02c4aae0b777133442ada2f7ab5a2af30a040b792b3c55d` |
| `Times-Italic.afm` | `ed37fa2e6a67b5b17dfd47f36fc7e90df32891a4408860dbc8d4cbbe9959e242` |
| `ZapfDingbats.afm` | `a32565c90afd1b57a7008fc567b78d95cf1c22adff5e086094d666d88b039859` |
| `MustRead.html` | `b226bfc00e1b8b8a80c7b3cfbbc322d13b4b0401f94cdeafdd93b2210ad802eb` |

The fourteen file names are the `FontName` of each file, which is also the
`/BaseFont` §9.6.2.2 spells for that font — the reason `Standard14Name` can
map to a file with no table of its own.
