"""Builds site/index.html, Regolith's product page, from site-src/template.html.

Run from the repo root: python3 site-src/build.py

The drawn screens (Collections and Mixes, the Search gesture, the inner screen's
Home and pinch) are generated here so every poster gets the same markup. Their
photos are in site/images/photos, from Unsplash via Lorem Picsum; photos.txt
lists each one's id, photographer and Unsplash page, and the footer credits
whoever's photos the page actually uses.
"""
import re

OUT = "site/index.html"
PHOTOS = dict((l.split(" | ")[0], l.split(" | ")[1]) for l in open("site-src/photos.txt").read().splitlines() if l.strip())

def ph(i):
    return f"background-image:url(images/photos/{i}.jpg)"

def poster(name, meta, photo, badge=None, res=None, mix=None):
    over = ""
    if badge: over += f'<span class="badge"><svg><use href="#i-folder"/></svg>{badge}</span>'
    if res: over += f'<span class="res">{res}</span>'
    if mix:
        a, b = mix
        art = f'<div class="d-art deck"><i style="{ph(a)}"></i><i style="{ph(b)}"></i>{over}</div>'
    else:
        art = f'<div class="d-art" style="{ph(photo)}">{over}</div>'
    return f'<div class="d-poster">{art}<div class="d-name">{name}</div><div class="d-meta">{meta}</div></div>'

def grid(cls, items):
    return f'<div class="d-grid {cls}">' + "".join(poster(*it) for it in items) + "</div>"

PILL = '''<div class="d-pill{cls}"><span><svg><use href="#i-home"/></svg>Home</span><span class="on"><svg><use href="#i-grid"/></svg>Library</span><span><svg><use href="#i-folder"/></svg>Browse</span><span><svg><use href="#i-phone"/></svg>Shorts</span><span><svg><use href="#i-gear"/></svg>Settings</span></div>'''
HANDLE = '<div class="d-handle"></div>'
RAIL = '''<div class="d-rail"><span{h}><svg><use href="#i-home"/></svg>Home</span><span{l}><svg><use href="#i-grid"/></svg>Library</span><span><svg><use href="#i-folder"/></svg>Browse</span><span><svg><use href="#i-phone"/></svg>Shorts</span><span><svg><use href="#i-gear"/></svg>Settings</span></div>'''

# The share's top level, as photos: (name, meta, photo, badge, res)
TOP = [
    ("Concerts", "18 titles", 274, "6", "4K"),
    ("Films", "96 videos", 43, None, "4K"),
    ("Home Videos", "212 videos", 129, None, "1080p"),
    ("Series", "140 videos", 238, "12", "1080p"),
    ("Travel", "58 videos", 177, "4", "4K"),
    ("Greece 2024", "33 videos", 49, None, "4K"),
    ("Kids", "75 videos", 76, None, "1080p"),
    ("Hikes", "24 videos", 17, None, "1080p"),
    ("Film Club", "17 videos", 250, None, "1080p"),
    ("Cooking", "41 videos", 225, None, "1080p"),
    ("Road Trip", "62 videos", 184, None, "4K"),
    ("Snow Trip", "19 videos", 260, None, "1080p"),
    ("Amsterdam", "27 videos", 164, None, "1080p"),
    ("Scotland", "38 videos", 200, None, "4K"),
]
CONCERTS = [
    ("Live at The Echo", "14 videos", 274, "14", "4K"),
    ("Spring Tour", "22 videos", 257, "22", "1080p"),
    ("Band Practice", "31 videos", 39, "31", "1080p"),
    ("Fall Showcase", "9 videos", 167, "9", "1080p"),
    ("Soundcheck", "6 videos", 137, "6", "4K"),
    ("Acoustic Set", "8 videos", 129, "8", "1080p"),
]
MIXES = [
    ("Assorted", "27 videos", None, "27", None, (65, 225)),
    ("Phone Dumps", "63 videos", None, "63", None, (100, 76)),
    ("Misc 2024", "19 videos", None, "19", None, (204, 152)),
]
NEWLY = [16, 10, 11, 15, 28, 29, 37, 110, 116, 122, 128, 142, 155, 214]

