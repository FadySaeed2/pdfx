# Table finder prototype: the same steps the app will run, on the same data it sees.
import json, re, sys
from dataclasses import dataclass, field
from statistics import median

SPACES = {32, 160, 0x2002, 0x2003, 0x2009, 0x200A}
SYMFONT = re.compile(r"symbol|dingbat|wingding|webding|zapf|\bpi\b|universal", re.I)

@dataclass
class Word:
    x0: float; y0: float; x1: float; y1: float
    text: str; size: float; bold: bool; mini: bool; base: float; font: str; line: int; upright: bool

@dataclass
class Rule:
    y: float; x0: float; x1: float; lum: float

@dataclass
class Band:
    x0: float; y0: float; x1: float; y1: float; lum: float

def words_of(page):
    by_line = {}
    for c in page["chars"]:
        by_line.setdefault(c[10], []).append(c)
    out = []
    for li, cs in by_line.items():
        sizes = {}
        for c in cs:
            if c[6] in SPACES: continue
            k = round(c[7], 1); sizes[k] = sizes.get(k, 0) + 1
        if not sizes: continue
        main = max(sizes.keys())
        upright = abs(cs[0][11] - 1) < 0.05 and abs(cs[0][12]) < 0.05
        bases = [c[5] for c in cs if abs(round(c[7], 1) - main) < 0.05]
        mainbase = median(bases) if bases else median(c[5] for c in cs)
        cur = None; prev = None
        def flush():
            nonlocal cur
            if cur: out.append(cur)
            cur = None
        for c in cs:
            code = c[6]
            if code in SPACES:
                flush(); prev = c; continue
            small = c[7] < 0.82 * main
            gap = c[0] - prev[2] if prev is not None else 0
            if cur is not None and (gap > 0.25 * max(c[7], cur.size) or small != cur.mini or gap < -0.5 * c[7]):
                flush()
            ch = chr(code)
            if SYMFONT.search(c[9] or "") and (48 <= code <= 57 or 65 <= code <= 90 or 97 <= code <= 122):
                ch = "\u27e6" + ch + "\u27e7"   # a glyph the file does not name: shown as drawn
            if cur is None:
                cur = Word(c[0], c[1], c[2], c[3], ch, c[7], bool(c[8]), small, c[5], c[9], li, upright)
            else:
                cur.x0 = min(cur.x0, c[0]); cur.y0 = min(cur.y0, c[1]); cur.x1 = max(cur.x1, c[2]); cur.y1 = max(cur.y1, c[3])
                cur.text += ch; cur.bold = cur.bold and bool(c[8]); cur.size = max(cur.size, c[7])
            prev = c
        flush()
    for w in out:
        pass
    return out

VR = []
def shapes_of(page):
    rules, bands = [], []
    VR.clear()
    for s in page["shapes"]:
        kind, x0, y0, x1, y1 = s[0], s[1], s[2], s[3], s[4]
        w, h = x1 - x0, y1 - y0
        if kind == "fill":
            lum = 0.3 * s[5] + 0.59 * s[6] + 0.11 * s[7]
            if w >= 40 and h <= 3 and lum < 0.97: rules.append(Rule((y0 + y1) / 2, x0, x1, lum))
            elif h >= 40 and w <= 3 and lum < 0.97: VR.append((x0, y0, x1, y1))
            elif h > 3 and lum < 0.97 and w > 3: bands.append(Band(x0, y0, x1, y1, lum))
        elif kind == "line":
            if h < 1 and w >= 40: rules.append(Rule((y0 + y1) / 2, x0, x1, 0))
            elif w < 1 and h >= 40: VR.append((x0, y0, x1, y1))
        elif kind == "box":
            # A stroked rectangle is four lines, as a path walker sees it.
            if w >= 40:
                rules.append(Rule(y0, x0, x1, 0))
                rules.append(Rule(y1, x0, x1, 0))
            if h >= 40:
                VR.append((x0, y0, x0, y1)); VR.append((x1, y0, x1, y1))
    # Bands drawn in pieces (one per column) count as one band.
    bands.sort(key=lambda b: (round(b.y0), round(b.y1), b.x0))
    merged = []
    for b in bands:
        m = merged[-1] if merged else None
        if m and abs(m.y0 - b.y0) < 1 and abs(m.y1 - b.y1) < 1 and abs(m.lum - b.lum) < 0.02 and b.x0 <= m.x1 + 1.5:
            m.x1 = max(m.x1, b.x1); continue
        merged.append(Band(b.x0, b.y0, b.x1, b.y1, b.lum))
    # Pieces narrower than 40 points were left out above; let them join their neighbours.
    return rules, merged

