/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.plan

import io.pdfkt.core.model.Size

// No clause applies: L4 is this library's own design

/**
 * One page, as data: how big it is and what sits where on it.
 *
 * This is the central decision of the layout engine:
 *
 * > The layout does **not** draw as it goes. It produces a plan — plain,
 * > immutable data describing what sits where — and a separate pass turns the
 * > plan into content streams.
 *
 * Three things follow, and they are the reason this type exists rather than a
 * `draw()` call threaded through the fragmenter:
 *
 * - **Measurement stops being a special mode.** The height of a block is what
 *   planning it already computed, so table row heights, widow control and "how
 *   much of the page is left" all read a plan instead of running the layout in a
 *   second, do-not-draw mode that can disagree with the first.
 * - **Layout is testable without a PDF.** A test asserts on coordinates
 *   directly; nothing has to decompress a content stream and parse operators
 *   back out to find out where a line landed.
 * - **Emission is a dumb walk.** `PlanEmitter` holds no layout knowledge at
 *   all — every decision was already taken by whoever built the plan.
 *
 * Nothing here refers to a page's `/MediaBox`, a resource dictionary or an
 * object number: a plan is geometry and content, and the file it ends up in is
 * the emitter's business. The same plan can be emitted twice, into two
 * documents.
 *
 * @property size the page, whose lower-left corner is the origin every
 *   coordinate in [items] is measured from (§8.3.2.3, user space). The emitter
 *   writes it as the `/MediaBox`.
 * @property items what is drawn, **in the order it is drawn**. Later items paint
 *   over earlier ones — the plan is a list and not a set because overlap is
 *   meaningful, and the order the fragmenter emitted them in is the order the
 *   page shows.
 */
public data class PagePlan(public val size: Size, public val items: List<PlacedItem> = emptyList())
