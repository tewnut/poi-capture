# YOLOX-Tiny (Apache-2.0, Megvii) for Vietnamese traffic signs, 52 VNTS classes (see ../classes.csv).
# Train:  python tools/train.py -f <this file> -d 1 -b 16 --fp16 -c D:/poi-capture-ml/ckpt/pretrained/yolox_tiny.pth
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from signs_nano import Exp as NanoExp  # noqa: E402

from yolox.exp import Exp as BaseExp  # noqa: E402


class Exp(NanoExp):
    def __init__(self):
        super().__init__()
        self.depth = 0.33
        self.width = 0.375
        self.exp_name = os.path.splitext(os.path.basename(__file__))[0]

    def get_model(self, sublinear=False):
        # standard (non-depthwise) YOLOX head/backbone, as in YOLOX-Tiny
        return BaseExp.get_model(self)