def lines_of(words):
    """Words grouped into text lines, left to right, the lines in reading order."""
    lines = {}
    for w in words:
        lines.setdefault(w.line, []).append(w)
    out = [sorted(v, key=lambda w: w.x0) for v in lines.values()]
    out.sort(key=lambda l: (round(line_base(l)), l[0].x0))
    return out

def line_base(l):
    mains = [w.base for w in l if not w.mini]
    return median(mains) if mains else median(w.base for w in l)

def baseline_lines(ws, size):
    """Words grouped by baseline across the whole width (one visual line), small raised ones joining the line they sit on."""
    mains = sorted([w for w in ws if not w.mini], key=lambda w: w.base)
    lines = []
    for w in mains:
        if lines and abs(lines[-1][0] - w.base) < 0.35 * size: lines[-1][1].append(w)
        else: lines.append([w.base, [w]])
    for w in ws:
        if not w.mini: continue
        if not lines: lines.append([w.base, [w]]); continue
        mid = (w.y0 + w.y1) / 2
        inside = [l for l in lines if min(x.y0 for x in l[1]) - 0.5 <= mid <= max(x.y1 for x in l[1]) + 0.5]
        best = min(inside or lines, key=lambda l: abs(l[0] - w.base))
        best[1].append(w)
    for l in lines: l[1].sort(key=lambda w: w.x0)
    return lines

CAP = re.compile(r"^(TABLE|Table)$")
NUM = re.compile(r"^[A-Z]?\d+([.–\-]\d+)?[.:]?$")

@dataclass
class Table:
    page: int
    caption: str
    number: str
    box: tuple
    header: list
    rows: list          # list of (kind, [cell texts]) kind: "row" | "group"
    notes: list
    kind: str           # "grid" | "cards" | "list" | "image"
    why: str = ""
    cols: int = 0

def text_of(ws):
    """Words of one line or cell, in reading order, small raised ones marked ^…^."""
    out = ""
    prev = None
    for w in ws:
        t = "^" + w.text + "^" if w.mini else w.text
        if prev is not None and not (w.mini or prev.mini) or (prev is not None and w.x0 - prev.x1 > 0.25 * w.size):
            out += " "
        out += t
        prev = w
    return out

def find_tables(page, pno):
    words = words_of(page)
    rules, bands = shapes_of(page)
    upright = [w for w in words if w.upright]
    lines = lines_of(upright)
    lines.sort(key=lambda l: (min(w.y0 for w in l), l[0].x0))
    tables = []
    sizes = sorted(w.size for w in upright if not w.mini)
    body = median(sizes) if sizes else 10
    for li, line in enumerate(lines):
        if len(line) < 2 or not CAP.match(line[0].text) or not NUM.match(line[1].text): continue
        cap = line
        capy0 = min(w.y0 for w in cap); capy1 = max(w.y1 for w in cap)
        # It must look like a caption: bold, or set on a band, or with a rule or band just below.
        near = [r for r in rules if capy1 - 2 < r.y < capy1 + 30] + [b for b in bands if b.y0 < capy1 + 30 and b.y1 > capy0 - 2]
        if not (cap[0].bold or near): continue
        tables.append(region_table(pno, page, upright, rules, bands, cap, body))
    tables += ruled_tables(pno, upright, rules, bands, body, [t.box for t in tables])
    return tables

