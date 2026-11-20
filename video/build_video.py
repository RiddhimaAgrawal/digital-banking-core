#!/usr/bin/env python3
"""Builds a captioned slide video (no voice) from presentation/Digital_Banking_Core.pptx + video/narration.json.
Usage: python3 video/build_video.py   (needs LibreOffice, pdftoppm, ffmpeg, Pillow)
The captions are paced at ~155 words per minute, so you can also use the video as a backing track
and read the narration aloud while recording (see docs/VIDEO_SCRIPT.md)."""
import json, re, subprocess, sys, tempfile, shutil
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
PPTX = ROOT / "presentation" / "Digital_Banking_Core.pptx"
OUT = ROOT / "video" / "Digital_Banking_Core_explainer.mp4"
NARR = json.loads((ROOT / "video" / "narration.json").read_text())
W, H = 1920, 1080
SW, SH = 1536, 864
FONT = ImageFont.truetype("/usr/share/fonts/truetype/crosextra/Carlito-Regular.ttf", 36)
BG, FG = (16, 32, 31), (255, 255, 255)
WPS = 155 / 60.0  # words per second

def wrap(draw, text, width):
    lines, cur = [], ""
    for word in text.split():
        t = (cur + " " + word).strip()
        if draw.textlength(t, font=FONT) <= width:
            cur = t
        else:
            lines.append(cur); cur = word
    lines.append(cur)
    return lines

def segments(draw, paragraphs, width, max_lines=3):
    """Group sentences into caption segments that fit in max_lines lines."""
    sentences = []
    for p in paragraphs:
        sentences += [s for s in re.split(r"(?<=[.!?])\s+", p.strip()) if s]
    segs, cur = [], ""
    for s in sentences:
        trial = (cur + " " + s).strip()
        if len(wrap(draw, trial, width)) <= max_lines:
            cur = trial
        else:
            if cur: segs.append(cur)
            if len(wrap(draw, s, width)) <= max_lines:
                cur = s
            else:  # one long sentence: split at commas
                parts, buf = re.split(r"(?<=,)\s+", s), ""
                for part in parts:
                    t = (buf + " " + part).strip()
                    if len(wrap(draw, t, width)) <= max_lines: buf = t
                    else: segs.append(buf); buf = part
                cur = buf
    if cur: segs.append(cur)
    return segs

def main():
    tmp = Path(tempfile.mkdtemp(prefix="bankvideo_"))
    try:
        subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", str(tmp), str(PPTX)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        pdf = tmp / (PPTX.stem + ".pdf")
        subprocess.run(["pdftoppm", "-png", "-scale-to-x", str(SW), "-scale-to-y", str(SH), str(pdf), str(tmp / "s")], check=True)
        slides = sorted(tmp.glob("s-*.png"))
        assert len(slides) == len(NARR), f"{len(slides)} slides but {len(NARR)} narration blocks"
        probe = ImageDraw.Draw(Image.new("RGB", (10, 10)))
        frames = []  # (png path, seconds)
        n = 0
        for slide_png, block in zip(slides, NARR):
            base = Image.new("RGB", (W, H), BG)
            base.paste(Image.open(slide_png).convert("RGB"), ((W - SW) // 2, 0))
            for seg in segments(probe, block["text"], SW):
                img = base.copy(); d = ImageDraw.Draw(img)
                y = SH + 28
                for line in wrap(d, seg, SW):
                    d.text(((W - SW) // 2, y), line, font=FONT, fill=FG); y += 50
                p = tmp / f"f{n:03d}.png"; img.save(p); n += 1
                frames.append((p, max(3.0, len(seg.split()) / WPS + 0.8)))
        lst = tmp / "list.txt"
        with lst.open("w") as f:
            for p, secs in frames:
                f.write(f"file '{p}'\nduration {secs:.2f}\n")
            f.write(f"file '{frames[-1][0]}'\n")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(lst),
                        "-vf", "fps=10,format=yuv420p", "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", str(OUT)], check=True)
        total = sum(s for _, s in frames)
        print(f"wrote {OUT}  ({len(frames)} caption frames, {total/60:.1f} min)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

if __name__ == "__main__":
    sys.exit(main())
