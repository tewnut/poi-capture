"""Export a trained checkpoint to ONNX (static 1x3xSxS, decoded output) and check it against PyTorch.

    python export_onnx.py --exp exps/signs_nano.py --ckpt D:/poi-capture-ml/ckpt/signs_nano/best_ckpt.pth \
        --out D:/poi-capture-ml/export/signs_nano_640.onnx --size 640 --check-dir D:/poi-capture-ml/data/vnts_coco/val2017
"""
import argparse
import glob
import os
import time

import cv2
import numpy as np
import torch

import signs_lib as L


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exp", required=True)
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--size", type=int, default=640)
    ap.add_argument("--opset", type=int, default=12)
    ap.add_argument("--check-dir")
    a = ap.parse_args()

    td = L.TorchDetector(a.exp, a.ckpt, size=a.size, device="cpu")
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    dummy = torch.zeros(1, 3, a.size, a.size)
    torch.onnx.export(td.model, dummy, a.out, input_names=["images"], output_names=["output"],
                      opset_version=a.opset, do_constant_folding=True, dynamo=False)
    print("wrote", a.out, round(os.path.getsize(a.out) / 1e6, 2), "MB")

    od = L.OnnxDetector(a.out)
    if a.check_dir:
        files = sorted(glob.glob(os.path.join(a.check_dir, "*.jpg")))[:8]
        worst = 0.0
        for f in files:
            x, r = L.preprocess(cv2.imread(f), a.size)
            pt, ox = td.raw(x)[0], od.raw(x)[0]
            worst = max(worst, float(np.abs(pt - ox).max()))
            dp, do = L.postprocess(pt, r), L.postprocess(ox, r)
            same = len(dp) == len(do) and (len(dp) == 0 or (np.abs(dp - do).max() < 0.5))
            print(os.path.basename(f), "torch", len(dp), "onnx", len(do), "match" if same else "DIFF")
        print("max |torch - onnx| on raw output:", worst)
    x = np.random.randint(0, 255, (1, 3, a.size, a.size)).astype(np.float32)
    for threads in (1, 4):
        od = L.OnnxDetector(a.out, threads=threads)
        od.raw(x)
        t = time.perf_counter()
        for _ in range(20):
            od.raw(x)
        print(f"onnxruntime CPU, {threads} thread(s): {(time.perf_counter() - t) / 20 * 1000:.1f} ms/image")


if __name__ == "__main__":
    main()