def ruled_tables(pno, words, rules, bands, body, taken):
    """Tables with no caption: a row of short column labels between two rules, rows under it, and a closing rule."""
    out = []
    wide = sorted([r for r in rules if r.x1 - r.x0 >= 120], key=lambda r: r.y)
    dark = [r for r in wide if r.lum < 0.9]
    used = list(taken)
    for i, a in enumerate(dark):
        for b in dark[i + 1:]:
            if b.y - a.y < 6: continue
            if b.y - a.y > 45: break
            if min(a.x1, b.x1) - max(a.x0, b.x0) < 0.8 * (a.x1 - a.x0): continue
            x0, x1 = max(a.x0, b.x0), min(a.x1, b.x1)
            if any(t[1] - 2 < b.y < t[3] + 2 and min(t[2], x1) - max(t[0], x0) > 0 for t in used): continue
            hw = [w for w in words if a.y < (w.y0 + w.y1) / 2 < b.y and x0 - 3 < w.x0 and w.x1 < x1 + 3]
            if not hw: continue
            bl = baseline_lines(hw, body)
            labels = header_labels(hw, body)
            if not (1 <= len(bl) <= 3 and len(labels) >= 2): continue
            if any(len(l[4]) > 35 or l[4][:1] in BULLETS for l in labels): continue
            if any(w.bold for w in hw if not w.mini) and not all(w.bold for w in hw if not w.mini): continue
            ends = [r for r in wide if r.y > b.y + 10 and min(r.x1, x1) - max(r.x0, x0) > 0.8 * (x1 - x0)]
            if not ends: continue
            c = ends[0].y
            bw = [w for w in words if b.y < (w.y0 + w.y1) / 2 < c and x0 - 3 < w.x0 and w.x1 < x1 + 3]
            if len(baseline_lines(bw, body)) < 2: continue
            above = [l for l in lines_of(words) if 0 < a.y - max(w.y1 for w in l) < 22 and all(w.bold for w in l if not w.mini) and x0 - 3 < l[0].x0 and re.search(r"[A-Za-z]{3}", text_of(l))]
            title = text_of(above[-1]) if above else ""
            top = min(min(w.y0 for w in above[-1]), a.y) if above else a.y
            notes = []
            after = [w for w in words if x0 - 3 < w.x0 and w.x1 < x1 + 3 and w.y0 > c - 0.5]
            kept = []; last = c
            for base, l in baseline_lines(after, body):
                tp = min(w.y0 for w in l); ms = [w.size for w in l if not w.mini]
                if not ms or max(ms) > 0.92 * body or tp - last > 14: break
                kept.append([base, l]); last = max(w.y1 for w in l)
            notes = note_paragraphs(kept, body)
            t = build(pno, title, "", (x0, top, x1, c), hw, bw, notes, body)
            if t.kind == "image" or t.cols < 2: continue
            out.append(t); used.append(t.box)
            break
    return out

