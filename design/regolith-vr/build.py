#!/usr/bin/env python3
"""Assemble Regolith-VR artboards: shared head + CSS + one body fragment each.

Usage: build.py <fragments_dir> <out_project_dir>
A fragment is `<stem>.html`; its first line is `<!-- title: ... | w,h -->`.
`[[name]]` in a fragment becomes an inline stroke icon.
"""
import json, pathlib, re, sys

ICONS = {
    "home": "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z",
    "grid": "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z",
    "folder": "M3 6a2 2 0 0 1 2-2h4l2 3h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    "shorts": "M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM10.5 9.5v5l4-2.5z",
    "settings": "M12 2.5 20 7v10l-8 4.5L4 17V7zM12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z",
    "search": "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4",
    "play": "M7 4.5v15l12-7.5z",
    "pause": "M8 5v14M16 5v14",
    "rew": "M11 6l-6 6 6 6M19 6l-6 6 6 6",
    "fwd": "M13 6l6 6-6 6M5 6l6 6-6 6",
    "left": "M15 5l-7 7 7 7",
    "right": "M9 5l7 7-7 7",
    "down": "M5 9l7 7 7-7",
    "up": "M5 15l7-7 7 7",
    "x": "M5 5l14 14M19 5 5 19",
    "check": "M4 12.5l5 5L20 6.5",
    "bookmark": "M6 3h12v18l-6-4.5L6 21z",
    "layers": "M12 3 21 8l-9 5-9-5zM3 12.5l9 5 9-5M3 17l9 5 9-5",
    "mic": "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3",
    "download": "M12 3v12M6.5 10 12 15.5 17.5 10M4 20h16",
    "more": "M5 12h.01M12 12h.01M19 12h.01",
    "server": "M4 5h16v6H4zM4 13h16v6H4zM8 8h.01M8 16h.01",
    "phone": "M8 2.5h8A1.5 1.5 0 0 1 17.500 4v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.500 20V4A1.5 1.5 0 0 1 8 2.5zM11 18.5h2",
    "eye": "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
    "size": "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5",
    "sun": "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.500 1.500M17.500 17.500 19 19M5 19l1.500-1.500M17.500 6.500 19 5",
    "list": "M4 6h16M4 12h16M4 18h10",
    "loop": "M4 12a8 8 0 0 1 14-5l2 2M20 5v4h-4M20 12a8 8 0 0 1-14 5l-2-2M4 19v-4h4",
    "lock": "M6 11h12v9H6zM8.500 11V7.500a3.500 3.500 0 0 1 7 0V11",
    "trash": "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13",
    "pencil": "M4 20l1-4L16 5l3 3L8 19z",
    "move": "M12 3v18M3 12h18M12 3l-3 3M12 3l3 3M12 21l-3-3M12 21l3-3M3 12l3-3M3 12l3 3M21 12l-3-3M21 12l-3 3",
    "image": "M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M9 9h.01",
    "volume": "M4 9h4l5-4v14l-5-4H4zM17 9a4 4 0 0 1 0 6",
    "shuffle": "M3 7h3l10 10h4M3 17h3l3-3M13 10l3-3h4M18 4l3 3-3 3M18 14l3 3-3 3",
    "pin": "M12 21v-6M7 15h10l-1.500-4V5h-7v6z",
    "bed": "M3 18v-7h12a4 4 0 0 1 4 4v3M3 14h16M3 18v2M19 18v2M7 11V9",
    "hand": "M8 12V5.500a1.500 1.500 0 0 1 3 0V11M11 11V4a1.500 1.500 0 0 1 3 0v7M14 11V5.500a1.500 1.500 0 0 1 3 0V14a7 7 0 0 1-7 7c-3 0-4.500-2-6-5l-1.500-3a1.400 1.400 0 0 1 2.500-1.200L8 14",
    "headset": "M3 9h18v6a2 2 0 0 1-2 2h-3l-2-2.500h-4L8 17H5a2 2 0 0 1-2-2z",
    "alert": "M12 4 2.500 20h19zM12 10v4M12 17h.01",
    "plus": "M12 5v14M5 12h14",
    "minus": "M5 12h14",
    "keyboard": "M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M8 14h8",
    "refresh": "M20 12a8 8 0 1 1-2.500-5.800M20 4v5h-5",
    "curve": "M3 8c6 4 12 4 18 0M3 8v9c6 4 12 4 18 0V8",
    "cc": "M3 6h18v12H3zM10 10.500a2 2 0 1 0 0 3M17 10.500a2 2 0 1 0 0 3",
}

