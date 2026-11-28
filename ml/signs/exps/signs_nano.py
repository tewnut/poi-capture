# YOLOX-Nano (Apache-2.0, Megvii) for Vietnamese traffic signs, 52 VNTS classes (see ../classes.csv).
# Train:  python tools/train.py -f <this file> -d 1 -b 32 --fp16 -c D:/poi-capture-ml/ckpt/pretrained/yolox_nano.pth
import os

import torch.nn as nn

from yolox.exp import Exp as MyExp

DATA = os.environ.get("SIGNS_DATA", "D:/poi-capture-ml/data/vnts_coco_blocked")
OUT = os.environ.get("SIGNS_OUT", "D:/poi-capture-ml/ckpt")


class Exp(MyExp):
    def __init__(self):
        super().__init__()
        self.num_classes = 52
        self.depth = 0.33
        self.width = 0.25
        self.data_dir = DATA
        self.output_dir = OUT
        self.exp_name = os.path.splitext(os.path.basename(__file__))[0]

        # signs are small (median ~3% of image width): train and test at 640
        self.input_size = (640, 640)
        self.test_size = (640, 640)
        self.multiscale_range = 3
        self.mosaic_prob = 1.0
        self.mosaic_scale = (0.5, 1.5)
        self.enable_mixup = False
        # NO horizontal flip: it turns "no left turn" into "no right turn", keep-left into keep-right...
        self.flip_prob = 0.0
        self.degrees = 5.0
        self.shear = 2.0

        self.max_epoch = 80
        self.no_aug_epochs = 10
        self.warmup_epochs = 3
        self.eval_interval = 10
        self.print_interval = 20
        self.data_num_workers = 6
        self.save_history_ckpt = False
        self.test_conf = 0.01
        self.nmsthre = 0.5

    def get_model(self, sublinear=False):
        def init_yolo(M):
            for m in M.modules():
                if isinstance(m, nn.BatchNorm2d):
                    m.eps = 1e-3
                    m.momentum = 0.03

        if "model" not in self.__dict__:
            from yolox.models import YOLOX, YOLOPAFPN, YOLOXHead
            in_channels = [256, 512, 1024]
            backbone = YOLOPAFPN(self.depth, self.width, in_channels=in_channels, act=self.act, depthwise=True)
            head = YOLOXHead(self.num_classes, self.width, in_channels=in_channels, act=self.act, depthwise=True)
            self.model = YOLOX(backbone, head)
        self.model.apply(init_yolo)
        self.model.head.initialize_biases(1e-2)
        return self.model
