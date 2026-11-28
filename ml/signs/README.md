# Vietnamese traffic-sign detector (first model)

Small object detector for Vietnamese road signs, meant for

* the phone (POI Capture app): notice "a sign is in view" and tag photos with sign boxes + a sign code;
* the processing scripts: pre-mark sign boxes so the processing agent reads the sign (the final reading stays with the agent).

Everything heavy lives on `D:\poi-capture-ml` (venv, pip cache, YOLOX checkout, datasets, checkpoints,
exports, evaluation sheets). This folder only holds the config and scripts.

## Results (model `signs_nano`, YOLOX-Nano 0.9 M params, 640x640, 80 epochs, ~75 min on the RTX 4060)

Files (on D:, not in the repo):

| What | Path |
|---|---|
| ONNX for phone / scripts | `D:\poi-capture-ml\export\signs_nano_640.onnx` (3.8 MB, fp32, opset 12, static 1x3x640x640) |
| PyTorch weights | `D:\poi-capture-ml\export\signs_nano.pth` (state dict of the best checkpoint, epoch 73) |
| Training checkpoints + log | `D:\poi-capture-ml\ckpt\signs_nano\` |
| Val metrics | `D:\poi-capture-ml\eval\val_metrics_signs_nano.json` |
| Trường Sa contact sheets | `D:\poi-capture-ml\eval\truongsa_nano_full\` (whole photo, phone mode) and `D:\poi-capture-ml\eval\truongsa_nano_tiles\` (+ tiles), each with `detections.json` |

ONNX vs PyTorch on 20 val images: max box difference 0.12 px, max score difference 0.0006, same
detections. CPU (onnxruntime on the dev laptop): **60 ms/image with 1 thread, 29 ms with 4 threads**
(a phone CPU will be slower; NNAPI/GPU delegate or int8 would help).

### Validation (held-out VNTS, blocked split: 516 images, 1,353 signs, scenes not seen in training)

* **mAP@0.5 = 0.487** (COCO AP50, YOLOX evaluator, class-aware NMS); mAP@0.5:0.95 = 0.367.
  With our class-agnostic NMS (`eval_val.py`): mAP@0.5 = 0.449.
* For comparison, the dataset's own random split gave mAP@0.5 = 0.965 already at epoch 30. That
  number is meaningless: neighbouring video frames end up in both train and val.
* Recall at score >= 0.3 (precision of the boxes, any class: 0.88):

| Group | Val signs | Found (box IoU >= 0.5) | Found with the right code |
|---|---|---|---|
| prohibitory | 882 | 0.72 | 0.61 |
| warning | 199 | 0.80 | 0.36 |
| mandatory | 204 | 0.72 | 0.70 |
| information | 61 | 0.03 | 0.03 |
| auxiliary (sub-plates) | 7 | 0.00 | 0.00 |
| **all** | 1,353 | **0.70** | **0.55** |

"A sign is here" works reasonably for red-circle, triangle and blue-round signs. The exact code is
right about half the time (warning triangles often get the wrong W code). Blue information signs are
essentially not detected.

### Our photos: ride "Trường Sa" (214 photos, sideways phone, mixed portrait 1840x3264 and landscape 3264x1840)

Every detection crop was checked by eye, and all 214 photos were scanned on overview sheets
(`D:\poi-capture-ml\eval\truongsa_overview\`). 16 photos show a real, front-facing traffic sign
(49, 50, 51, 52, 54, 57, 58, 158, 159, 160, 161, 173, 190, 194, 195, 196).

| Mode | Threshold | Detections | True (real sign) | False | Sign photos with >= 1 true box |
|---|---|---|---|---|---|
| whole photo (phone) | 0.25 | 22 on 17 photos | **17** | 5 | 12 / 16 |
| whole photo (phone) | 0.50 | 12 | **11** | 1 | 9 / 16 |
| + tiles (processing) | 0.25 | 71 on 48 photos | **25** | 46 | 15 / 16 |
| + tiles (processing) | 0.50 | 40 | **19** | 21 | 15 / 16 |

* True detections: no stopping/parking P.130 (p50, p51), no parking P.131a (p158-161, p173, p190),
  blue pedestrian crossing I.423 (p158, p159; boxed but named I.407a, since I.423 is not a class),
  keep right R.302a (p194, p195 with the right code; far ones on p49-p52), curve-right and "Đi chậm"
  warning triangles (p50, p51; named W.202b / W.245a / W.201b), no-turn signs (p54, p57, p58),
  a distant no-entry P.102 (p52, named P.123a), a "30 m" distance sign (p52).
  The exact code is right on about a third of the true boxes (P.130, P.131a, R.302a).
* False detections, phone mode: a Tiger beer logo, a flag, a dark round lamp, a logo on a board, a
  house-number plate. With tiles (close-up crops of facades): mostly big letters "O", "S", "9", "Q" on
  shop signs read as speed-limit or no-parking signs, flags, logos, a person, a car mirror, plus a
  street-name plate and a private "no car parking" notice (not official signs).
* Misses visible in the photos: no-entry P.102 above the keep-right sign on p194/p195 (cut by the top
  edge of the photo; scored only 0.14-0.22), the I.423 crossing sign under the no-parking sign on p160,
  the give-way triangles (W.208, not a class) under the no-turn signs on p57/p58, the small "30 m" signs
  on p50/p51, the half-visible keep-right on p196.

Suggested use for now: the phone runs whole-photo mode and treats score >= 0.5 as "sign in view" (few
false alarms), storing boxes >= 0.3 as hints. Processing runs tiles with score >= 0.5 and lets the agent
read or reject each box. The agent must still verify: about half of the tile boxes are letters or logos.

## Known gaps

* Domain gap: VNTS is forward dash-cam video of roads. Our photos are sideways facades full of shop
  lettering, so letters and logos cause most false positives (training had no such negatives).
* Information (I.*) signs, sub-plates (S.*, hours "6:00-22:00"), give-way, I.423, keep-left and
  street-name plates are missing or nearly missing from the classes.
* Warning triangles are found but often get the wrong W code. Speed limits are one class per value
  (P.127 40/50/60/80 only).
* Signs cut by the photo edge are usually missed.
* The validation set has few independent scenes (~3,200 frames from a limited number of drives), so the
  metrics are noisy (AP50 between 0.43 and 0.49 from epoch 30 to 80).
* Licence: CC BY-SA dataset (see above). The cleanest long-term fix is our own labelled data.

## What would improve it most

1. **Label our own photos**: ~500-1,000 frames from forward-facing "Traffic signs" rides (boxes +
   QCVN code), plus ~300 sign boxes from sideways shop rides. This fixes the domain gap, adds the
   missing classes (I.423, R.302b, W.208, sub-plates, information signs) and gives us data we own.
2. **Hard negatives**: add ~1,000 sideways shop-ride photos *without* signs (checked) as background
   images. This is the quickest way to cut false positives on letters, logos and flags.
3. Two-stage reading: a detector with 4-5 coarse groups (prohibitory / warning / mandatory /
   information / sub-plate) for recall, then a small classifier (or the processing agent) on the crop
   for the exact code.
4. YOLOX-Tiny (`exps/signs_tiny.py`, ready but not trained) for the processing scripts once there is
   more data; keep Nano on the phone. Then an int8 TFLite / NNAPI export for the app.


## Licences (all permissive, nothing AGPL/GPL)

| Part | Licence | Source |
|---|---|---|
| YOLOX code (training, model definition) | Apache-2.0 | https://github.com/Megvii-BaseDetection/YOLOX (commit 6ddff48, plus the 2 small Windows fixes in yolox_local.patch) |
| COCO-pretrained YOLOX-Nano / Tiny weights (init) | Apache-2.0 (released with YOLOX) | https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0 |
| PyTorch / torchvision | BSD-3 | pytorch.org (cu124 wheels) |
| onnx / onnxruntime | Apache-2.0 / MIT | PyPI |
| VNTS dataset (training data) | **CC BY-SA 4.0** | see below |

No Ultralytics package or weights and no VTSR model were used anywhere.

### Dataset: VNTS - Vietnam Road Signs Dataset

* Author: Thanh Hiep Vo. Kaggle: https://www.kaggle.com/datasets/maitam/vietnamese-traffic-signs
  (licence field on Kaggle: **CC BY-SA 4.0**; downloaded without an account via the public Kaggle
  API download URL). Same author's mirror on Hugging Face: https://huggingface.co/datasets/thanhhiepvos/vietnam_traffic_sign (tagged CC BY-SA 3.0).
* ~3,216 dash-cam images (960x540 and 1920x1080, Vietnamese city streets), 8,334 boxes, 52 classes,
  The images are consecutive video frames, so the author's random train/test split leaks (near-identical
  frames on both sides). prepare_vnts.py holds out whole runs of 100 consecutive frames instead: train
  2,491 images / 6,518 boxes, val 516 images / 1,353 boxes, 209 frames dropped as a guard band.
* Attribution text to ship with the model (e.g. in the app's open-source / licences screen):

  > Traffic-sign detector trained on "Vietnam Road Signs Dataset (VNTS)" by Thanh Hiep Vo,
  > https://www.kaggle.com/datasets/maitam/vietnamese-traffic-signs, licensed CC BY-SA 4.0
  > (https://creativecommons.org/licenses/by-sa/4.0/). Detector architecture and initial weights:
  > YOLOX, Copyright (c) Megvii Inc., Apache License 2.0.

* ShareAlike note: CC BY-SA allows commercial use. ShareAlike applies to *adapted versions of the
  dataset* (if we ever redistribute the images or relabelled copies, they must stay CC BY-SA). Creative
  Commons' own guidance is that training a model is generally not "adapted material" of the dataset,
  so the weights are not forced under BY-SA, but this is a reading, not legal advice - have it
  checked before shipping, or replace the data with our own labelled photos (see "What would improve it").

### Datasets looked at and NOT used

| Dataset | Why not |
|---|---|
| star092304/Traffic-sign-detection-VietNam (HF, 10k images, 82 classes; same copies under Minh124689/, tantan294/) | Licence contradictory: metadata says CC BY 4.0 but the README badge links to CC BY-**NC** 4.0; provenance not stated and it appears to re-contain VNTS (identical class names) under a different licence. Treated as unknown -> not used. |
| Roboflow Universe Vietnamese sign sets (e.g. giang-yp9g1/vietnam-traffic-sign-altsi, CC BY 4.0) | Download needs a Roboflow account / API key -> skipped as instructed. |
| Hoang42/VietNam_Traffic_sign_Augmentationed (HF, "MIT") | No description or provenance, a re-upload of unknown origin -> not used. |
| ghbihuy/vietnam_traffic_sign (HF, MIT) | Sign icons scraped from a driving-test website, no boxes -> not used. |
| Zalo AI Challenge 2020 traffic signs, Mapillary MTSD | Competition-only / non-commercial (MTSD: CC BY-NC-SA) terms, not re-checked in detail -> not used. |

## Classes

52 classes, in model output order: [classes.csv](classes.csv) (`id, code, qcvn, group, en, vi`).
`code` is the dataset's label; `qcvn` is the QCVN 41:2019 code (cleaned: e.g. `P.127*50` -> `P.127 (50)`);
`group` is prohibitory / warning / mandatory / information / auxiliary.

Main gaps in the class list (not in VNTS, so the model cannot name them, it may still box them as a
neighbour class): I.423 (blue pedestrian-crossing square), R.302b (keep left), W.208 (give way),
sub-plates such as hours "6:00-22:00", street-name plates, most I.* information signs.

## How to reproduce (Windows, PowerShell or Git Bash)

```bash
# 1. environment (all on D:)
mkdir D:/poi-capture-ml && cd D:/poi-capture-ml
C:/Python312/python.exe -m venv venv
export PIP_CACHE_DIR=D:/poi-capture-ml/pip-cache TMP=D:/poi-capture-ml/tmp TEMP=D:/poi-capture-ml/tmp PYTHONNOUSERSITE=1
venv/Scripts/python.exe -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
venv/Scripts/python.exe -m pip install onnx onnxruntime opencv-python-headless loguru tabulate pycocotools thop ninja psutil tensorboard pillow tqdm
git clone --depth 1 https://github.com/Megvii-BaseDetection/YOLOX.git code/YOLOX
curl -L -o ckpt/pretrained/yolox_nano.pth https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.pth
# two small local fixes for Windows (yolox_local.patch): --cache disk crash, and the evaluator
# trying to JIT-compile a C++ op (needs MSVC) -> plain pycocotools
(cd code/YOLOX && git apply <repo>/ml/signs/yolox_local.patch)

