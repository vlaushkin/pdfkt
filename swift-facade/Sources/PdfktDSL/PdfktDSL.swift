import Pdfkt

// The facade deliberately reuses the Kotlin type names (`Document`,
// `Paragraph`, `Table`, …), so the bridged originals are renamed here and
// referred to by these aliases everywhere a facade name would shadow them.
public typealias KotlinDocument = Pdfkt.Document
public typealias KotlinDocumentScope = Pdfkt.DocumentScope
public typealias KotlinInlineScope = Pdfkt.InlineScope
public typealias KotlinInlineStyleScope = Pdfkt.InlineStyleScope
public typealias KotlinColophonScope = Pdfkt.ColophonScope
public typealias KotlinTableScope = Pdfkt.TableScope
public typealias KotlinRowScope = Pdfkt.RowScope
public typealias KotlinCellScope = Pdfkt.CellScope
public typealias KotlinListScope = Pdfkt.ListScope
public typealias KotlinListItemScope = Pdfkt.ListItemScope
public typealias KotlinBlockquoteScope = Pdfkt.BlockquoteScope
public typealias KotlinSectionScope = Pdfkt.SectionScope
