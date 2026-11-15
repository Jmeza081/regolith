"""Builds site/index.html, Regolith's product page, from site-src/template.html.

Run from the repo root: python3 site-src/build.py

Every phone on the page is drawn here, not screenshotted, so every poster and pile
gets the same markup: the Library, a collection's page and its selection, Collections
and Mixes, the Search gesture, the player's Color bleed, an album and its story, the
main screen that opens out into the inner screen's pinch, and the inner screen's Home.
Color bleed and the opening fold are driven by the script at the bottom of the
template. Their
photos are in site/images/photos, from Unsplash via Lorem Picsum; photos.txt
lists each one's id, photographer and Unsplash page, and the footer credits
whoever's photos the page actually uses.
"""
import re

OUT = "site/index.html"
PHOTOS = dict((l.split(" | ")[0], l.split(" | ")[1]) for l in open("site-src/photos.txt").read().splitlines() if l.strip())

# A collection with a poster.jpg wears a film poster (site-src/posters.html, rendered to
# images/posters by render_posters.mjs). One without, and every mix, wears a pile of its own
# frames, as the app draws it (PILES, below). The 16:9 video frames (Continue watching, search
# hits) and the player's film stay photos. Each poster is built over one of the photos, which is
# how its photographer still gets credited: name -> photo id.
POSTERS = {"concerts": 274, "films": 43, "series": 238, "travel": 177,
           "greece-2024": 49, "bikes": 76, "hikes": 17, "film-club": 250, "cooking": 225,
           "road-trip": 184, "snow-trip": 260, "amsterdam": 164, "scotland": 200,
           "live-at-the-echo": 274, "spring-tour": 257, "band-practice": 39, "fall-showcase": 167,
           "soundcheck": 137, "acoustic-set": 129, "echo-park-rehearsals": 249}

def ph(i):
    if i in POSTERS: return f"background-image:url(images/posters/{i}.jpg)"
    return f"background-image:url(images/photos/{i}.jpg)"

# Frames of tall phone clips, for the piles: a folder with no poster.jpg, and each mix.
PILES = {
    "home-videos": [838, 64, 1012, 660, 1025],
    "assorted": [431, 63, 835, 1080, 429],
    "phone-dumps": [837, 1062, 237, 659, 40],
    "misc-2024": [452, 453, 836, 407, 30],
}

def pile(frames, over="", cls="pile"):
    """A deck kept as a pile (DeckLayout.pileCards): up to five frames fanned from below like a
    hand of cards, the front one upright in the middle, drawn last. CSS places and turns them by
    rank (.p0 is the front card)."""
    order = [4, 3, 2, 1, 0][5 - len(frames):]
    cards = "".join(f'<i class="p{k}" style="{ph(frames[k])}"></i>' for k in order)
    return f'<div class="d-art {cls}">{cards}{over}</div>'

def poster(name, meta, art, badge=None, res=None):
    """A tile on a wall. `art` names a poster, a photo id, or a pile in PILES (no res chip there:
    it would sit on the frames, which the app avoids)."""
    over = ""
    if badge: over += f'<span class="badge"><svg><use href="#i-folder"/></svg>{badge}</span>'
    if art in PILES:
        tile = pile(PILES[art], over)
    else:
        if res: over += f'<span class="res">{res}</span>'
        tile = f'<div class="d-art" style="{ph(art)}">{over}</div>'
    return f'<div class="d-poster">{tile}<div class="d-name">{name}</div><div class="d-meta">{meta}</div></div>'

def grid(cls, items):
    return f'<div class="d-grid {cls}">' + "".join(poster(*it) for it in items) + "</div>"

PILL = '''<div class="d-pill{cls}"><span><svg><use href="#i-home"/></svg>Home</span><span class="on"><svg><use href="#i-grid"/></svg>Library</span><span><svg><use href="#i-folder"/></svg>Browse</span><span><svg><use href="#i-phone"/></svg>Shorts</span><span><svg><use href="#i-gear"/></svg>Settings</span></div>'''
HANDLE = '<div class="d-handle"></div>'
RAIL = '''<div class="d-rail"><span{h}><svg><use href="#i-home"/></svg>Home</span><span{l}><svg><use href="#i-grid"/></svg>Library</span><span><svg><use href="#i-folder"/></svg>Browse</span><span><svg><use href="#i-phone"/></svg>Shorts</span><span><svg><use href="#i-gear"/></svg>Settings</span></div>'''

