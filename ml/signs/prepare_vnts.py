"""Convert the VNTS dataset (YOLO txt labels) into the COCO layout YOLOX reads.

    python prepare_vnts.py --raw D:/poi-capture-ml/data/vnts_raw/archive --out D:/poi-capture-ml/data/vnts_coco_blocked

Output:
    <out>/train2017/*.jpg, <out>/val2017/*.jpg   (hard links to the raw images, no copy)
    <out>/annotations/instances_train2017.json, instances_val2017.json
Split (--split):
    blocked (default): VNTS images are consecutive video frames (0001, 0002... are near-duplicates), so the
        dataset's own random split leaks: almost every val frame has a twin in train. "blocked" holds out
        whole runs of 100 consecutive image numbers (every 6th run, ~17 %) and drops train images closer
        than 20 numbers to a held-out run.
    dataset: the author's split_dataset/test_files.txt -> val, the rest -> train (optimistic metrics).
classes.csv (next to this script) is the class list the model is trained on (same order as the
dataset's classes.txt) with the QCVN 41:2019 code and sign group.
"""
import argparse
import csv
import json
import os
import shutil

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))


def load_classes():
    with open(os.path.join(HERE, "classes.csv"), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def link(src, dst):
    if os.path.exists(dst):
        return
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True, help="folder with images/, labels/, split_dataset/")
    ap.add_argument("--out", required=True)
    ap.add_argument("--split", choices=["blocked", "dataset"], default="blocked")
    a = ap.parse_args()

    classes = load_classes()
    images = sorted(f for f in os.listdir(os.path.join(a.raw, "images")) if f.lower().endswith(".jpg"))
    if a.split == "dataset":
        val_names = set(open(os.path.join(a.raw, "split_dataset", "test_files.txt")).read().split())
        dropped = set()
    else:
        num = {f: int(os.path.splitext(f)[0]) for f in images}
        val_names = {f for f, n in num.items() if (n - 1) // 100 % 6 == 2}
        val_nums = sorted(num[f] for f in val_names)
        import bisect

        def near_val(n, gap=20):
            i = bisect.bisect_left(val_nums, n)
            return any(0 <= j < len(val_nums) and abs(val_nums[j] - n) < gap for j in (i - 1, i))

        dropped = {f for f, n in num.items() if f not in val_names and near_val(n)}
        print("blocked split:", len(val_names), "val,", len(dropped), "train images dropped as guard band")
    images = [f for f in images if f not in dropped]
    cats = [{"id": int(c["id"]), "name": c["code"], "supercategory": c["group"]} for c in classes]
    out = {s: {"images": [], "annotations": [], "categories": cats} for s in ("train2017", "val2017")}
    ann_id = 1
    for img_id, name in enumerate(images, 1):
        split = "val2017" if name in val_names else "train2017"
        os.makedirs(os.path.join(a.out, split), exist_ok=True)
        src = os.path.join(a.raw, "images", name)
        link(src, os.path.join(a.out, split, name))
        w, h = Image.open(src).size
        out[split]["images"].append({"id": img_id, "file_name": name, "width": w, "height": h})
        lab = os.path.join(a.raw, "labels", name[:-4] + ".txt")
        if not os.path.exists(lab):
            continue
        for line in open(lab):
            p = line.split()
            if len(p) < 5:
                continue
            c, cx, cy, bw, bh = int(p[0]), *map(float, p[1:5])
            x0, y0 = max(0.0, (cx - bw / 2) * w), max(0.0, (cy - bh / 2) * h)
            x1, y1 = min(w, (cx + bw / 2) * w), min(h, (cy + bh / 2) * h)
            if x1 - x0 < 1 or y1 - y0 < 1:
                continue
            out[split]["annotations"].append({
                "id": ann_id, "image_id": img_id, "category_id": c, "iscrowd": 0,
                "bbox": [round(x0, 2), round(y0, 2), round(x1 - x0, 2), round(y1 - y0, 2)],
                "area": round((x1 - x0) * (y1 - y0), 2),
            })
            ann_id += 1
    os.makedirs(os.path.join(a.out, "annotations"), exist_ok=True)
    for s, d in out.items():
        with open(os.path.join(a.out, "annotations", f"instances_{s}.json"), "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False)
        print(s, len(d["images"]), "images", len(d["annotations"]), "boxes")


if __name__ == "__main__":
    main()
