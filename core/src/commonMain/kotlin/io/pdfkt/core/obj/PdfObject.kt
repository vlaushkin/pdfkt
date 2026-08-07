/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

// ISO 32000-2:2020 §7.3.1 "Objects — General"

/**
 * A PDF object.
 *
 * The clause enumerates the basic types of object a PDF file is built from;
 * each one is a variant of this sealed hierarchy, and the hierarchy is closed
 * so that [writePdfObject] can dispatch over it with a `when` the compiler
 * checks for exhaustiveness. Adding a variant without teaching the serializer
 * about it is a compile error, which is the entire reason for this shape.
 *
 * The variants are declared one per file, next to the writer for their own
 * clause: [PdfBool], [PdfInt], [PdfReal], [PdfName], [PdfString], [PdfArray],
 * [PdfDict], [PdfStream], [PdfRef] and [PdfNull].
 */
public sealed interface PdfObject
