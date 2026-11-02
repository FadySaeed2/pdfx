# Dumps what the app will see on a page: characters (with font, size, boldness, line and
# direction) and drawn shapes (rules and filled boxes), in the page's own upright coordinates.
import pymupdf, json, sys
def page_data(p):
    M = p.rotation_matrix
    chars = []
    li = 0
    raw = p.get_text("rawdict", flags=pymupdf.TEXT_PRESERVE_WHITESPACE | pymupdf.TEXT_MEDIABOX_CLIP | pymupdf.TEXT_CID_FOR_UNKNOWN_UNICODE)
    for b in raw["blocks"]:
        for l in b.get("lines", []):
            dx, dy = l["dir"]
            # direction in upright coordinates
            v = pymupdf.Point(dx, dy) * pymupdf.Matrix(M.a, M.b, M.c, M.d, 0, 0)
            for s in l["spans"]:
                bold = 1 if (s["flags"] & 16) or any(k in s["font"] for k in ("Bold", "Black", "Heavy", "Semibold", ".B")) else 0
                for c in s["chars"]:
                    r = pymupdf.Rect(c["bbox"]) * M
                    o = pymupdf.Point(c["origin"]) * M
                    chars.append([round(r.x0,2), round(r.y0,2), round(r.x1,2), round(r.y1,2), round(o.x,2), round(o.y,2), ord(c["c"]) if c["c"] else 32, round(s["size"],2), bold, s["font"], li, round(v.x,3), round(v.y,3)])
            li += 1
    shapes = []
    for g in p.get_drawings():
        bb = pymupdf.Rect(g["rect"]) * M
        fill = g.get("fill"); col = g.get("color")
        if fill is not None:
            # Each subpath on its own, as a path walker sees them: a frame drawn as thin strips
            # is several thin rectangles, not one big filled box.
            subs = []; cur = None; last = None
            for it in g["items"]:
                if it[0] == "re":
                    r = pymupdf.Rect(it[1]); subs.append([r.x0, r.y0, r.x1, r.y1]); cur = None; continue
                if it[0] == "qu":
                    r = it[1].rect; subs.append([r.x0, r.y0, r.x1, r.y1]); cur = None; continue
                pts = [it[1], it[-1]] if it[0] == "c" else [it[1], it[2]]
                if cur is None or last is None or abs(pts[0].x - last.x) > 0.05 or abs(pts[0].y - last.y) > 0.05:
                    cur = [pts[0].x, pts[0].y, pts[0].x, pts[0].y]; subs.append(cur)
                for q in pts:
                    cur[0] = min(cur[0], q.x); cur[1] = min(cur[1], q.y); cur[2] = max(cur[2], q.x); cur[3] = max(cur[3], q.y)
                last = pts[-1]
            for sb in subs:
                r = pymupdf.Rect(sb) * M
                shapes.append(["fill", round(r.x0,2), round(r.y0,2), round(r.x1,2), round(r.y1,2)] + [round(c,3) for c in (list(fill) + [0,0,0])[:3]])
        if col is not None and g.get("width") is not None:
            for it in g["items"]:
                if it[0] == "l":
                    a, b2 = it[1] * M, it[2] * M
                    shapes.append(["line", round(min(a.x,b2.x),2), round(min(a.y,b2.y),2), round(max(a.x,b2.x),2), round(max(a.y,b2.y),2), round(g["width"] or 1,2), 0, 0])
                elif it[0] == "re":
                    r = pymupdf.Rect(it[1]) * M
                    shapes.append(["box", round(r.x0,2), round(r.y0,2), round(r.x1,2), round(r.y1,2), round(g["width"] or 1,2), 0, 0])
    w, h = p.rect.width, p.rect.height
    return {"w": round(w,2), "h": round(h,2), "chars": chars, "shapes": shapes}
if __name__ == "__main__":
    d = pymupdf.open(sys.argv[1])
    out = [page_data(p) for p in d]
    json.dump(out, open(sys.argv[2], "w"))