FILLED = {"play"}

CSS = r"""
body{margin:0}
.fov{width:1600px;height:900px;position:relative;overflow:hidden;background:#050505;font-family:'Space Grotesk',system-ui,sans-serif;color:#fff;box-sizing:border-box;perspective:1700px}
.fov *{box-sizing:border-box}
.fov button{font:inherit;color:inherit;cursor:pointer;margin:0}
.fov a{color:inherit;text-decoration:none}
.floor{position:absolute;left:-300px;right:-300px;bottom:-470px;height:680px;border-radius:50%;background:radial-gradient(closest-side,#131313,#050505 72%)}
.room{position:absolute;inset:0;background:linear-gradient(#0c0d0e,#141516 62%,#0a0a0a 62%,#111 100%)}
.p{position:absolute;background:#0F0F0F;border:1px solid #1F1F1F;border-radius:28px;box-shadow:0 30px 80px rgba(0,0,0,.6)}
.p.lift{background:#1C1C1C;border-color:#3A3A3A}
.p.glass{background:rgba(0,0,0,.52);border-color:rgba(255,255,255,.16)}
.tl{transform:rotateY(22deg);transform-origin:right center}
.tr{transform:rotateY(-22deg);transform-origin:left center}
.tu{transform:rotateX(-18deg);transform-origin:center top}
.h1{font:400 26px/1.3 Michroma,sans-serif;text-transform:uppercase;margin:0;-webkit-text-stroke:.5px #fff}
.h2{font:400 20px/1.35 Michroma,sans-serif;text-transform:uppercase;margin:0}
.eb{font:600 14px/1 'Space Grotesk',sans-serif;letter-spacing:.14em;text-transform:uppercase;color:#A0A0A0;margin:0}
.t{font:600 19px/1.3 'Space Grotesk',sans-serif;margin:0}
.b{font:400 17px/1.5 'Space Grotesk',sans-serif;color:#A0A0A0;margin:0}
.m{font:400 15px/1.4 'Space Grotesk',sans-serif;color:#8A8A8A;margin:0}
.fig{font:400 44px/1 Michroma,sans-serif;margin:0}
.btn{height:64px;padding:0 30px;border-radius:999px;border:1px solid transparent;display:inline-flex;align-items:center;justify-content:center;gap:12px;font:700 18px/1 'Space Grotesk',sans-serif;white-space:nowrap}
.btn.red{background:#E11B17;color:#fff}
.btn.frost{background:rgba(255,255,255,.06);border-color:rgba(255,255,255,.18);font-weight:600}
.btn.sm{height:52px;padding:0 22px;font-size:16px}
.btn.off{background:#161616;color:#8A8A8A}
.chip{height:48px;padding:0 20px;border-radius:999px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.18);display:inline-flex;align-items:center;gap:8px;font:600 15px/1 'Space Grotesk',sans-serif;color:#EDEDED;white-space:nowrap}
.chip.on{background:#fff;color:#0A0A0A;border-color:#fff;font-weight:700}
.chip.art{background:rgba(0,0,0,.72);border-color:transparent;height:34px;padding:0 12px;font-size:13px}
.ic{width:60px;height:60px;border-radius:50%;background:rgba(0,0,0,.42);border:1px solid rgba(255,255,255,.34);display:inline-flex;align-items:center;justify-content:center;flex:none;padding:0}
.ic.big{width:84px;height:84px;background:#fff;color:#0A0A0A;border-color:#fff}
.ic.flat{background:rgba(255,255,255,.06);border-color:rgba(255,255,255,.18)}
.i{width:26px;height:26px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;flex:none}
.i.f{fill:currentColor;stroke:none}
.i.s{width:20px;height:20px}
.i.l{width:36px;height:36px}
.dock{position:absolute;display:flex;gap:4px;padding:8px;border-radius:999px;background:rgba(0,0,0,.52);border:1px solid rgba(255,255,255,.16);box-shadow:0 20px 50px rgba(0,0,0,.6)}
.dk{width:104px;height:76px;border-radius:999px;border:0;background:none;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;font:600 12px/1 'Space Grotesk',sans-serif;letter-spacing:.06em;text-transform:uppercase;color:#8A8A8A;position:relative}
.dk.on{color:#fff;background:rgba(255,255,255,.08)}
.dot{position:absolute;top:12px;right:30px;width:8px;height:8px;border-radius:50%;background:#3DDC84}
.art{border-radius:16px;position:relative;overflow:hidden;background:#141414;flex:none}
.a1{background:radial-gradient(circle at 28% 30%,#d9a066 0,transparent 42%),radial-gradient(circle at 72% 68%,#7a2f3a 0,transparent 52%),#1c1412}
.a2{background:radial-gradient(circle at 65% 25%,#6fa3c7 0,transparent 46%),radial-gradient(circle at 30% 75%,#27445c 0,transparent 55%),#0e161d}
.a3{background:radial-gradient(circle at 40% 60%,#e0b04a 0,transparent 38%),radial-gradient(circle at 75% 30%,#a8552c 0,transparent 48%),#19130c}
.a4{background:radial-gradient(circle at 30% 35%,#5c8f6b 0,transparent 44%),radial-gradient(circle at 70% 70%,#1f3d33 0,transparent 55%),#0d1512}
.a5{background:radial-gradient(circle at 60% 40%,#b58bc2 0,transparent 40%),radial-gradient(circle at 25% 75%,#3c2a55 0,transparent 55%),#14101b}
.a6{background:radial-gradient(circle at 50% 30%,#c9c9c9 0,transparent 36%),radial-gradient(circle at 50% 80%,#3d3d3d 0,transparent 60%),#111}
.a7{background:radial-gradient(circle at 25% 60%,#d86a4a 0,transparent 40%),radial-gradient(circle at 75% 35%,#f0c27a 0,transparent 36%),#1a100d}
.a8{background:radial-gradient(circle at 70% 60%,#3f6fb0 0,transparent 46%),radial-gradient(circle at 25% 25%,#9cc4e4 0,transparent 34%),#0c1220}
.bar{position:absolute;left:0;right:0;bottom:0;height:5px;background:rgba(255,255,255,.25)}
.bar>i{display:block;height:100%;background:#E11B17}
.hov{outline:2px solid #fff;outline-offset:4px;transform:scale(1.04)}
.cur{position:absolute;width:16px;height:16px;border-radius:50%;background:#fff;box-shadow:0 0 0 5px rgba(255,255,255,.22);z-index:9}
.ray{position:absolute;height:2px;background:linear-gradient(90deg,rgba(255,255,255,0),rgba(255,255,255,.55));transform-origin:left center;z-index:8}
.seg{display:inline-flex;padding:5px;border-radius:999px;background:#141414;border:1px solid #1F1F1F;gap:2px}
.sg{height:48px;padding:0 22px;border-radius:999px;border:0;background:none;font:600 15px/1 'Space Grotesk',sans-serif;color:#8A8A8A;display:inline-flex;align-items:center;gap:8px}
.sg.on{background:#2E2E2E;color:#fff}
.row{display:flex;align-items:center;gap:16px;min-height:72px;padding:10px 18px;border-radius:18px;border:0;background:none;width:100%;text-align:left}
.row.sel{background:rgba(255,255,255,.06)}
.box{width:52px;height:52px;border-radius:12px;background:#1A1A1A;display:flex;align-items:center;justify-content:center;flex:none;color:#EDEDED}
.sw{width:64px;height:38px;border-radius:999px;background:#2E2E2E;position:relative;flex:none;border:0;padding:0}
.sw::after{content:"";position:absolute;top:5px;left:5px;width:28px;height:28px;border-radius:50%;background:#A0A0A0}
.sw.on{background:#fff}
.sw.on::after{left:31px;background:#0A0A0A}
.sl{height:8px;border-radius:99px;background:rgba(255,255,255,.22);position:relative;flex:1}
.sl>i{position:absolute;left:0;top:0;bottom:0;border-radius:99px;background:#fff}
.sl>b{position:absolute;top:-12px;width:32px;height:32px;margin-left:-16px;border-radius:50%;background:#fff;box-shadow:0 2px 10px rgba(0,0,0,.6)}
.sl.red>i{background:#E11B17}
.hr{height:1px;background:#1F1F1F;border:0;margin:0}
.scr{position:absolute;border-radius:8px;overflow:hidden;box-shadow:0 40px 120px rgba(0,0,0,.7)}
.glow{position:absolute;border-radius:50%;filter:blur(70px);opacity:.5}
.tag{position:absolute;font:600 13px/1 'Space Grotesk',sans-serif;letter-spacing:.1em;text-transform:uppercase;color:#8A8A8A;display:flex;align-items:center;gap:8px}
.tag::before{content:"";width:18px;height:1px;background:#3A3A3A}
.fld{height:72px;border-radius:18px;background:#141414;border:1px solid #2E2E2E;padding:12px 20px;display:flex;flex-direction:column;justify-content:center;gap:8px}
.fld.on{border-color:#fff}
.fl{font:600 12px/1 'Space Grotesk',sans-serif;letter-spacing:.12em;text-transform:uppercase;color:#8A8A8A}
.fx{display:flex}.col{display:flex;flex-direction:column}.ac{align-items:center}.jb{justify-content:space-between}.jc{justify-content:center}.wrap{flex-wrap:wrap}
.g4{gap:4px}.g8{gap:8px}.g12{gap:12px}.g18{gap:18px}.g30{gap:30px}.g40{gap:40px}
.grow{flex:1;min-width:0}
.ink{color:#fff}.soft{color:#EDEDED}.red-t{color:#FF6B66}
.tile{display:flex;flex-direction:column;gap:8px;background:none;border:0;padding:0;text-align:left;min-width:0;position:relative}
.tile .art{width:100%;aspect-ratio:2/3}
.tile .t{font-size:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#fff}
.tile .m{font-size:14px}
.sk{background:#141414;border-radius:16px}
.ck{position:absolute;top:10px;right:10px;width:30px;height:30px;border-radius:50%;background:#fff;color:#0A0A0A;display:flex;align-items:center;justify-content:center}
.dim{opacity:.38}
.sub{position:absolute;left:0;right:0;bottom:7%;text-align:center;font:500 22px/1.3 'Space Grotesk',sans-serif;color:#fff;text-shadow:0 2px 6px #000}
.kbd{min-width:40px;height:40px;padding:0 10px;border-radius:10px;border:1px solid #3A3A3A;background:#1C1C1C;display:inline-flex;align-items:center;justify-content:center;font:600 14px/1 'Space Grotesk',sans-serif;color:#EDEDED}
.dash{border:1px dashed #3A3A3A;background:none}
"""