def bar(title, sub, back=False, pad=""):
    b = '<span class="d-act"><svg><use href="#i-back"/></svg></span>' if back else ""
    return (f'<div class="d-bar"{pad}>{b}<div class="d-title"><b>{title}</b><span>{sub}</span></div>'
            '<span class="d-act"><svg><use href="#i-search"/></svg></span><span class="d-act"><svg><use href="#i-sort"/></svg></span><span class="d-act"><svg><use href="#i-more"/></svg></span></div>')

# ── Main screen · Collections and Mixes, the chip row tucking away on scroll ──
chip_scroll = f'''
<div class="dp cover cs" aria-hidden="true">
  {bar("CONCERTS", "18 titles", back=True)}
  <div class="cs-chips"><div class="d-chips"><span class="d-chip on">All</span><span class="d-chip">Collections <i>6</i></span><span class="d-chip">Mixes <i>3</i></span></div></div>
  <div class="cs-clip"><div class="d-wall cs-wall">
    <div class="d-head">Collections</div>
    {grid("c3", CONCERTS)}
    <div class="d-head">Mixes</div>
    {grid("c3", MIXES)}
  </div></div>
  <div class="finger cs-finger"></div>
  {PILL.format(cls=" cs-pill")}
  {HANDLE}
</div>'''

# ── Main screen · swipe up off the nav to search ──
def hit(title, meta, photo, link, mix=False):
    tag = ' <span class="d-tag">MIX</span>' if mix else ""
    return (f'<div class="d-hit"><div class="d-thumb" style="{ph(photo)}"></div><div class="d-lines"><div class="d-p">{title}</div>'
            f'<div class="d-m">{meta}</div><span class="d-link"><svg><use href="#i-folder"/></svg>{link}{tag} ›</span></div></div>')

search_gesture = f'''
<div class="dp cover sg" aria-hidden="true">
  <div class="sg-lib">
    {bar("MEDIA", "Tower · media · 1,284 files")}
    <div class="d-chips"><span class="d-chip on"><svg><use href="#i-play"/></svg>Videos</span><span class="d-chip"><svg><use href="#i-bookmark"/></svg>Moments</span><span class="d-chip"><svg><use href="#i-image"/></svg>Images</span></div>
    <div class="d-wall">{grid("c3", [t[:5] for t in TOP[:9]])}</div>
    {PILL.format(cls=" sg-pill")}
  </div>
  <div class="sg-search">
    <div class="d-field"><svg><use href="#i-search"/></svg><span class="sg-ph">Titles, filenames, folders</span><span class="sg-q">echo</span><span class="caret"></span><span class="cancel">Cancel</span></div>
    <div class="sg-results">
      <div class="d-chips"><span class="d-chip red">All</span><span class="d-chip">Unwatched</span><span class="d-chip">4K</span><span class="d-chip">On device</span></div>
      <div class="d-wall">
        <div class="d-head">Collections · 2</div>
        <div class="d-shelf">
          {poster("Live at The <mark>Echo</mark>", "14 videos", 274, "14")}
          {poster("<mark>Echo</mark> Park Rehearsals", "8 videos", 249, "8")}
        </div>
        <div class="d-head" style="padding-top:12px">7 videos</div>
        {hit("<mark>Echo</mark> Park Rehearsal 03", "1080p · 52m 18s", 249, "<mark>Echo</mark> Park Rehearsals")}
        {hit("Opening Set", "4K · 41m 03s", 274, "Live at The <mark>Echo</mark>")}
      </div>
    </div>
    <div class="sg-kb">{"".join('<div class="kb-row">' + "".join(f"<span>{k}</span>" for k in row) + "</div>" for row in ("qwertyuiop", "asdfghjkl", "zxcvbnm"))}<div class="kb-row"><span class="space">space</span></div></div>
  </div>
  <div class="sg-capsule">
    <svg class="sg-ring" preserveAspectRatio="none"><rect x="1" y="1" rx="29" pathLength="100"/></svg>
    <svg class="sg-glass"><use href="#i-search"/></svg>
    <span class="sg-l1">Swipe up to search</span><span class="sg-l2">Let go to search</span>
  </div>
  <div class="finger sg-finger"></div>
  {HANDLE}
</div>'''

