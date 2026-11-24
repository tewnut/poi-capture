"""Validation metrics on the held-out VNTS split: COCO mAP@0.5 (class-aware) plus per-group recall.

    python eval_val.py --onnx D:/poi-capture-ml/export/signs_nano_640.onnx
    python eval_val.py --exp exps/signs_nano.py --ckpt D:/poi-capture-ml/ckpt/signs_nano/best_ckpt.pth

Recall per group at --conf (default 0.3), a GT sign counts as
  found    : some detection overlaps it with IoU >= 0.5 (any class)  -> "a sign is in view"
  correct  : ... and the detection's class is right                 -> sign code also right
"""
import argparse
import collections
import contextlib
import io
import json
import os

import cv2
import numpy as np
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval

import signs_lib as L


def iou(a, b):
    x0, y0 = np.maximum(a[0], b[:, 0]), np.maximum(a[1], b[:, 1])
    x1, y1 = np.minimum(a[2], b[:, 2]), np.minimum(a[3], b[:, 3])
    inter = np.clip(x1 - x0, 0, None) * np.clip(y1 - y0, 0, None)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1]) - inter)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="D:/poi-capture-ml/data/vnts_coco_blocked")
    ap.add_argument("--onnx")
    ap.add_argument("--exp")
    ap.add_argument("--ckpt")
    ap.add_argument("--conf", type=float, default=0.3)
    ap.add_argument("--out", help="write metrics json here")
    a = ap.parse_args()
    classes = L.load_classes()
    det = L.OnnxDetector(a.onnx) if a.onnx else L.TorchDetector(a.exp, a.ckpt)
    gt = COCO(os.path.join(a.data, "annotations", "instances_val2017.json"))
    results = []
    stats = collections.defaultdict(lambda: [0, 0, 0])  # group -> [gt, found, correct]
    n_det = n_tp = 0
    for img_id in gt.getImgIds():
        info = gt.loadImgs(img_id)[0]
        img = cv2.imread(os.path.join(a.data, "val2017", info["file_name"]))
        d = L.detect(det, img, conf=0.001, nms_thr=0.5)
        for x0, y0, x1, y1, s, c in d[:300]:
            results.append({"image_id": img_id, "category_id": int(c), "score": float(s),
                            "bbox": [float(x0), float(y0), float(x1 - x0), float(y1 - y0)]})
        d = d[d[:, 4] >= a.conf]
        n_det += len(d)
        used = np.zeros(len(d), bool)
        for ann in gt.loadAnns(gt.getAnnIds(imgIds=img_id)):
            g = ann["bbox"]
            g = np.array([g[0], g[1], g[0] + g[2], g[1] + g[3]])
            grp = classes[ann["category_id"]]["group"]
            st = stats[grp]
            st[0] += 1
            if len(d):
                ious = iou(g, d[:, :4])
                ious[used] = 0
                j = int(ious.argmax())
                if ious[j] >= 0.5:
                    used[j] = True
                    st[1] += 1
                    st[2] += int(d[j, 5]) == ann["category_id"]
        n_tp += int(used.sum())
    with contextlib.redirect_stdout(io.StringIO()):
        ev = COCOeval(gt, gt.loadRes(results), "bbox")
        ev.evaluate()
        ev.accumulate()
        ev.summarize()
    m = {"mAP50": round(ev.stats[1], 4), "mAP50_95": round(ev.stats[0], 4),
         "conf": a.conf, "precision_any_class": round(n_tp / max(n_det, 1), 4), "groups": {}}
    tot = [0, 0, 0]
    for g, (n, f, c) in sorted(stats.items()):
        m["groups"][g] = {"gt": n, "recall_found": round(f / n, 3), "recall_correct_class": round(c / n, 3)}
        tot = [tot[0] + n, tot[1] + f, tot[2] + c]
    m["groups"]["ALL"] = {"gt": tot[0], "recall_found": round(tot[1] / tot[0], 3),
                          "recall_correct_class": round(tot[2] / tot[0], 3)}
    print(json.dumps(m, indent=1))
    if a.out:
        json.dump(m, open(a.out, "w"), indent=1)


if __name__ == "__main__":
    main()
