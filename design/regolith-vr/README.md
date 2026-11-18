# Regolith-VR — design canvas sources

The canvas lives at <https://claude.ai/artifact/1cfHmNJ7p4jn9vVPeVTh78>
(25 boards: foundations, first run, library, watch, more). The PRD it
draws is `docs/vr/PRD.md`.

- `frags/*.html` — one board each: just the markup. The first line names the
  board and its size. `[[name]]` is an icon; `<!--dock:tab-->`,
  `<!--bar:key-->` and `<!--t:art|title|meta-->` are shared pieces.
- `build.py` — wraps each fragment in the shared head and CSS (the Regolith
  tokens from `app/.../ui/theme/`, resized for a headset) and writes
  `<name>.dc.html`. Web analogy: a tiny static-site generator, so the CSS
  and the Dock exist once instead of 25 times.
- `canvas.json` — where each board sits on the canvas.

Rebuild: `python3 design/regolith-vr/build.py design/regolith-vr/frags <out>/project`