# ── Inner screen · Home ──
inner_home = f'''
<div class="dp inner" aria-hidden="true">
  <div class="d-bar" style="padding-left:96px"><div class="d-title"><b>HOME</b></div><span class="d-act"><svg><use href="#i-search"/></svg></span></div>
  <div class="d-wall" style="padding-left:96px">
    <div class="d-head">Continue watching</div>
    <div class="cw">
      <div class="cw-card"><div class="cw-art" style="{ph(100)}"><span class="cw-left">Under a minute left</span><span class="cw-bar"><i style="width:92%"></i></span></div><div class="d-name">Beach day</div><div class="d-meta">1080p · today</div></div>
      <div class="cw-card"><div class="cw-art" style="{ph(184)}"><span class="cw-left">24m left</span><span class="cw-bar"><i style="width:40%"></i></span></div><div class="d-name">Road Trip, Day 3</div><div class="d-meta">4K · yesterday</div></div>
      <div class="cw-card"><div class="cw-art" style="{ph(29)}"><span class="cw-left">8m left</span><span class="cw-bar"><i style="width:70%"></i></span></div><div class="d-name">Above the Glacier</div><div class="d-meta">4K · Sunday</div></div>
    </div>
    <div class="d-head">Newly added</div>
    <div class="d-grid c7">{"".join(f'<div class="d-poster"><div class="d-art" style="{ph(i)}"></div></div>' for i in NEWLY[:7])}</div>
    <div class="d-head">Moments</div>
    <div class="d-chips" style="padding:0"><span class="d-chip">Arrival <i>1</i></span><span class="d-chip">Cannonball <i>2</i></span><span class="d-chip">Rope swing <i>1</i></span><span class="d-chip">Sunset <i>2</i></span><span class="d-chip">The view <i>1</i></span><span class="d-chip">Encore <i>3</i></span></div>
  </div>
  {RAIL.format(h=' class="on"', l="")}
</div>'''

# ── Inner screen · the Library, pinched from five across to seven ──
inner_pinch = f'''
<div class="dp inner pz" aria-hidden="true">
  {bar("MEDIA", "Tower · media · 1,284 files", pad=' style="padding-left:96px"')}
  <div class="d-chips" style="padding-left:96px"><span class="d-chip on"><svg><use href="#i-play"/></svg>Videos</span><span class="d-chip"><svg><use href="#i-bookmark"/></svg>Moments</span><span class="d-chip"><svg><use href="#i-image"/></svg>Images</span><span class="d-sep"></span><span class="d-chip on">All</span><span class="d-chip">Collections <i>14</i></span><span class="d-chip">Mixes <i>3</i></span></div>
  <div class="pz-clip"><div class="pz-zoom">
    <div class="d-head">Collections</div>
    {grid("c7", TOP)}
  </div></div>
  <div class="pz-pill"><span class="n5">5 across</span><span class="n7">7 across</span></div>
  <div class="finger pz-a"></div><div class="finger pz-b"></div>
  {RAIL.format(h="", l=' class="on"')}
</div>'''

html = open("site-src/template.html").read()
html = (html.replace("{{CHIP_SCROLL}}", chip_scroll)
            .replace("{{SEARCH_GESTURE}}", search_gesture)
            .replace("{{INNER_HOME}}", inner_home)
            .replace("{{INNER_PINCH}}", inner_pinch)
            )
used = sorted(set(re.findall(r"images/photos/(\d+)\.jpg", html)), key=int)
missing = [i for i in used if i not in PHOTOS]
assert not missing, f"no photographer listed in photos.txt for {missing}"
html = html.replace("{{AUTHORS}}", ", ".join(sorted({PHOTOS[i] for i in used}, key=str.lower)))
open(OUT, "w").write(html)
print("wrote", OUT, len(html))