# The share's top level: (name, meta, poster or pile, badge, res)
TOP = [
    ("Concerts", "18 titles", "concerts", "6", "4K"),
    ("Films", "96 videos", "films", None, "4K"),
    ("Home Videos", "212 videos", "home-videos", None, "1080p"),
    ("Series", "140 videos", "series", "12", "1080p"),
    ("Travel", "58 videos", "travel", "4", "4K"),
    ("Greece 2024", "33 videos", "greece-2024", None, "4K"),
    ("Bikes", "75 videos", "bikes", None, "1080p"),
    ("Hikes", "24 videos", "hikes", None, "1080p"),
    ("Film Club", "17 videos", "film-club", None, "1080p"),
    ("Cooking", "41 videos", "cooking", None, "1080p"),
    ("Road Trip", "62 videos", "road-trip", None, "4K"),
    ("Snow Trip", "19 videos", "snow-trip", None, "1080p"),
    ("Amsterdam", "27 videos", "amsterdam", None, "1080p"),
    ("Scotland", "38 videos", "scotland", None, "4K"),
]
CONCERTS = [
    ("Live at The Echo", "14 videos", "live-at-the-echo", "14", "4K"),
    ("Spring Tour", "22 videos", "spring-tour", "22", "1080p"),
    ("Band Practice", "31 videos", "band-practice", "31", "1080p"),
    ("Fall Showcase", "9 videos", "fall-showcase", "9", "1080p"),
    ("Soundcheck", "6 videos", "soundcheck", "6", "4K"),
    ("Acoustic Set", "8 videos", "acoustic-set", "8", "1080p"),
]
MIXES = [
    ("Assorted", "27 videos", "assorted", "27"),
    ("Phone Dumps", "63 videos", "phone-dumps", "63"),
    ("Misc 2024", "19 videos", "misc-2024", "19"),
]
# Newly added on the inner screen's Home: the latest collections to arrive.
NEWLY = ["spring-tour", "fall-showcase", "hikes", "snow-trip", "acoustic-set", "scotland", "soundcheck"]

KINDS = '<span class="d-chip on">All</span><span class="d-chip">Collections <i>14</i></span><span class="d-chip">Mixes <i>3</i></span>'

def wall(cls, collections):
    """The Library's wall split as the app splits it: Collections, then Mixes as piles."""
    return (f'<div class="d-head">Collections</div>{grid(cls, [t[:5] for t in collections])}'
            f'<div class="d-head">Mixes</div>{grid(cls, MIXES)}')

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
    <div class="d-chips">{KINDS}</div>
    <div class="d-wall">{wall("c3", TOP[:6])}</div>
    {PILL.format(cls=" sg-pill")}
  </div>
  <div class="sg-search">
    <div class="d-field"><svg><use href="#i-search"/></svg><span class="sg-ph">Titles, filenames, folders</span><span class="sg-q">echo</span><span class="caret"></span><span class="cancel">Cancel</span></div>
    <div class="sg-results">
      <div class="d-chips"><span class="d-chip red">All</span><span class="d-chip">Unwatched</span><span class="d-chip">4K</span><span class="d-chip">On device</span></div>
      <div class="d-wall">
        <div class="d-head">Collections · 2</div>
        <div class="d-shelf">
          {poster("Live at The <mark>Echo</mark>", "14 videos", "live-at-the-echo", "14")}
          {poster("<mark>Echo</mark> Park Rehearsals", "8 videos", "echo-park-rehearsals", "8")}
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
    {wall("c7", TOP)}
  </div></div>
  <div class="pz-pill"><span class="n5">5 across</span><span class="n7">7 across</span></div>
  <div class="finger pz-a"></div><div class="finger pz-b"></div>
  {RAIL.format(h="", l=' class="on"')}
</div>'''

# ── Main screen · the Library. Drawn twice: full height in the hero and the Library section,
#    and short on the back of the Fold's left half before it opens, where it is the shape of half
#    the open phone. Either way it is the wall the inner screen opens into, three across. ──
def library(cls, collections):
    return f'''
<div class="dp cover{cls}" aria-hidden="true">
  {bar("MEDIA", "Tower · media · 1,284 files")}
  <div class="d-chips">{KINDS}</div>
  <div class="d-wall">{wall("c3", collections)}</div>
  {PILL.format(cls="")}
  {HANDLE}