def region_table(pno, page, words, rules, bands, cap, body):
    capx0 = cap[0].x0; capy0 = min(w.y0 for w in cap); capy1 = max(w.y1 for w in cap)
    number = cap[1].text.rstrip(".:")
    # Width of the table: the widest rule or band near the caption.
    near = [(r.x0, r.x1) for r in rules if capy0 - 20 < r.y < capy1 + 40] + [(b.x0, b.x1) for b in bands if capy0 - 20 < b.y0 < capy1 + 40]
    if not near:
        return Table(pno, "", number, (0, 0, 0, 0), [], [], [], "image", "no rule or band near the caption")
    x0 = min(a for a, b in near); x1 = max(b for a, b in near)
    width = x1 - x0
    # Caption may run on to more lines: same style, just below.
    caption_lines = [cap]
    ys = capy1
    others = sorted([l for l in lines_of(words) if l is not cap], key=lambda l: min(w.y0 for w in l))
    # Edges across the table: rules, and the tops and bottoms of bands.
    edges = [r.y for r in rules if min(r.x1, x1) - max(r.x0, x0) > 0.6 * width]
    for b in bands:
        if min(b.x1, x1) - max(b.x0, x0) > 0.6 * width and b.y1 - b.y0 > 3:
            edges += [b.y0, b.y1]
    edges = sorted(edges)
    merged = []
    for e in edges:
        if merged and e - merged[-1] < 2.5: merged[-1] = max(merged[-1], e); continue
        merged.append(e)
    # The caption itself, and any of its following lines, sit above the first edge below it.
    below = [e for e in merged if e > capy1 - 1]
    if not below:
        return Table(pno, "", number, (x0, capy0, x1, capy1), [], [], [], "image", "no edge below the caption")
    first = below[0]
    capwords = [w for w in words if capy0 - 2 < w.y0 and w.y1 < first + 1 and x0 - 2 < w.x0 and w.x1 < x1 + 2]
    caption = " ".join(text_of(l) for l in sorted(lines_of(capwords), key=lambda l: min(w.y0 for w in l)))
    sides = [v for v in VR if abs(v[0] - x0) < 3 or abs(v[2] - x1) < 3]
    if len(below) < 3 and sides:
        # A framed table running on to the next page: it ends where its side lines end.
        bottom = max(v[3] for v in sides)
        if bottom > below[-1] + 10: below = below + [bottom]
    if len(below) < 2:
        return Table(pno, caption, number, (x0, capy0, x1, first), [], [], [], "image", "table runs on without an end")
    inside = lambda w, a, b: a - 0.5 < (w.y0 + w.y1) / 2 < b + 0.5 and x0 - 3 < w.x0 and w.x1 < x1 + 3
    def baselines(ws):
        bs = sorted(w.base for w in ws if not w.mini)
        n = 0; last = -99
        for b in bs:
            if b - last > 3: n += 1; last = b
        return n
    s1, s2 = below[0], below[1]
    between = [w for w in words if inside(w, s1, s2)]
    blines = baseline_lines(between, body) if between else []
    bulleted = any(l[1][0].text[:1] in BULLETS for l in blines)
    if 1 <= len(blines) <= 4 and len(below) >= 3 and not bulleted and all(len(w.text) < 40 for w in between):
        head_top, head_bot, end = s1, s2, below[2]
    else:
        head_top, head_bot, end = s1, s1, s2
    header_words = [w for w in words if inside(w, head_top, head_bot)]
    body_words = [w for w in words if inside(w, head_bot, end) and not CONT.match(w.text)]
    # Notes: a band right below, or smaller text right below.
    notes = []
    nb = [b for b in bands if abs(b.y0 - end) < 3 and min(b.x1, x1) - max(b.x0, x0) > 0.6 * width]
    if nb:
        nw = [w for w in words if inside(w, nb[0].y0, nb[0].y1)]
        notes = note_paragraphs(baseline_lines(nw, body), body)
    else:
        after = [w for w in words if x0 - 3 < w.x0 and w.x1 < x1 + 3 and w.y0 > end - 0.5]
        kept = []
        last = end
        for base, l in baseline_lines(after, body):
            top = min(w.y0 for w in l)
            mains = [w.size for w in l if not w.mini]
            if not mains or max(mains) > 0.92 * body or top - last > 14: break
            kept.append([base, l]); last = max(w.y1 for w in l)
        notes = note_paragraphs(kept, body)
    t = build(pno, caption, number, (x0, capy0, x1, end), header_words, body_words, notes, body)
    return t

CONT = re.compile(r"^\(?[Cc]ontinued\)?\.?$")
BULLETS = "•▪●◦■□–"
NOTE_START = re.compile(r"^(\^?[a-z*†‡§]\^?\.?|Note\.?|Notes\.?|Source\.?|Sources\.?|Abbreviations?:?|Key:?)$")