# 2. data
curl -L -o data/vnts_raw/vnts.zip https://www.kaggle.com/api/v1/datasets/download/maitam/vietnamese-traffic-signs
(cd data/vnts_raw && unzip vnts.zip)
cd <repo>/ml/signs
D:/poi-capture-ml/venv/Scripts/python.exe prepare_vnts.py --raw D:/poi-capture-ml/data/vnts_raw/archive --out D:/poi-capture-ml/data/vnts_coco_blocked   # blocked split (default), see prepare_vnts.py

# 3. train (from the YOLOX folder; ~50 s/epoch on the RTX 4060 laptop, data-loader bound; 80 epochs ~ 75 min)
cd D:/poi-capture-ml/code/YOLOX
PYTHONPATH=. ../../venv/Scripts/python.exe tools/train.py -f <repo>/ml/signs/exps/signs_nano.py \
    -d 1 -b 32 --fp16 --cache disk -c D:/poi-capture-ml/ckpt/pretrained/yolox_nano.pth
# -> D:/poi-capture-ml/ckpt/signs_nano/best_ckpt.pth. Keep only the weights (the checkpoint holds numpy scalars,
#    which torch.load(weights_only=True) refuses):
#    python -c "import torch; c=torch.load('D:/poi-capture-ml/ckpt/signs_nano/best_ckpt.pth', weights_only=False); torch.save({'model': c['model']}, 'D:/poi-capture-ml/export/signs_nano.pth')"

