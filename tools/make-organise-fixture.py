# Makes app/src/androidTest/assets/organise.pdf (needs pymupdf): six pages, each saying which it
# is ("marker-N"), a contents list with one entry per page, a filled text field on page 2, a link
# on page 1 to page 3, and page 5 already turned 90 degrees.
import pymupdf, sys
out = sys.argv[1] if len(sys.argv) > 1 else "organise.pdf"
d = pymupdf.open()
for i in range(6):
    p = d.new_page(width=595, height=842)
    p.insert_text((72, 120), f"Chapter {i + 1}", fontsize=28)
    p.insert_text((72, 170), f"marker-{i + 1} on this page.", fontsize=14)
    for k in range(20):
        p.insert_text((72, 220 + k * 22), f"Line {k + 1} of chapter {i + 1}, kept with its page.", fontsize=11)
w = pymupdf.Widget()
w.field_type = pymupdf.PDF_WIDGET_TYPE_TEXT; w.field_name = "patient_name"; w.rect = pymupdf.Rect(72, 700, 300, 722); w.field_value = "Ann Example"
d[1].add_widget(w)
d[0].insert_link({"kind": pymupdf.LINK_GOTO, "from": pymupdf.Rect(72, 680, 200, 700), "page": 2, "to": pymupdf.Point(72, 100)})
d[0].insert_text((72, 695), "See chapter 3", fontsize=11)
d[4].set_rotation(90)
d.set_toc([[1, f"Chapter {i + 1}", i + 1] for i in range(6)])
d.save(out)