def note_paragraphs(lines, size):
    """Note lines joined into paragraphs; a new one at 'Note.', 'Source.', a footnote letter, or an abbreviations list."""
    paras = []
    for base, l in lines:
        t = text_of(l)
        first = l[0].text if not l[0].mini else "^" + l[0].text + "^"
        starts = bool(NOTE_START.match(first)) or l[0].mini or re.match(r"^[a-z]\.$", l[0].text) is not None
        if paras and not starts:
            prev = paras[-1]
            paras[-1] = prev + t if prev.endswith("-") and not prev.endswith(" -") else prev + " " + t
        else:
            paras.append(t)
    return paras

def gutters(ws, x0, x1, minw):
    res = 4
    n = int((x1 - x0 + 8) * res)
    cover = [False] * n
    for w in ws:
        a = int((w.x0 - x0 + 2) * res); b = int((w.x1 - x0 + 2) * res)
        for i in range(max(0, a), min(n, b)): cover[i] = True
    out = []; i = 0
    first = next((k for k in range(n) if cover[k]), n); last = max((k for k in range(n) if cover[k]), default=0)
    i = first
    while i <= last:
        if not cover[i]:
            j = i
            while j < n and not cover[j]: j += 1
            if (j - i) / res >= minw: out.append((x0 - 2 + i / res, x0 - 2 + j / res))
            i = j
        else: i += 1
    return out

