"""camera360.py on a phone-controlled Ricoh Theta ride (docs/camera360.md section 4): session.camera360.shots matched to
the camera's files by name (R00100NN.JPG, case-insensitive), positions / times / bearings from the shots, the EXIF GPS
cross-check, no sync code needed, the clock for files without a shot from the matched ones, then prepare.py.

    python -m unittest discover -s .claude/skills/process-sweep/scripts/tests
"""
import json, os, shutil, subprocess, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
sys.path.insert(0, SCRIPTS)
sys.path.insert(0, HERE)

try:
    import numpy as np
    import cv2
    HAVE_CV = True
except ImportError:
    HAVE_CV = False

if HAVE_CV:
    import camera360 as C  # noqa: E402
    import synth360 as S  # noqa: E402

SID = 's_20261128_theta'
T0 = 1795000000000


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def run_py(script, *args):
    return subprocess.run([sys.executable, '-E', '-P', '-X', 'utf8', os.path.join(SCRIPTS, script), *args],
                          capture_output=True, text=True, encoding='utf-8')


@unittest.skipUnless(HAVE_CV, 'needs numpy + opencv-python')
class ShotRecords(unittest.TestCase):
    def test_shot_key_and_filter(self):
        self.assertEqual(C.shot_key({'fileName': 'R0010023.JPG'}), 'r0010023.jpg')
        self.assertEqual(C.shot_key({'fileName': None, 'fileUrl': 'http://192.168.1.1/files/abc/100RICOH/R0010024.JPG'}),
                         'r0010024.jpg')
        self.assertIsNone(C.shot_key({'fileUrl': None}))
        shots = [{'tMs': 1, 'lat': 10.0, 'lon': 106.0, 'ok': True, 'fileName': 'R0010001.JPG'},
                 {'tMs': 2, 'lat': 10.0, 'lon': 106.0, 'ok': False, 'error': 'timeout', 'fileName': None},
                 {'tMs': 3, 'lat': 10.0, 'lon': 106.0, 'ok': True, 'fileUrl': 'http://192.168.1.1/x/R0010003.JPG'}]
        self.assertEqual(sorted(C.theta_shots({'camera360': {'control': 'theta-osc', 'shots': shots}})),
                         ['r0010001.jpg', 'r0010003.jpg'])
        self.assertEqual(C.theta_shots({'camera360': {'shots': shots}}), {})   # not a Theta ride: no matching

    def test_exif_gps_round_trip(self):
        d = tempfile.mkdtemp()
        try:
            f = os.path.join(d, 'R0010001.JPG')
            S.save_jpg(f, np.zeros((128, 256, 3), np.uint8), T0, gps=(10.7769421, -106.7009245))
            fr = C.photo_frame(f)
            self.assertAlmostEqual(fr['exifGps'][0], 10.7769421, places=6)
            self.assertAlmostEqual(fr['exifGps'][1], -106.7009245, places=6)
            # a Theta photo with a shot needs no EXIF time
            g = os.path.join(d, 'R0010002.JPG')
            S.save_jpg(g, np.zeros((128, 256, 3), np.uint8), None, gps=(10.0, 106.0))
            with self.assertRaises(C.Refused):
                C.photo_frame(g)
            self.assertIsNone(C.photo_frame(g, need_time=False)['camMs'])
        finally:
            shutil.rmtree(d)