</div>'''
library_wall = library("", TOP[:6])
cover_closed = library(" short", TOP[:3])

# ── Main screen · Home Videos, a collection's page. It has no poster.jpg, so its page is lit by
#    its frames laid loose (DeckLayout.looseCards): the whole hand, every card whole. ──
HOME_VIDEOS = [(838, "Saturday downtown", "45s"), (64, "Lunch outside", "27s"), (1012, "Dog walk", "24s"),
               (660, "Sparklers", "42s"), (1025, "Blanket fort", "38s"), (646, "Golden hour", "31s"),
               (839, "First bike ride", "52s"), (823, "Photo walk", "19s")]

def clip(photo, name, meta, picked=None, watched=False):
    """A video on a collection's page: its frame, resolution, and an unwatched ring, or, while
    picking, a check (picked=True) or an empty circle (picked=False)."""
    corner = '<span class="ring"></span>' if picked is None and not watched else ""
    if picked is True: corner = '<span class="pick on"><svg><use href="#i-check"/></svg></span>'
    if picked is False: corner = '<span class="pick"></span>'
    seen = '<span class="seen"></span>' if watched else ""
    return (f'<div class="d-poster{" picked" if picked else ""}"><div class="d-art" style="{ph(photo)}"><span class="res">1080p</span>{corner}{seen}</div>'
            f'<div class="d-name">{name}</div><div class="d-meta">{meta}</div></div>')

collection_page = f'''
<div class="dp cover cp" aria-hidden="true">
  <div class="d-bar"><span class="d-act"><svg><use href="#i-back"/></svg></span><div class="d-title"></div><span class="d-act"><svg><use href="#i-search"/></svg></span><span class="d-act"><svg><use href="#i-sort"/></svg></span><span class="d-act"><svg><use href="#i-more"/></svg></span></div>
  {pile(PILES["home-videos"], cls="hand")}
  <div class="cp-head"><span>MEDIA</span><b>HOME VIDEOS</b></div>
  <div class="cp-stats"><span><b>8</b>Videos</span><span><b>5</b>Pictures</span><span><b>4m 24s</b>Runtime</span><span><b>28 MB</b>Size</span></div>
  <div class="cp-acts"><span class="cp-play"><svg><use href="#i-play-solid"/></svg>Play all</span><span class="cp-round"><svg><use href="#i-shuffle"/></svg></span><span class="cp-round"><svg><use href="#i-up"/></svg></span></div>
  <div class="cp-tabs"><span class="on">Videos <i>8</i></span><span>Moments <i>8</i></span><span>Images <i>5</i></span></div>
  <div class="d-wall"><div class="d-grid c3">{"".join(clip(p, n, m, watched=(k == 0)) for k, (p, n, m) in enumerate(HOME_VIDEOS[:6]))}</div></div>
  {PILL.format(cls="")}
  {HANDLE}
</div>'''

# ── Main screen · picking videos on Home Videos: the nav pill has become the selection's toolbar ──
PICKED = {3, 4, 5}
selection_page = f'''
<div class="dp cover sel" aria-hidden="true">
  <div class="d-bar"><span class="d-act"><svg><use href="#i-back"/></svg></span><div class="d-title"><b class="sel-title">3 SELECTED</b></div><span class="d-act"><svg><use href="#i-check"/></svg></span><span class="d-act"><svg><use href="#i-x"/></svg></span></div>
  <div class="d-wall"><div class="d-grid c3">{"".join(clip(p, n, m, picked=(k in PICKED)) for k, (p, n, m) in enumerate(HOME_VIDEOS))}</div></div>
  <div class="sel-sum"><b>3 videos · 17 MB</b><span>Rename works on one at a time</span></div>
  <div class="sel-bar"><span class="sel-x"><svg><use href="#i-x"/></svg></span><div class="sel-tools"><span><svg><use href="#i-down"/></svg>Download</span><span><svg><use href="#i-move"/></svg>Move</span><span class="off"><svg><use href="#i-pencil"/></svg>Rename</span><span class="red"><svg><use href="#i-trash"/></svg>Delete</span></div></div>
  {HANDLE}