def build(pno, caption, number, box, header_words, body_words, notes, body):
    x0, y0, x1, y1 = box
    if not body_words:
        return Table(pno, caption, number, box, [], [], notes, "image", "no body")
    size = median([w.size for w in body_words if not w.mini])
    # Text much smaller than the table's own is a superscript or subscript, even set apart.
    for w in body_words + header_words:
        if w.size < 0.82 * size: w.mini = True
    mains = [w for w in body_words if not w.mini]
    # Lines holding a single phrase (a group heading) may run across columns: leave them out when finding gutters.
    blines = baseline_lines(mains, size)
    def phrases(l):
        n = 1
        for a, b in zip(l, l[1:]):
            if b.x0 - a.x1 > 1.0 * size: n += 1
        return n
    counts = [phrases(l) for _, l in blines]
    multi = sorted(counts)[len(counts) // 2] >= 2 if counts else False
    lone = set()
    if multi:
        for (_, l), n in zip(blines, counts):
            if n == 1: lone.update(id(w) for w in l)
    g = gutters([w for w in mains if id(w) not in lone], x0, x1, max(2.6, 0.38 * size))
    cuts = [(a + b) / 2 for a, b in g]
    labels = header_labels(header_words, size)
    cuts = add_header_cuts(cuts, labels, mains, x0, x1)
    col = lambda w, cuts=cuts: sum(1 for k in cuts if (w.x0 + w.x1) / 2 > k)
    # A first column holding only numbers or bullets belongs with the text beside it.
    if cuts:
        firsts = [w for w in mains if col(w) == 0 and id(w) not in lone]
        if firsts and all(re.match(r"^(\d{1,2}[.)]?|[•▪●◦■–])$", w.text) for w in firsts):
            cuts = cuts[1:]
            col = lambda w, cuts=cuts: sum(1 for k in cuts if (w.x0 + w.x1) / 2 > k)
    ncol = len(cuts) + 1
    for w in mains:
        if id(w) in lone: continue
        for k in cuts:
            if w.x0 < k - 0.8 and w.x1 > k + 0.8:
                return Table(pno, caption, number, box, [], [], notes, "image", f"word '{w.text}' crosses a column boundary")
    header = [""] * ncol
    for lab in sorted(labels, key=lambda l: (l[1], l[0])):
        c = col(type("L", (), {"x0": lab[0], "x1": lab[2]})())
        crossing = [k for k in cuts if lab[0] < k - 0.8 and lab[2] > k + 0.8]
        if crossing:
            # Wider than its column is fine when each neighbour it reaches has its own label.
            for k in crossing:
                side = sum(1 for kk in cuts if kk < k)  # column left of k
                other = side + 1 if side == c else side
                if not any(col(type("L", (), {"x0": o[0], "x1": o[2]})()) == other and o is not lab for o in labels):
                    return Table(pno, caption, number, box, [], [], notes, "image", f"header '{lab[4]}' spans columns")
        header[c] = (header[c] + " " + lab[4]).strip()
    rows = make_rows(body_words, ncol, col, size, lone, cuts)
    if rows is None:
        return Table(pno, caption, number, box, [], [], notes, "image", "rows unclear")
    plain = [r for r in rows if r[0] == "row"]
    if ncol >= 3:
        for c in range(ncol):
            if not any(r[1][c].strip() for r in plain):
                return Table(pno, caption, number, box, header, rows, notes, "image", f"column {c} empty")
    kind = choose_kind(header, rows, ncol)
    return Table(pno, caption, number, box, header, rows, notes, kind, cols=ncol)

def header_labels(ws, size):
    """Header words joined into labels: words close on a line, stacked lines overlapping in x."""
    pieces = []
    for base, l in baseline_lines(ws, size):
        cur = None
        for w in l:
            if cur and (w.mini or w.x0 - cur[2] < 0.45 * size):
                cur = [cur[0], min(cur[1], w.y0), max(cur[2], w.x1), max(cur[3], w.y1), cur[4] + ("^" + w.text + "^" if w.mini else " " + w.text)]
            else:
                if cur: pieces.append(cur)
                cur = [w.x0, w.y0, w.x1, w.y1, w.text]
        if cur: pieces.append(cur)
    pieces.sort(key=lambda p: (p[1], p[0]))
    labels = []
    for p in pieces:
        hit = None
        for lab in labels:
            if min(lab[2], p[2]) - max(lab[0], p[0]) > 0 and p[1] - lab[3] < 1.2 * size:
                hit = lab; break
        if hit:
            hit[0] = min(hit[0], p[0]); hit[2] = max(hit[2], p[2]); hit[3] = max(hit[3], p[3]); hit[4] += " " + p[4]
        else:
            labels.append(list(p))
    return labels

def add_header_cuts(cuts, labels, mains, x0, x1):
    labels = sorted(labels, key=lambda l: l[0])
    out = list(cuts)
    for a, b in zip(labels, labels[1:]):
        if b[0] - a[2] < 1.0: continue
        if any((a[0] + a[2]) / 2 < k < (b[0] + b[2]) / 2 for k in out): continue
        right = [w for w in mains if b[0] - 6 <= w.x0 < max(b[2], b[0] + 30)]
        if not right: continue
        x = min(w.x0 for w in right) - 0.6
        if any(w.x0 < x - 0.3 and w.x1 > x + 0.3 for w in mains): continue
        if not [w for w in mains if w.x1 <= x + 0.3 and w.x0 >= a[0] - 6]: continue
        out.append(x)
    return sorted(out)

def make_rows(ws, ncol, col, size, lone=set(), cuts_=()):
    lines = baseline_lines(ws, size)
    if not lines: return None
    steps = [b[0] - a[0] for a, b in zip(lines, lines[1:])]
    med = median(steps) if steps else size * 1.2
    gapped = any(s > 1.35 * med for s in steps)
    col0_left = min((w.x0 for l in lines for w in l[1] if col(w) == 0 and not w.mini), default=0)
    rows = []
    def cells_of(line_words):
        cs = [[] for _ in range(ncol)]
        mains_ = [w for w in line_words if not w.mini]
        lonely = bool(mains_) and all(id(w) in lone for w in mains_) and any(w.x0 < k - 0.8 and w.x1 > k + 0.8 for w in mains_ for k in cuts_)
        for w in line_words: cs[0 if lonely else col(w)].append(w)
        return cs
    # Gapped tables with a column of one-line entries (a number, a duration): each entry starts a row.
    anchor = None
    if gapped and ncol > 2:
        best = 0
        for c in range(1, ncol):
            hits = [i for i, (b, lw) in enumerate(lines) if any(col(w) == c and not w.mini for w in lw)]
            if len(hits) < 2: continue
            single = all(b - a > 1 for a, b in zip(hits, hits[1:]))
            short = all(len(" ".join(w.text for w in lines[i][1] if col(w) == c and not w.mini)) <= 16 for i in hits)
            if single and short and len(hits) > best: anchor, best = c, len(hits)
    for i, (base, lw) in enumerate(lines):
        cs = cells_of(lw)
        c0 = [w for w in cs[0] if not w.mini]
        if not rows: start = True
        elif ncol == 1:
            start = False
        elif anchor is not None:
            has_anchor = any(col(w) == anchor for w in cs[anchor] if not w.mini)
            only0 = bool(c0) and all(not cs[k] for k in range(1, ncol))
            nxt = lines[i + 1][1] if i + 1 < len(lines) else []
            next_anchor = any(col(w) == anchor and not w.mini for w in nxt)
            heading = only0 and c0[0].x0 <= col0_left + 0.3 * size and ((base - lines[i - 1][0]) > 1.35 * med or next_anchor)
            start = has_anchor or heading
        elif gapped:
            start = (base - lines[i - 1][0]) > 1.35 * med
            # A lone first-column line opening a block is a heading of its own when another starts right under it.
        else:
            if c0:
                indented = c0[0].x0 > col0_left + 0.3 * size
                others_empty = all(not cs[k] for k in range(1, ncol))
                prev0 = next((line[0] for line in reversed(rows[-1]) if line[0]), [])
                prev_ends = bool(prev0) and prev0[-1].text[-1:] in ".:;?!)"
                start = not (indented and others_empty and not prev_ends and bool(prev0))
            else:
                start = False
        if start: rows.append([cs])
        else: rows[-1].append(cs)
    rights = [max((w.x1 for r in rows for line in r for w in line[c] if not w.mini), default=0) for c in range(ncol)]
    out = []
    for r in rows:
        # In a gapped table a block may open with a heading line (only the first column, then more rows).
        if ncol > 1 and len(r) > 1 and all(not r[0][k] for k in range(1, ncol)) and r[1][0] and any(r[1][k] for k in range(1, ncol)) and r[0][0][0].x0 < r[1][0][0].x0 - 0.3 * size:
            out.append(("group", [text_of(r[0][0])] + [""] * (ncol - 1)))
            r = r[1:]
        cells = [join_cell([line[c] for line in r if line[c]], size, rights[c]) for c in range(ncol)]
        nonempty = [c for c in range(ncol) if cells[c].strip()]
        kind = "group" if nonempty == [0] and ncol > 1 and len(rows) > 1 else "row"
        out.append((kind, cells))
    if ncol == 1:
        return [("row", [t]) for t in list_items(lines, size)]
    return out

def list_items(lines, size):
    """One column of lines as list items: a bullet starts one (indented bullets are a level down); a short line ending a sentence ends one."""
    marks = [l[1][0].x0 for l in lines if l[1] and l[1][0].text[:1] in BULLETS]
    left = min(marks) if marks else 0
    right = max((w.x1 for _, l in lines for w in l if not w.mini), default=0)
    items = []
    prev = None
    for base, l in lines:
        mains = [w for w in l if not w.mini]
        if not mains: continue
        t = text_of(l)
        first = mains[0]
        bullet = first.text[:1] in BULLETS or first.text[:1] == "\u27e6"
        if bullet:
            items.append(("\t" if first.x0 > left + 0.5 * size else "") + t)
        elif items and prev is not None:
            pmains = [w for w in prev if not w.mini]
            short = pmains and pmains[-1].x1 < right - 2.5 * size
            pt = items[-1]
            if short and pt[-1:] in ".:;?!" and first.text[:1].isupper(): items.append(t)
            elif pt.endswith("-") and not pt.endswith(" -"): items[-1] = pt + t
            elif pt.endswith("/"): items[-1] = pt + t
            else: items[-1] = pt + " " + t
        else:
            items.append(t)
        prev = l
    return items

def col_right(r, c):
    xs = [w.x1 for line in r for w in line[c]]
    return max(xs) if xs else 0

def join_cell(parts, size, right=0):
    """Lines of one cell joined: wrapped lines run on; a bullet, or a short line ending a sentence, starts a new line."""
    text = ""
    prev = None
    for p in parts:
        t = text_of(p)
        if prev is None: text = t
        else:
            pt = text.rstrip()
            mains = [w for w in prev if not w.mini]
            short = right and mains and mains[-1].x1 < right - 2.5 * size
            first = next((w.text for w in p if not w.mini), "")
            if t[:1] in BULLETS or t[:1] == "\u27e6" or t[:2] == "+ ": text = pt + "\n" + t
            elif pt.endswith("/"): text = pt + t
            elif short and pt[-1:] in ".:;?!" and first[:1].isupper(): text = pt + "\n" + t
            elif pt.endswith("-") and not pt.endswith(" -"): text = pt + t
            else: text = pt + " " + t
        prev = p
    return text

def choose_kind(header, rows, ncol):
    plain = [r for r in rows if r[0] == "row"]
    if ncol == 1: return "list"
    first = [r[1][0].strip() for r in plain]
    if ncol == 2 and first and all(f in ("•", "▪", "●", "–") for f in first): return "list"
    longest = max((len(r[1][c]) for r in plain for c in range(1, ncol)), default=0)
    avg = sum(len(r[1][c]) for r in plain for c in range(1, ncol)) / max(1, len(plain) * (ncol - 1))
    if ncol == 2 or avg > 28 or longest > 90: return "cards"
    return "grid"

def merge_continued(tables):
    out = []
    for t in tables:
        p = out[-1] if out else None
        if p and t.number and p.number == t.number and t.page == getattr(p, 'pages', [p.page])[-1] + 1 and p.kind != "image" and t.kind != "image" and p.cols == t.cols and (not t.header or not any(t.header) or t.header == p.header):
            p.rows = p.rows + t.rows
            p.notes = p.notes + [n for n in t.notes if n not in p.notes]
            p.pages = getattr(p, "pages", [p.page]) + [t.page]
            continue
        out.append(t)
    return out

def find_all(pages):
    found = []
    for i, pg in enumerate(pages):
        found += find_tables(pg, i)
    return merge_continued(found)

def show(t):
    print(f"=== p{t.page + 1} Table {t.number} [{t.kind}] {t.why}")
    print("   caption:", t.caption[:110])
    if t.header: print("   HEADER:", " | ".join(t.header))
    for k, cells in t.rows:
        print("   " + ("GROUP: " if k == "group" else "ROW:   ") + " | ".join(c.replace("\n", " ⏎ ") for c in cells))
    for n in t.notes: print("   note:", n[:120])

def golden(t):
    return {"page": t.page, "pages": getattr(t, "pages", [t.page]), "number": t.number, "caption": t.caption, "kind": t.kind,
            "header": t.header, "rows": [{"group": k == "group", "cells": c} for k, c in t.rows], "notes": t.notes}

if __name__ == "__main__" and sys.argv[1] == "golden":
    pages = json.load(open(sys.argv[2]))
    allt = []
    for i, pg in enumerate(pages): allt += find_tables(pg, i)
    json.dump([golden(t) for t in allt], open(sys.argv[3], "w"), ensure_ascii=False, indent=1)
    sys.exit(0)

if __name__ == "__main__":
    pages = json.load(open(sys.argv[1]))
    want = [int(x) - 1 for x in sys.argv[2:]]
    if want:
        for i in want:
            for t in find_tables(pages[i], i): show(t)
    else:
        for t in find_all(pages): show(t)
