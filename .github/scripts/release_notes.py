#!/usr/bin/env python3
"""Work out the next version and write the release notes for it.

Why this exists rather than semantic-release or release-please: every one of
those reads Conventional Commits (`feat:`, `fix:`), and this project writes
commit subjects as prose on purpose — CLAUDE.md asks for a message that
explains the why, and "Move, rename and delete whole folders" says more than
"feat(fileops): folder operations" ever would. So the machine-readable part
lives in git TRAILERS at the foot of the message, beside the
`Co-Authored-By:` that is already there, and the prose is left alone.

Two trailers, both optional:

    Release: major | minor | patch | skip
        How much of a version bump this commit earns, and which section of
        the notes it lands in. Absent means `patch` for a code change and
        `skip` for a change that only touches docs or design files.

    Notes: one line, in a user's words, about what is different
        Continuation lines may be indented. Absent means the subject line is
        used, which is usually fine — the subjects here are written for a
        human already.

Run it locally to see exactly what the next release would say:

    .github/scripts/release_notes.py --repo Jmeza081/regolith
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys

# A bump's rank, so a range of commits takes the largest one in it.
RANK = {"skip": 0, "patch": 1, "minor": 2, "major": 3}

# Which heading each bump writes under. "Under the hood" is the honest home
# for everything that changed without changing anything you can see.
SECTIONS = [
    ("major", "Breaking changes"),
    ("minor", "New"),
    ("patch", "Fixed and improved"),
    ("skip", "Under the hood"),
]

# A commit touching only these is documentation, not a build: it earns no
# bump on its own, and is still listed so the notes account for every commit.
DOC_PREFIXES = ("docs/", "design/", ".github/")
DOC_SUFFIXES = (".md",)


def run(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()


def latest_tag() -> str | None:
    """The newest `vX.Y.Z` reachable from HEAD, or None on a fresh repo."""
    try:
        return run("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
    except subprocess.CalledProcessError:
        return None


def commits_in(rev_range: str) -> list[str]:
    out = run("git", "log", "--no-merges", "--format=%H", rev_range)
    return out.splitlines() if out else []


def trailers_of(sha: str) -> dict[str, str]:
    """The commit's trailer block, folded and lower-cased by key.

    `git interpret-trailers --parse` is what decides where the trailer block
    starts, so a colon inside the prose body is never mistaken for one.
    """
    message = run("git", "log", "-1", "--format=%B", sha)
    parsed = subprocess.run(
        ["git", "interpret-trailers", "--parse"],
        input=message, capture_output=True, text=True, check=True,
    ).stdout
    out: dict[str, str] = {}
    for line in parsed.splitlines():
        if ":" in line:
            key, _, value = line.partition(":")
            out[key.strip().lower()] = value.strip()
    return out


def touches_only_docs(sha: str) -> bool:
    files = run("git", "show", "--name-only", "--format=", sha).splitlines()
    files = [f for f in files if f]
    if not files:
        return True
    return all(
        f.startswith(DOC_PREFIXES) or f.endswith(DOC_SUFFIXES) for f in files
    )


def describe(sha: str) -> tuple[str, str]:
    """This commit's (bump, note line)."""
    trailers = trailers_of(sha)
    subject = run("git", "log", "-1", "--format=%s", sha)
    declared = trailers.get("release", "").lower()
    if declared in RANK:
        bump = declared
    else:
        bump = "skip" if touches_only_docs(sha) else "patch"
    return bump, trailers.get("notes") or subject


def next_version(previous: str | None, bump: str) -> str:
    major, minor, patch = (0, 1, 0) if previous is None else tuple(
        int(p) for p in previous.lstrip("v").split(".")[:3]
    )
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def version_code(version: str) -> int:
    """A single increasing integer Android will accept, readable as the version.

    0.2.0 -> 200, 1.4.12 -> 10412. Android refuses to install an APK whose
    versionCode is not greater than the installed one, so this has to rise
    every release — deriving it from the semver means there is no second
    number anyone has to remember to bump, and reading it backwards tells you
    which release a phone is on. Holds while minor and patch stay under 100.
    """
    major, minor, patch = (int(p) for p in version.split("."))
    return major * 10_000 + minor * 100 + patch


def render(version: str, previous: str | None, entries: list[tuple[str, str, str]], repo: str) -> str:
    lines = [f"Regolith **{version}** — install `regolith-{version}.apk` below.", ""]
    for bump, heading in SECTIONS:
        mine = [(note, sha) for kind, note, sha in entries if kind == bump]
        if not mine:
            continue
        lines.append(f"## {heading}")
        if bump == "skip":
            lines.append("_Changed, but nothing you can see from the app._")
        lines.append("")
        for note, sha in mine:
            lines.append(f"- {note} ({sha[:7]})")
        lines.append("")
    if not entries:
        lines += ["## Under the hood", "", "- No commits since the last release.", ""]
    lines += [
        "---",
        "",
        "Installs over your existing copy — the library, chapters and resume points are kept.",
        "It is signed with the repository's own key, which is the same key every build here uses.",
        "",
    ]
    if previous:
        lines.append(f"**Every commit:** https://github.com/{repo}/compare/{previous}...v{version}")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "Jmeza081/regolith"))
    ap.add_argument("--force-bump", default="auto", choices=["auto", "patch", "minor", "major"])
    ap.add_argument("--notes-out", help="write the notes here instead of stdout")
    ap.add_argument("--github-output", help="append version/code/bump to this file (CI)")
    args = ap.parse_args()

    previous = latest_tag()
    rev_range = f"{previous}..HEAD" if previous else "HEAD"
    entries = []
    for sha in commits_in(rev_range):
        bump, note = describe(sha)
        entries.append((bump, note, sha))

    highest = max((RANK[b] for b, _, _ in entries), default=0)
    bump = next(k for k, v in RANK.items() if v == highest)
    if args.force_bump != "auto":
        bump = args.force_bump
    # A range of nothing but documentation is still worth a release when it is
    # asked for by hand; on its own it publishes nothing.
    effective = "patch" if bump == "skip" else bump
    version = next_version(previous, effective)
    notes = render(version, previous, entries, args.repo)

    if args.notes_out:
        with open(args.notes_out, "w") as f:
            f.write(notes)
    else:
        print(notes)
    if args.github_output:
        with open(args.github_output, "a") as f:
            f.write(f"version={version}\n")
            f.write(f"code={version_code(version)}\n")
            f.write(f"bump={bump}\n")
            f.write(f"previous={previous or ''}\n")
    else:
        print(f"\n--- version={version} code={version_code(version)} bump={bump}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
