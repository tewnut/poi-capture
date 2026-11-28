"""Shared inference helpers for the sign detector (PyTorch checkpoint or ONNX), no YOLOX needed for ONNX.

Model I/O (both backends):
    input  "images": float32 [1, 3, S, S], BGR, 0..255 (no mean/std), image resized keeping aspect
           ratio so it fits SxS, placed at the TOP-LEFT, rest padded with 114.
    output "output": float32 [1, N, 5 + 52]  -> cx, cy, w, h (pixels of the SxS input), objectness,
           52 class probabilities (already sigmoid).  score = objectness * class prob.
"""
import csv
import os

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))


def load_classes():
    with open(os.path.join(HERE, "classes.csv"), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def preprocess(img, size):
    h, w = img.shape[:2]
    r = min(size / h, size / w)
    resized = cv2.resize(img, (int(round(w * r)), int(round(h * r))), interpolation=cv2.INTER_LINEAR)
    pad = np.full((size, size, 3), 114, dtype=np.uint8)
    pad[: resized.shape[0], : resized.shape[1]] = resized
    x = pad.transpose(2, 0, 1)[None].astype(np.float32)
    return np.ascontiguousarray(x), r


def nms(boxes, scores, thr):
    order = scores.argsort()[::-1]
    keep = []
    while order.size:
        i = order[0]
        keep.append(i)
        xx0 = np.maximum(boxes[i, 0], boxes[order[1:], 0])
        yy0 = np.maximum(boxes[i, 1], boxes[order[1:], 1])
        xx1 = np.minimum(boxes[i, 2], boxes[order[1:], 2])
        yy1 = np.minimum(boxes[i, 3], boxes[order[1:], 3])
        inter = np.clip(xx1 - xx0, 0, None) * np.clip(yy1 - yy0, 0, None)
        a = (boxes[i, 2] - boxes[i, 0]) * (boxes[i, 3] - boxes[i, 1])
        b = (boxes[order[1:], 2] - boxes[order[1:], 0]) * (boxes[order[1:], 3] - boxes[order[1:], 1])
        iou = inter / np.maximum(a + b - inter, 1e-9)
        order = order[1:][iou <= thr]
    return np.array(keep, dtype=np.int64)


def postprocess(out, ratio, conf=0.25, nms_thr=0.45, offset=(0, 0)):
    """out: [N, 5+C] for one image -> array [K, 6] x0,y0,x1,y1,score,cls in original-image pixels.
    Class-agnostic NMS: one box per physical sign."""
    out = np.asarray(out, dtype=np.float32)
    cls = out[:, 5:].argmax(1)
    score = out[:, 4] * out[np.arange(len(out)), 5 + cls]
    m = score >= conf
    if not m.any():
        return np.zeros((0, 6), np.float32)
    o, score, cls = out[m], score[m], cls[m]
    boxes = np.stack([o[:, 0] - o[:, 2] / 2, o[:, 1] - o[:, 3] / 2, o[:, 0] + o[:, 2] / 2, o[:, 1] + o[:, 3] / 2], 1)
    boxes /= ratio
    boxes[:, [0, 2]] += offset[0]
    boxes[:, [1, 3]] += offset[1]
    k = nms(boxes, score, nms_thr)
    return np.concatenate([boxes[k], score[k, None], cls[k, None].astype(np.float32)], 1)


class OnnxDetector:
    def __init__(self, path, threads=None):
        import onnxruntime as ort
        so = ort.SessionOptions()
        if threads:
            so.intra_op_num_threads = threads
        self.sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
        self.size = self.sess.get_inputs()[0].shape[2]

    def raw(self, x):
        return self.sess.run(None, {self.sess.get_inputs()[0].name: x})[0]


class TorchDetector:
    def __init__(self, exp_file, ckpt, size=640, device="cuda"):
        import torch
        from yolox.exp import get_exp
        exp = get_exp(exp_file, None)
        model = exp.get_model()
        sd = torch.load(ckpt, map_location="cpu", weights_only=True)
        model.load_state_dict(sd["model"] if "model" in sd else sd)
        model.head.decode_in_inference = True
        self.model = model.eval().to(device)
        self.device, self.size, self.torch = device, size, torch

    def raw(self, x):
        with self.torch.no_grad():
            return self.model(self.torch.from_numpy(x).to(self.device)).float().cpu().numpy()


def detect(det, img, conf=0.25, nms_thr=0.45, tiles=False):
    """Whole image letterboxed to the model size; with tiles=True also run square tiles along the
    long side (better for small signs on tall portrait photos) and merge."""
    x, r = preprocess(img, det.size)
    res = [postprocess(det.raw(x)[0], r, conf, nms_thr)]
    if tiles:
        h, w = img.shape[:2]
        s, L = min(h, w), max(h, w)
        n = int(np.ceil((L - s) / (0.75 * s))) + 1 if L > s else 1
        for i in range(n):
            o = int(round(i * (L - s) / max(n - 1, 1)))
            crop = img[o:o + s, :] if h >= w else img[:, o:o + s]
            x, r = preprocess(crop, det.size)
            res.append(postprocess(det.raw(x)[0], r, conf, nms_thr, (0, o) if h >= w else (o, 0)))
    d = np.concatenate(res, 0)
    if len(d) and tiles:
        d = d[nms(d[:, :4], d[:, 4], nms_thr)]
    return d