</div>'''

# ── Main screen · an album, laid out as a mosaic where each picture keeps its own shape, and the
#    same album playing as a story. One trip, so it reads as one set of pictures. ──
ALBUM = [[(1015, 230), (469, 160), (1039, 220), (434, 190)],
         [(1011, 180), (507, 240), (1044, 160), (573, 230)],
         [(575, 210), (1018, 170), (1036, 220), (574, 200)]]
album_page = f'''
<div class="dp cover alb" aria-hidden="true">
  <div class="d-bar"><span class="d-act"><svg><use href="#i-back"/></svg></span><div class="d-title"><b class="alb-title">FJORDS 2024</b></div><span class="d-act"><svg><use href="#i-search"/></svg></span><span class="d-act"><svg><use href="#i-sort"/></svg></span><span class="d-act"><svg><use href="#i-more"/></svg></span></div>
  <div class="alb-cols">{"".join('<div>' + "".join(f'<i style="{ph(p)};height:{h}px">' + ('<span class="alb-poster"><svg><use href="#i-phone"/></svg>POSTER</span>' if (c, n) == (1, 1) else "") + '</i>' for n, (p, h) in enumerate(col)) + '</div>' for c, col in enumerate(ALBUM))}</div>
  {PILL.format(cls="")}
  {HANDLE}
</div>'''
story_page = f'''
<div class="dp cover story" aria-hidden="true">
  <div class="st-pic" style="{ph(1011)}"></div>
  <div class="st-shade"></div>
  <div class="st-segs">{"".join('<i></i>' for _ in range(16))}</div>
  <div class="st-head"><span class="st-av" style="{ph(1015)}"></span><div class="st-t"><b>Fjords 2024</b><span>1 of 16 · 12 Aug 2024</span></div><span class="st-btn"><svg><use href="#i-pause"/></svg></span><span class="st-btn"><svg><use href="#i-x"/></svg></span></div>
  {HANDLE}
</div>'''

# ── Main screen · the player, with Color bleed. The film is a run of slow pans over 16:9 night
#    skies, whose greens and violets light the bars plainly, drawn by the page's script:
#    (photo, [x, y, zoom] at the start, [x, y, zoom] at the end). ──
FILM = [(901, [.45, .5, 1.0], [.55, .5, 1.2]), (1022, [.5, .5, 1.2], [.45, .5, 1.0]),
        (724, [.4, .5, 1.05], [.55, .5, 1.25]), (903, [.5, .55, 1.25], [.5, .45, 1.05]),
        (974, [.5, .5, 1.0], [.5, .55, 1.2])]
color_bleed = f'''
<div class="dp cover cb off" aria-hidden="true" data-shots='{[list(f) for f in FILM]}'>
  <canvas class="cb-glow" width="103" height="224"></canvas>
  <canvas class="cb-pic" width="640" height="360"></canvas>
  <div class="cb-top"><span class="d-act"><svg><use href="#i-back"/></svg></span><div class="d-title"><b>Northern Lights</b><span>Films · 4K</span></div><span class="d-act"><svg><use href="#i-more"/></svg></span></div>
  <div class="cb-state"><i></i><span class="t-off">Color bleed off</span><span class="t-on">Color bleed on</span></div>
  <div class="cb-bottom"><div class="cb-track"><i></i></div><div class="cb-times"><span class="cb-now">1:02:04</span><span>1:48:20</span></div></div>
</div>'''

html = open("site-src/template.html").read()
html = (html.replace("{{CHIP_SCROLL}}", chip_scroll)
            .replace("{{SEARCH_GESTURE}}", search_gesture)
            .replace("{{INNER_HOME}}", inner_home)
            .replace("{{INNER_PINCH}}", inner_pinch)
            .replace("{{COVER_CLOSED}}", cover_closed)
            .replace("{{COLOR_BLEED}}", color_bleed)
            .replace("{{LIBRARY}}", library_wall)
            .replace("{{COLLECTION}}", collection_page)
            .replace("{{SELECTION}}", selection_page)
            .replace("{{ALBUM}}", album_page)
            .replace("{{STORY}}", story_page)
            )
used = set(re.findall(r"images/photos/(\d+)\.jpg", html))
used |= {str(POSTERS[n]) for n in re.findall(r"images/posters/([a-z0-9-]+)\.jpg", html)}
used |= {str(f[0]) for f in FILM}  # the player's film, loaded by the script
used = sorted(used, key=int)
missing = [i for i in used if i not in PHOTOS]
assert not missing, f"no photographer listed in photos.txt for {missing}"
html = html.replace("{{AUTHORS}}", ", ".join(sorted({PHOTOS[i] for i in used}, key=str.lower)))
open(OUT, "w").write(html)
print("wrote", OUT, len(html))