# 4. export + check ONNX against PyTorch, CPU timing
cd <repo>/ml/signs
export PYTHONPATH=D:/poi-capture-ml/code/YOLOX
D:/poi-capture-ml/venv/Scripts/python.exe export_onnx.py --exp exps/signs_nano.py \
    --ckpt D:/poi-capture-ml/export/signs_nano.pth --out D:/poi-capture-ml/export/signs_nano_640.onnx \
    --check-dir D:/poi-capture-ml/data/vnts_coco_blocked/val2017

# 5. validation metrics (mAP@0.5 + per-group recall)
D:/poi-capture-ml/venv/Scripts/python.exe eval_val.py --onnx D:/poi-capture-ml/export/signs_nano_640.onnx

# 6. our photos: detections.json + contact sheets
D:/poi-capture-ml/venv/Scripts/python.exe run_ride.py --photos <ride>/work/photos.json \
    --onnx D:/poi-capture-ml/export/signs_nano_640.onnx --out D:/poi-capture-ml/eval/<ride> [--tiles]
```

Training choices (exps/signs_nano.py): 640x640 input (signs are small: median box ~3 % of image
width), mosaic on, mixup off, **horizontal flip off** (a flip turns "no left turn" into "no right
turn"), 80 epochs (10 without augmentation), batch 32, fp16, COCO-pretrained YOLOX-Nano init.

## Using the model (phone / scripts)

ONNX, opset 12, static input `images` float32 [1,3,640,640], **BGR, 0-255, no normalisation**; the
photo is resized keeping aspect ratio to fit 640, placed top-left, padded with value 114.
Output `output` float32 [1, 8400, 57]: `cx, cy, w, h` (in 640-input pixels), `objectness`, 52 class
probabilities (already sigmoid). Score = objectness x class prob; divide boxes by the resize ratio;
then NMS (we use class-agnostic NMS 0.45). See `signs_lib.py` for a 60-line reference implementation.
For tall portrait photos `detect(..., tiles=True)` also runs 2 square crops along the long side and
merges - roughly 3x the cost, finds smaller signs.
