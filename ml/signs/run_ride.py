"""Run the sign detector over a ride's photos and make contact sheets of every detection.

    python run_ride.py --photos <ride>/work/photos.json --onnx D:/poi-capture-ml/export/signs_nano_640.onnx \
        --out D:/poi-capture-ml/eval/truongsa_nano [--tiles] [--conf 0.3]

Reads photos.json (list of {"file": <jpg>, ...}) read-only. Writes <out>/detections.json and
<out>/sheet_NN.jpg (each tile = the box with context, label = #n photo-idx code group score).
"""
import argparse
import json
import os
import time

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

import signs_lib as L

TILE, COLS, ROWS = 260, 6, 5


def font(sz):
    for f in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(f, sz)
        except OSError:
            pass
    return ImageFont.load_default()


def crop_tile(img, box, label):
    h, w = img.shape[:2]
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    side = max(x1 - x0, y1 - y0) * 3.0
    side = max(side, 200)
    a0, b0 = int(max(0, cx - side / 2)), int(max(0, cy - side / 2))
    a1, b1 = int(min(w, cx + side / 2)), int(min(h, cy + side / 2))
    c = img[b0:b1, a0:a1].copy()
    cv2.rectangle(c, (int(x0 - a0), int(y0 - b0)), (int(x1 - a0), int(y1 - b0)), (0, 0, 255), max(2, int(side / 150)))
    s = (TILE - 34) / max(c.shape[:2])
    c = cv2.resize(c, (max(1, int(c.shape[1] * s)), max(1, int(c.shape[0] * s))))
    tile = Image.new("RGB", (TILE, TILE), (30, 30, 30))
    tile.paste(Image.fromarray(c[:, :, ::-1]), ((TILE - c.shape[1]) // 2, 34))
    d = ImageDraw.Draw(tile)
    d.text((4, 2), label[0], fill=(255, 255, 0), font=font(15))
    d.text((4, 18), label[1], fill=(200, 200, 200), font=font(12))
    return tile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--photos", required=True)
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--conf", type=float, default=0.3)
    ap.add_argument("--tiles", action="store_true")
    a = ap.parse_args()
    classes = L.load_classes()
    det = L.OnnxDetector(a.onnx)
    photos = json.load(open(a.photos, encoding="utf-8"))
    os.makedirs(a.out, exist_ok=True)
    allr, tiles, t0 = [], [], time.perf_counter()
    for p in photos:
        img = cv2.imdecode(np.fromfile(p["file"], np.uint8), cv2.IMREAD_COLOR)
        d = L.detect(det, img, conf=a.conf, tiles=a.tiles)
        rec = {"idx": p.get("idx"), "id": p.get("id"), "file": p["file"], "detections": []}
        for x0, y0, x1, y1, s, c in d:
            k = classes[int(c)]
            n = len(tiles) + 1
            rec["detections"].append({"n": n, "box": [round(float(v)) for v in (x0, y0, x1, y1)],
                                      "score": round(float(s), 3), "class_id": int(c), "code": k["code"],
                                      "group": k["group"], "en": k["en"]})
            tiles.append(crop_tile(img, (x0, y0, x1, y1),
                                   (f"#{n} p{p.get('idx')} {k['code']} {s:.2f}", f"{k['group']}: {k['en']}"[:40])))
        allr.append(rec)
    dt = time.perf_counter() - t0
    n_ph = sum(1 for r in allr if r["detections"])
    print(f"{len(photos)} photos, {len(tiles)} detections on {n_ph} photos, {dt / len(photos) * 1000:.0f} ms/photo")
    json.dump({"conf": a.conf, "tiles": a.tiles, "onnx": a.onnx, "photos": allr},
              open(os.path.join(a.out, "detections.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    per = COLS * ROWS
    for i in range(0, len(tiles), per):
        part = tiles[i:i + per]
        sheet = Image.new("RGB", (COLS * TILE, ((len(part) - 1) // COLS + 1) * TILE), (0, 0, 0))
        for j, t in enumerate(part):
            sheet.paste(t, ((j % COLS) * TILE, (j // COLS) * TILE))
        sheet.save(os.path.join(a.out, f"sheet_{i // per + 1:02d}.jpg"), quality=88)


if __name__ == "__main__":
    main()