HEAD = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>%(title)s</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Michroma&family=Space+Grotesk:wght@400;500;600;700&display=swap" rel="stylesheet">
<style>%(css)s</style>
</helmet>
%(body)s
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":%(w)d,"height":%(h)d}}'>
class Component extends DCLogic {
renderVals() {
return {};
}
}
</script>
</body>
</html>
"""



TABS = [("home","Home"),("grid","Library"),("folder","Browse"),("shorts","Shorts"),("settings","Settings")]

def dock(m):
    on = m.group(1)
    out = ['<nav class="dock" style="left:524px;top:770px" aria-label="Main">']
    for ic, label in TABS:
        act = label.lower() == on
        out.append('<button class="dk%s">[[%s]]%s%s</button>' % (" on" if act else "", ic, '<span class="dot"></span>' if act else "", label))
    out.append("</nav>")
    return "".join(out)

def tile(m):
    art, title, meta = (m.group(1).split("|") + ["", ""])[:3]
    return '<button class="tile"><span class="art %s"></span><span class="t">%s</span><span class="m">%s</span></button>' % (art, title, meta)


def bar(m):
    on = m.group(1)
    def k(key, ic, label, href=None):
        cls = "dk on" if key == on else "dk"
        tag = "a" if href else "button"
        h = ' href="%s"' % href if href else ""
        return '<%s class="%s" style="width:84px;border-radius:22px"%s>[[%s]]%s</%s>' % (tag, cls, h, ic, label, tag)
    ticks = "".join('<span style="position:absolute;left:%d%%;top:-3px;width:2px;height:14px;background:#0A0A0A"></span>' % x for x in (14, 31, 47, 62, 78, 90))
    return (
        '<div class="p glass" style="left:300px;top:630px;width:860px;height:236px;padding:22px 28px;border-radius:36px"><div class="col g12">'
        '<div class="fx jb ac"><div class="col g4"><p class="t">Glasshouse</p><p class="m">The greenhouse · chapter 2 of 7</p></div>'
        '<p class="t">48:12 <span style="color:#8A8A8A">/ 2:04:00</span></p></div>'
        '<div class="sl red" style="flex:none;margin:8px 0"><i style="width:39%"></i>' + ticks + '<b style="left:39%"></b></div>'
        '<div class="fx jb ac"><div class="fx g4">' + k("chapters", "list", "Chapters", "D5_Chapters.dc.html") + k("mark", "bookmark", "Mark", "D5_Chapters.dc.html") + k("subs", "cc", "Subs") + '</div>'
        '<div class="fx ac g12"><button class="ic" aria-label="Back ten seconds">[[rew]]</button><button class="ic big" aria-label="Pause">[[pause.l]]</button><button class="ic" aria-label="Forward ten seconds">[[fwd]]</button></div>'
        '<div class="fx g4">' + k("relief", "layers", "Relief", "D3_Relief.dc.html") + k("space", "size", "Screen", "D4_Space.dc.html") + k("speed", "refresh", "1× speed") + '</div></div>'
        '</div></div>'
    )

def icon(m):
    parts = m.group(1).split(".")
    name, mods = parts[0], parts[1:]
    cls = "i" + (" f" if name in FILLED else "") + "".join(" " + x for x in mods)
    return '<svg class="%s" viewBox="0 0 24 24" aria-hidden="true"><path d="%s"></path></svg>' % (cls, ICONS[name])


def main(src, out):
    src, out = pathlib.Path(src), pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    css = re.sub(r"\n+", "", CSS.strip())
    done = []
    for f in sorted(src.glob("*.html")):
        text = f.read_text()
        first, body = text.split("\n", 1)
        m = re.match(r"<!-- title: (.*?) \| (\d+),(\d+) -->", first)
        title, w, h = m.group(1), int(m.group(2)), int(m.group(3))
        body = re.sub(r"<!--dock:(\w+)-->", dock, body.strip())
        body = re.sub(r"<!--bar:(\w+)-->", bar, body)
        body = re.sub(r"<!--t:(.*?)-->", tile, body)
        body = re.sub(r"\[\[([a-z0-9.]+)\]\]", icon, body)
        (out / (f.stem + ".dc.html")).write_text(HEAD % dict(title=title, css=css, body=body, w=w, h=h))
        done.append((f.stem, title, w, h))
    print(json.dumps(done))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