@unittest.skipUnless(HAVE_CV, 'needs numpy + opencv-python')
class ThetaRide(unittest.TestCase):
    """A straight street north at 5 m/s; the phone asked for a photo every 8 m. The camera's clock is 2 s behind and
    its shutter 150 ms after the command (track - camera = 1850 ms). One shot failed, one shot's file is missing, one
    photo has no shot (time matching), one photo's EXIF GPS is 50 m off, one has no EXIF time at all."""

    FRONT = 40.0
    segments = [(2, 0.0), (8, 5.0), (2, 0.0)]

    def setUp(self):
        self.d = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def frame(self, pts, tr):
        return S.render_street(1024, 512, S.y_at(pts, tr), self.FRONT)

    def test_theta_ride(self):
        ride = os.path.join(self.d, 'ride')
        shot_t = [T0 + 2000 + 1600 * k for k in range(5)]          # y = 0, 8, 16, 24, 32 m
        names = [f'R00100{23 + k}.JPG' for k in range(5)]
        shots = []
        for k, t in enumerate(shot_t):
            lat, lon = S.ll(5.0 * (t - T0 - 2000) / 1000)
            shots.append({'tMs': t, 'lat': lat, 'lon': lon, 'accuracyM': 3.5, 'bearing': 0.0, 'speedMps': 5.0,
                          'cmdId': str(23 + k), 'fileUrl': f'http://192.168.1.1/files/150100525831424d42/100RICOH/{names[k]}',
                          'fileName': names[k], 'ok': True})
        shots.insert(2, {'tMs': shot_t[1] + 800, 'lat': shots[1]['lat'], 'lon': shots[1]['lon'], 'accuracyM': 3.5,
                         'bearing': 0.0, 'speedMps': 5.0, 'cmdId': None, 'fileUrl': None, 'fileName': None, 'ok': False,
                         'error': 'timeout: no file after 20 s'})
        missing = {'tMs': T0 + 10500, 'lat': S.ll(40)[0], 'lon': S.ll(40)[1], 'accuracyM': 3.5, 'bearing': 0.0,
                   'speedMps': 5.0, 'cmdId': '30', 'fileUrl': 'http://192.168.1.1/files/x/100RICOH/R0010030.JPG',
                   'fileName': 'R0010030.JPG', 'ok': True}
        shots.append(missing)
        pts = S.write_ride(ride, SID, T0, self.segments, [], camera360={
            'control': 'theta-osc', 'everyM': 8, 'shots': shots,
            'camera': {'model': 'RICOH THETA X', 'serial': '12345678', 'firmware': '2.10.1'}})
        cam = os.path.join(self.d, 'DCIM', '100RICOH')
        os.makedirs(cam)
        for k, t in enumerate(shot_t):
            y = 5.0 * (t - T0 - 2000) / 1000
            gps = S.ll(y, 50.0) if k == 2 else S.ll(y)              # R0010025: EXIF GPS 50 m east of the shot
            name = names[k].lower() if k == 1 else names[k]         # r0010024.jpg: matched case-insensitively
            S.save_jpg(os.path.join(cam, name), self.frame(pts, t), None if k == 4 else t - 1850, tz_h=7, gps=gps)
        # a photo the phone did not ask for (taken on the camera's button): placed by time with the shots' offset
        S.save_jpg(os.path.join(cam, 'R0010099.JPG'), self.frame(pts, T0 + 9000), T0 + 9000 - 1850, tz_h=7)
        work = os.path.join(self.d, 'work')
        r = run_py('camera360.py', ride, cam, work, '--size', '640x480', '--front-deg', str(self.FRONT))
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertNotIn('NO SYNC CODE', r.stdout)
        rep = load(os.path.join(work, 'camera360.json'))
        man = load(os.path.join(work, 'sweep', 'manifest.json'))

        self.assertEqual(rep['clock']['source'], 'theta shots')
        self.assertEqual(rep['clock']['framesLooked'], 0)            # no QR scan
        self.assertEqual(rep['clock']['shots'], 4)                   # R0010027 has no EXIF time
        self.assertAlmostEqual(rep['clock']['offsetMs'], 1850, delta=2)
        th = rep['theta']
        self.assertEqual(th['shots'], 7)
        self.assertEqual(th['shotsWithFile'], 6)
        self.assertEqual(th['matched'], 5)
        self.assertEqual(th['failedShots'], 1)
        self.assertEqual(th['filesWithoutShot'], 1)
        self.assertEqual(th['shotsWithoutFile'], ['r0010030.jpg'])
        self.assertEqual(th['exifGpsChecked'], 5)
        self.assertEqual([x['file'] for x in th['exifGpsFar']], ['R0010025.JPG'])
        self.assertAlmostEqual(th['exifGpsFar'][0]['m'], 50.0, delta=1.0)
        self.assertTrue(any('over 30 m' in w and 'R0010025.JPG' in w for w in rep['warnings']), rep['warnings'])
        self.assertTrue(any('R0010030.JPG' in w for w in rep['warnings']), rep['warnings'])

        self.assertEqual(rep['kept'], 6)
        items = man['items']
        self.assertEqual(len(items), 18)
        by_file = {}
        for it in items:
            by_file.setdefault(it['camera360']['file'], []).append(it)
        self.assertEqual(sorted(by_file), sorted(['R0010023.JPG', 'r0010024.jpg', 'R0010025.JPG', 'R0010026.JPG',
                                                  'R0010027.JPG', 'R0010099.JPG']))
        for k, n in enumerate(names):
            its = by_file[n.lower() if k == 1 else n]
            s = shots[k if k < 2 else k + 1]
            self.assertEqual(s['fileName'], n)
            for it in its:
                self.assertEqual(it['positionFrom'], 'theta shot')
                self.assertEqual(it['lat'], round(s['lat'], 7))
                self.assertEqual(it['lon'], round(s['lon'], 7))
                self.assertEqual(it['tMs'], s['tMs'])
                self.assertEqual(it['travelBearing'], 0.0)
                self.assertIsNone(it['elapsedNanos'])                # prepare.py keeps the shot's position
                exp = {'front': 0.0, 'left': 270.0, 'right': 90.0}[it['camera360']['view']]
                self.assertEqual(it['cameraBearing'], exp)
                self.assertEqual(it['camera360']['shot']['cmdId'], s['cmdId'])
        for it in by_file['R0010099.JPG']:
            self.assertEqual(it['positionFrom'], 'phone track')
            self.assertAlmostEqual(it['lat'], S.ll(35.0)[0], delta=0.3 / 110540)
            self.assertAlmostEqual(it['tMs'], T0 + 9000, delta=2)
        self.assertEqual(man['session']['camera360']['control'], 'theta-osc')

        prep = os.path.join(work, 'prep')
        r = run_py('prepare.py', os.path.join(work, 'sweep'), prep)
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        photos = load(os.path.join(prep, 'photos.json'))
        self.assertEqual(len(photos), 18)
        for p, it in zip(photos, items):
            self.assertEqual(p['id'], it['id'])
            self.assertAlmostEqual(p['lat'], it['lat'], delta=0.05 / 110540)
            self.assertEqual(p['cameraBearing'], it['cameraBearing'])

    def test_no_file_matches_falls_back_to_time(self):
        ride = os.path.join(self.d, 'ride')
        pts = S.write_ride(ride, SID, T0, self.segments, [], camera360={
            'control': 'theta-osc', 'everyM': 8, 'shots': [
                {'tMs': T0 + 4000, 'lat': S.ll(10)[0], 'lon': S.ll(10)[1], 'accuracyM': 3, 'bearing': 0.0,
                 'speedMps': 5.0, 'cmdId': '1', 'fileUrl': None, 'fileName': 'R0010001.JPG', 'ok': True}]})
        cam = os.path.join(self.d, 'cam')
        os.makedirs(cam)
        for n, t in enumerate(range(T0 + 2000, T0 + 10001, 1000)):   # other names: the camera clock as set (no offset)
            S.save_jpg(os.path.join(cam, f'IMG_{n:04d}.JPG'), self.frame(pts, t), t, tz_h=7)
        work = os.path.join(self.d, 'work')
        r = run_py('camera360.py', ride, cam, work, '--size', '320x240', '--front-deg', str(self.FRONT))
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        rep = load(os.path.join(work, 'camera360.json'))
        self.assertEqual(rep['theta']['matched'], 0)
        self.assertTrue(any('none of the 1 Theta shots' in w for w in rep['warnings']), rep['warnings'])
        self.assertEqual(rep['clock']['source'], 'camera clock as is')
        self.assertIn('set the camera', r.stdout)
        self.assertGreaterEqual(rep['kept'], 5)


if __name__ == '__main__':
    unittest.main()
