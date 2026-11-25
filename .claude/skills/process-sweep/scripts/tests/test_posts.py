"""Tests for signs on one post (a pole or a gantry: "post" / "order" in places.json signs) and for a shop's own
address entry in its Google check (placeRef -> result.google_check address_numbers).

    python -m unittest discover -s .claude/skills/process-sweep/scripts/tests -v
"""
import contextlib, io, json, math, os, shutil, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import build  # noqa: E402
import merge_places  # noqa: E402
import result  # noqa: E402

LAT0, LON0, HF = 10.78, 106.77, 69.1


def read(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def write(path, data):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(json.dumps(data, ensure_ascii=False))


def ride(n=10, step=5.5):
    """Riding north from (LAT0, LON0), a forward photo every `step` m."""
    out = []
    for i in range(n):
        lat, lon = build.to_ll(0, i * step, LAT0, LON0)
        out.append({'idx': i, 'lat': lat, 'lon': lon, 'accuracyM': 3, 'cameraBearing': 0, 'travelBearing': 0,
                    'cameraBearingSource': 'forward', 'hFovDeg': HF, 'width': 3264, 'height': 1840,
                    'orientation': 'landscape'})
    return out


def seen(photos, i, east, north, width_m=0.70):
    """(x, w) of a sign at (east, north) metres, `width_m` wide, facing the rider, in photo i."""
    x0, y0 = build.to_xy(photos[i]['lat'], photos[i]['lon'], LAT0, LON0)
    f = 0.5 / math.tan(math.radians(HF / 2))
    return 0.5 + f * (east - x0) / (north - y0), width_m * f / (north - y0)


def xy(lat, lon):
    return build.to_xy(lat, lon, LAT0, LON0)


ROAD = {'fromPhoto': 0, 'toPhoto': 99, 'lanes': [3.2, 3.2, 3.2], 'riderLane': 1, 'divider': True}


class Posts(unittest.TestCase):

    def test_groups_and_order(self):
        signs = [{'code': 'A', 'post': 'p1', 'order': 2}, {'code': 'B'}, {'code': 'C', 'post': 'p1', 'order': 1},
                 {'code': 'D', 'post': 7}, {'code': 'E', 'post': 7},
                 {'code': 'G1', 'post': 'g', 'side': 'over', 'lane': 1},
                 {'code': 'G3', 'post': 'g', 'side': 'over', 'lane': 3},
                 {'code': 'G2', 'post': 'g', 'side': 'over', 'lane': 2}]
        g = build.post_groups(signs)
        self.assertEqual(g, {'p1': [2, 0], '7': [3, 4], 'g': [6, 7, 5]})   # gantry: from the left (highest lane)

    def test_spec_checks(self):
        spec = {'signs': [{'code': 'P.104', 'post': 'p1', 'side': 'left', 'order': 1},
                          {'code': 'P.127', 'post': 'p1', 'side': 'right', 'order': 1},
                          {'code': 'P.130', 'order': 2},
                          {'code': 'P.102', 'post': True}]}
        bad = ' | '.join(build.check_spec(spec))
        self.assertIn('post p1: its signs have different sides', bad)
        self.assertIn('post p1: two signs with the same order', bad)
        self.assertIn('order without a post', bad)
        self.assertIn('post must be a string', bad)
        self.assertEqual(build.check_spec({'signs': [{'code': 'P.104', 'post': 'p1', 'side': 'left', 'order': 1},
                                                     {'code': 'P.127', 'post': 'p1', 'side': 'left'}]}), [])

    def test_a_pole_gets_one_position_from_all_its_sightings(self):
        """Two signs on one pole, 4 m right, 40 m ahead of photo 0: the top one read with a width on one photo, the
        lower one seen on other photos only. Alone each is placed differently; on the post both get one point that
        uses the range and every ray."""
        photos = ride()
        x0, w0 = seen(photos, 2, 4, 40)
        top = {'code': 'P.104', 'cover': 2, 'side': 'right', 'post': 'p1',
               'sightings': [{'photo': 2, 'x': x0, 'w': w0}]}
        low = {'code': 'R.415a', 'cover': 4, 'side': 'right', 'post': 'p1',    # a rectangle: no range of its own
               'sightings': [{'photo': i, 'x': seen(photos, i, 4, 40)[0]} for i in (0, 4)]}
        alone = [build.locate_sign(s, photos, 15) for s in (top, low)]
        self.assertGreater(math.dist(xy(*alone[0][:2]), xy(*alone[1][:2])), 1.0)
        fixes = build.locate_post([top, low], 'p1', photos, 15, {})
        self.assertEqual(len(fixes), 2)
        self.assertEqual(fixes[0][:2], fixes[1][:2])
        self.assertLess(math.dist(xy(*fixes[0][:2]), (4, 40)), 0.5)
        how = fixes[0][2]
        self.assertTrue(how['method'].startswith('ranged from the sign size (0.70 m) on 1 photo, 3 rays'))
        self.assertIn('one position for the 2 signs on post p1', how['method'])
        self.assertEqual(how['post'], {'id': 'p1', 'mount': 'pole', 'signs': 2})
        self.assertEqual([s['i'] for s in how['sightings']], [2, 0, 4])
        # the same range given twice (both signs, the same width on the same photo) counts once
        low2 = dict(low, code='P.127', sightings=[{'photo': 2, 'x': x0, 'w': w0}])
        self.assertIn('on 1 photo', build.locate_signs([top, low2], photos, 15)[2]['method'])
        # snapped as one onto the layout
        fixes = build.locate_post([top, low], 'p1', photos, 15, {'road': [ROAD]})
        self.assertEqual(fixes[0][:2], fixes[1][:2])
        self.assertTrue(fixes[0][2]['snap']['done'])

    def test_a_gantry_is_a_line_across_the_road(self):
        """Three boards over lanes 1-3, 30 m ahead of photo 0: each read on its own photos, alone they come out
        metres apart along the road; on the gantry they share one along-road position, each over its lane."""
        photos = ride()
        spec = {'road': [ROAD]}
        boards = []
        for lane, east, shots in ((1, 0.0, (0, 1)), (2, -3.2, (0, 2)), (3, -6.4, (1, 3))):
            sights = [{'photo': i, 'x': seen(photos, i, east, 30)[0]} for i in shots]
            sights[0]['x'] += 0.01 * lane                                 # each read a little off
            boards.append({'code': 'R.412', 'cover': shots[-1], 'side': 'over', 'lane': lane, 'post': 'g1',
                           'sightings': sights})
        alone = [build.snap_sign(b, *build.locate_sign(b, photos, 15), photos, spec) for b in boards]
        fixes = build.locate_post(boards, 'g1', photos, 15, spec)
        c = photos[5]
        along = [build.frame_of(la, lo, c)[0] for la, lo, _h in fixes]
        across = [build.frame_of(la, lo, c)[1] for la, lo, _h in fixes]
        self.assertLess(max(along) - min(along), 0.05)
        alone_along = [build.frame_of(la, lo, c)[0] for la, lo, _h in alone]
        self.assertGreater(max(alone_along) - min(alone_along), 0.5)
        for got, want in zip(across, (0.0, -3.2, -6.4)):
            self.assertAlmostEqual(got, want, places=1)
        how = fixes[1][2]
        self.assertEqual((how['post']['id'], how['post']['mount'], how['post']['signs']), ('g1', 'gantry', 3))
        self.assertIn('along the road with the 3 boards of gantry g1', how['method'])
        self.assertIn('alone', how['post'])


class PostsInTheBuild(unittest.TestCase):
    """build.py: post / order reach place.json, result.json (sign block and pin), the GeoJSON and the CSV; signs
    without a post are as before."""

    def setUp(self):
        from PIL import Image
        self.d = tempfile.mkdtemp()
        work, self.out = os.path.join(self.d, 'work'), os.path.join(self.d, 'out', 'ride')
        os.makedirs(work)
        photos = ride(8)
        for p in photos:
            f = os.path.join(work, f"p{p['idx']}.jpg")
            Image.new('RGB', (64, 36), (p['idx'] * 30, 90, 120)).save(f)
            p.update({'id': f"p{p['idx']}", 'file': f, 't': f"2026-11-24T10:00:0{p['idx']}Z", 'speedMps': 5,
                      'roadName': 'Đồng Văn Cống', 'blurry': False, 'sharpness': 10})
        write(os.path.join(work, 'photos.json'), photos)
        write(os.path.join(work, 'session.json'), {'id': 's_p', 'name': 'P', 'kind': 'sweep', 'target': 'signs'})
        x, w = seen(photos, 2, 4, 40)
        sights = [{'photo': 2, 'x': x, 'w': w}, {'photo': 4, 'x': seen(photos, 4, 4, 40)[0]}]
        write(os.path.join(work, 'places.json'), {
            'street': 'Đồng Văn Cống', 'places': [], 'road': [ROAD],
            'signs': [{'code': 'P.127', 'label': 'Tốc độ tối đa cho phép', 'text': '50', 'photos': [0, 5], 'cover': 4,
                       'sightings': sights, 'side': 'right', 'confidence': 'high', 'post': 'p1', 'order': 2},
                      {'code': 'P.104', 'label': 'Cấm xe máy', 'photos': [0, 5], 'cover': 4, 'sightings': sights,
                       'side': 'right', 'confidence': 'high', 'post': 'p1', 'order': 1},
                      {'code': 'P.130', 'label': 'Cấm dừng xe và đỗ xe', 'photos': [0, 5], 'cover': 4,
                       'sightings': [{'photo': 4, 'x': 0.7}], 'side': 'right', 'confidence': 'high'}]})
        self.work = work

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_exports(self):
        with contextlib.redirect_stdout(io.StringIO()):
            build.build(self.work, self.out)
        res = read(os.path.join(self.out, 'result.json'))
        by = {p['name']: p for p in res['places']}
        top, low, own = by['Cấm xe máy'], by['Tốc độ tối đa cho phép'], by['Cấm dừng xe và đỗ xe']
        self.assertEqual(top['sign'], {'code': 'P.104', 'group': 'prohibitory', 'facingDeg': 0, 'side': 'right',
                                       'post': 'p1', 'order': 1})
        self.assertEqual((low['sign']['post'], low['sign']['order']), ('p1', 2))
        self.assertEqual(own['sign'], {'code': 'P.130', 'group': 'prohibitory', 'facingDeg': 0, 'side': 'right'})
        a, b = top['pins']['algorithm'], low['pins']['algorithm']
        self.assertEqual((a['lat'], a['lon']), (b['lat'], b['lon']))
        self.assertEqual(a['post'], {'id': 'p1', 'mount': 'pole', 'signs': 2})
        self.assertNotIn('post', own['pins']['algorithm'])
        pj = read(os.path.join(self.out, top['folder'], 'place.json'))['place']
        self.assertEqual((pj['sign']['post'], pj['position']['post']['id']), ('p1', 'p1'))
        props = {f['properties']['name']: f['properties'] for f in read(os.path.join(self.out, 'places.geojson'))['features']}
        self.assertEqual((props['Cấm xe máy']['signPost'], props['Cấm xe máy']['signOrder']), ('p1', 1))
        self.assertNotIn('signPost', props['Cấm dừng xe và đỗ xe'])
        with open(os.path.join(self.out, 'places.csv'), encoding='utf-8-sig') as f:
            rows = f.read().splitlines()
        self.assertTrue(rows[0].endswith(',sign post,sign order,level,unit,building'))
        self.assertTrue(any(',Cấm xe máy,' in r and r.endswith(',p1,1,,,') for r in rows))
        self.assertTrue(any(',Cấm dừng xe và đỗ xe,' in r and r.endswith(',,,,,') for r in rows))


class PostsInMergedParts(unittest.TestCase):

    def test_two_readers_posts_stay_apart_and_join_where_merged(self):
        p1 = {'signs': [{'code': 'P.104', 'photos': [80, 86], 'cover': 86, 'post': 'p1', 'order': 1},
                        {'code': 'P.127', 'photos': [80, 86], 'cover': 86, 'post': 'p1', 'order': 2},
                        {'code': 'P.130', 'photos': [10, 12], 'cover': 11, 'post': 'p2'}]}
        p2 = {'signs': [{'code': 'P.104', 'photos': [84, 88], 'cover': 88, 'post': 'p1', 'order': 1},
                        {'code': 'P.127', 'photos': [84, 88], 'cover': 88, 'post': 'p1', 'order': 2},
                        {'code': 'P.130', 'photos': [150, 152], 'cover': 151, 'post': 'p2'}]}
        out, _report = merge_places.merge([p1, p2])
        posts = [(s['code'], s['photos'][0], s['post']) for s in out['signs']]
        self.assertEqual(posts, [('P.130', 10, '1.p2'), ('P.104', 80, '1.p1'), ('P.127', 80, '1.p1'),
                                 ('P.130', 150, '2.p2')])
        self.assertEqual([s['post'] for s in merge_places.merge([p1])[0]['signs']], ['p2', 'p1', 'p1'])   # as written


class ShopAddressInGoogleCheck(unittest.TestCase):
    """An address entry that names its shop (placeRef): its sure house number counts in the shop's Google check."""

    G = {'name': 'Bún Bò Huế Hương Cố Đô', 'nameMatch': 1.0, 'distanceM': 12,
         'address': '106 Nguyễn Thanh Sơn, Thạnh Mỹ Lợi'}

    def f(self, name, house=None):
        out = {'name': {'value': name, 'photo': 1, 'sure': 'high'}}
        if house:
            out['houseNumber'] = {'value': house, 'photo': 1, 'sure': 'high'}
        return out

    def test_a_different_number_refuses(self):
        ok, why = result.google_check(self.G, self.f('Bún Bò Huế Hương Cố Đô'), ['108'])
        self.assertFalse(ok)
        self.assertIn("house number differs: the shop's address entry 108, Google 106", why)
        self.assertTrue(result.google_check(self.G, self.f('Bún Bò Huế Hương Cố Đô'), ['106/2'])[0])
        self.assertTrue(result.google_check(self.G, self.f('Bún Bò Huế Hương Cố Đô'), ['108', '106'])[0])   # a corner
        self.assertTrue(result.google_check(self.G, self.f('Bún Bò Huế Hương Cố Đô'), [])[0])
        self.assertTrue(result.google_check({**self.G, 'thisPlace': True}, self.f('X'), ['108'])[0])
        # Google gives no number: nothing to compare
        self.assertTrue(result.google_check({**self.G, 'address': 'Nguyễn Thanh Sơn'}, self.f('Bún Bò Huế Hương Cố Đô'),
                                                ['108'])[0])
        self.assertEqual(result.merge_google(self.f('B'), {**self.G, 'name': 'B'}, ['108']), self.f('B'))

    def test_the_same_number_supports_a_weak_name(self):
        weak = {**self.G, 'nameMatch': 0.3}
        self.assertFalse(result.google_check(weak, self.f('Bún Bò Cô Ba'))[0])
        ok, why = result.google_check(weak, self.f('Bún Bò Cô Ba'), ['106'])
        self.assertTrue(ok)
        self.assertEqual(why, "same house number as the shop's address entry (name match only 0.3)")
        ok, why = result.google_check(weak, self.f('Bún Bò Cô Ba', house='106'))     # on the photos: the same
        self.assertEqual((ok, why), (True, 'same house number (name match only 0.3)'))
        ok, why = result.google_check({**weak, 'nameMatch': 0.1}, self.f('Bún Bò Cô Ba'), ['106'])
        self.assertFalse(ok)
        self.assertIn('with the same house number it needs 0.25', why)
        # a generic name ("Phở") is supported by the address entry's number too
        ok, why = result.google_check({**self.G, 'name': 'Phở'}, self.f('Phở'), ['106'])
        self.assertTrue(ok)
        self.assertFalse(result.google_check({**self.G, 'name': 'Phở'}, self.f('Phở'))[0])

    def test_only_sure_numbers(self):
        hn = lambda v, sure='high': {'houseNumber': {'value': v, 'sure': sure}}
        nums = result.shop_numbers([
            {'placeRef': 'Bún Bò Huế Hương Cố Đô', 'fields': hn('106'), 'confidence': 'high'},
            {'placeRef': 'bun bo hue huong co do', 'fields': hn('106'), 'confidence': 'high'},
            {'placeRef': 'Phở Liên', 'fields': hn('1…', 'low'), 'confidence': 'low', 'numbering': {'partial': True}},
            {'placeRef': 'Phở Liên', 'fields': hn('12'), 'confidence': 'low'},
            {'placeRef': 'Tạp Hóa', 'fields': hn('7?', 'medium'), 'confidence': 'medium'},
            {'fields': hn('9'), 'confidence': 'high'}])
        self.assertEqual(nums, {'bun bo hue huong co do': ['106']})
        self.assertEqual(result.ref_key('Bún Bò Huế  Hương Cố Đô!'), 'bun bo hue huong co do')

    def test_through_build_and_result(self):
        """build.py writes place.addressNumbers for the shop; result.json's Google check uses it and says so."""
        from PIL import Image
        d = tempfile.mkdtemp()
        try:
            work, out = os.path.join(d, 'work'), os.path.join(d, 'out', 'ride')
            os.makedirs(work)
            photos = ride(4)
            for p in photos:
                f = os.path.join(work, f"p{p['idx']}.jpg")
                Image.new('RGB', (64, 36), (90, 90, 120)).save(f)
                p.update({'id': f"p{p['idx']}", 'file': f, 't': f"2026-11-24T10:00:0{p['idx']}Z", 'speedMps': 5,
                          'roadName': 'Nguyễn Thanh Sơn', 'blurry': False, 'sharpness': 10, 'cameraBearing': 90,
                          'cameraBearingSource': 'gyroCompass'})
            write(os.path.join(work, 'photos.json'), photos)
            write(os.path.join(work, 'session.json'), {'id': 's_a', 'name': 'A', 'kind': 'sweep', 'target': 'addresses'})
            write(os.path.join(work, 'places.json'), {
                'street': 'Nguyễn Thanh Sơn', 'defaultFacadeDistanceM': 10,
                'places': [{'name': 'Bún Bò Cô Ba', 'photos': [0, 2], 'cover': 1, 'sightings': [{'photo': 1, 'x': 0.5}]}],
                'addresses': [{'houseNumber': '108', 'photos': [1, 2], 'cover': 2, 'sightings': [{'photo': 2, 'x': 0.4}],
                               'confidence': 'high', 'placeRef': 'Bún Bò Cô Ba'}]})
            with contextlib.redirect_stdout(io.StringIO()):
                build.build(work, out)
            f = os.path.join(out, '01_Bún_Bò_Cô_Ba', 'place.json')
            m = read(f)
            self.assertEqual(m['place']['addressNumbers'], ['108'])
            m['googleMaps'] = {**self.G, 'name': 'Bún Bò Cô Ba', 'status': 'found'}
            write(f, m)
            res = read(result.write(out))
            g = res['places'][0]['google']['merge']
            self.assertEqual((g['merged'], g['addressNumbers']), (False, ['108']))
            self.assertIn("the shop's address entry 108", g['why'])
            self.assertNotIn('google', res['places'][0]['fields'].get('houseNumber', {}))
            self.assertTrue(any(t['type'] == 'visit' for t in res['tasks']))
        finally:
            shutil.rmtree(d)


if __name__ == '__main__':
    unittest.main()
