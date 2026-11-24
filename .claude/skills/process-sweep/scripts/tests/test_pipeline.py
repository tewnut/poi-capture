"""Unit tests for the sweep processing scripts (stdlib unittest; Pillow needed for the build test).

    python -m unittest discover -s .claude/skills/process-sweep/scripts/tests -v
"""
import json, os, shutil, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import build  # noqa: E402
import enrich_browser  # noqa: E402
import enrich_web  # noqa: E402
import paths  # noqa: E402
import prepare  # noqa: E402
import result  # noqa: E402


def read(path, as_json=True):
    with open(path, encoding='utf-8') as f:
        return json.load(f) if as_json else f.read()


def write(path, data):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False))


class AddressParsing(unittest.TestCase):
    """Item 3: the street from a Google address whatever comes before it."""

    def check(self, addr, house, street):
        self.assertEqual(result.parse_address(addr), (house, street), addr)

    def test_plain(self):
        self.check('37 Tạ Hiện, P, Cát Lái, Hồ Chí Minh, Việt Nam', '37', 'Tạ Hiện')
        self.check('12A Tạ Hiện, P, Cát Lái, Hồ Chí Minh 700000, Việt Nam', '12A', 'Tạ Hiện')

    def test_building_before_the_street(self):
        self.check('tòa nhà One Verandah, 2 Bát Nàn, Cát Lái, Hồ Chí Minh 700000, Việt Nam', '2', 'Bát Nàn')
        self.check('SH 06 One Verandah, 02 Bát Nàn, Cát Lái, Hồ Chí Minh 71114, Việt Nam', '02', 'Bát Nàn')
        self.check('SH05, KĐT Căn hộ cao cấp dự án One Verandah, 2 Bát Nàn, Cát Lái', '2', 'Bát Nàn')
        self.check('Khu Thương Mại Feliz En Vista, 1 Phan Văn Đáng, Cát Lái', '1', 'Phan Văn Đáng')
        self.check('QQG2+XR Khu dân cư DA cty Hà Đô, 13 Tạ Hiện, P, Cát Lái', '13', 'Tạ Hiện')

    def test_street_word(self):
        self.check('Đ. Tạ Hiện, Cát Lái, Hồ Chí Minh', None, 'Tạ Hiện')
        self.check('Đ. Số 3, An Khánh, Thủ Đức', None, 'Đường Số 3')
        self.check('1 Đường 59, Cát Lái, Hồ Chí Minh', '1', 'Đường 59')
        self.check('133 -135 đường Trương Văn Bang, Cát Lái', '133-135', 'Trương Văn Bang')

    def test_where_the_name_ends(self):
        self.check('Chung cư Bộ Công An, Đ. Số 3/50 Lô A, Khu Phố 4, An Khánh', None, 'Đường Số 3')
        self.check('Đ. Số 3/48 P. Bình An, Quận 2, An Khánh', None, 'Đường Số 3')
        self.check('6B Đ. D2, P, An Khánh, Hồ Chí Minh', '6B', 'Đường D2')
        self.check('9/9 Số 9, Khu Phố 4, An Khánh', '9/9', 'Đường Số 9')
        self.check('B2 Đ. Số 3, Khu Phố 4, An Khánh', 'B2', 'Đường Số 3')
        self.check('83/20 Vành Đai Tây. phường An Khánh ( Q2 Cũ, HoChiMInh', '83/20', 'Vành Đai Tây')
        self.check('97 đường số 3 phường An Khánh quận 2 thủ đức, 97 Đ. Số 3', '97', 'Đường số 3')
        self.check('Q2 Cũ, Hồ Chí Minh', None, None)

    def test_no_street(self):
        self.check('Cát Lái, Hồ Chí Minh 700000, Việt Nam', None, None)
        self.check('Shop House, Chung cư One Verandah, Tạ Hiện, Thủ Đức', None, None)
        self.check('', None, None)

    def test_google_fields(self):
        g = {'address': 'tòa nhà One Verandah, 2 Bát Nàn, Cát Lái', 'phone': '+84 90 452 3417'}
        self.assertEqual(result._google_fields(g), {'phone': '0904523417', 'houseNumber': '2', 'street': 'Bát Nàn'})


class Comparisons(unittest.TestCase):
    """Item 13."""

    def test_phone_misread_digit_is_clarified(self):
        f = {'phone': {'value': '0904521417', 'photo': 5, 'sure': 'low'}}
        out = result.merge_source(f, {'phone': '0904523417'}, 'google')
        self.assertTrue(out['phone'].get('clarified'))
        self.assertEqual(out['phone']['value'], '0904523417')
        self.assertEqual(out['phone']['photoValue'], '0904521417')

    def test_phone_sure_reading_still_differs(self):
        f = {'phone': {'value': '0904521417', 'photo': 5, 'sure': 'high'}}
        out = result.merge_source(f, {'phone': '0904523417'}, 'google')
        self.assertEqual(out['phone']['google'], 'differs')

    def test_phone_unrelated_is_not_clarified(self):
        self.assertFalse(result._compatible('phone', '0904521417', '0281234567'))
        self.assertTrue(result._compatible('phone', '0901 457 …', '0901457801'))

    def test_long_street_reading_matches_short_google(self):
        self.assertTrue(result._same('street', 'Đường Số 3, P. An Khánh, TP. Thủ Đức', 'Đ. Số 3'))
        self.assertTrue(result._same('street', 'Tạ Hiện, Phường Cát Lái', 'Tạ Hiện'))
        self.assertTrue(result._same('street', 'Đường 3 - P. An Khánh - TP. Thủ Đức', 'Đường Số 3'))
        self.assertTrue(result._same('street', '…Số 3, KP. 4, P. Bình An', 'Đ. Số 3'))
        self.assertFalse(result._same('street', 'Đường Số 3', 'Đường Số 13'))
        self.assertFalse(result._same('street', 'Tạ Hiện', 'Bát Nàn'))
        f = {'street': {'value': 'Đường Số 3, P. An Khánh, TP. Thủ Đức', 'photo': 3, 'sure': 'high'}}
        g = {'name': 'X', 'nameMatch': 1.0, 'distanceM': 10, 'address': 'Đ. Số 3, An Khánh, Thủ Đức'}
        self.assertEqual(result.merge_google(f, g)['street']['google'], 'match')


class Google(unittest.TestCase):
    """Items 6 and 7: only a result close and alike enough to merge counts."""
    near = {'status': 'found', 'name': 'Phở Vụ', 'nameMatch': 1.0, 'distanceM': 6, 'website': 'https://phovu.vn'}
    far = {'status': 'found', 'name': 'Phở Vụ Hà Nội', 'nameMatch': 1.0, 'distanceM': 1134000, 'website': 'https://x.vn'}

    def test_mergeable(self):
        self.assertTrue(result.google_mergeable(self.near))
        self.assertTrue(result.google_mergeable({**self.near, 'distanceM': 0}))
        self.assertFalse(result.google_mergeable(self.far))
        self.assertFalse(result.google_mergeable({**self.near, 'distanceM': 101}))
        self.assertFalse(result.google_mergeable({**self.near, 'nameMatch': 0.4}))
        self.assertFalse(result.google_mergeable({'status': 'not found nearby'}))

    def test_far_google_website_not_used(self):
        self.assertEqual(enrich_web.website_of({'googleMaps': self.near}), 'https://phovu.vn')
        self.assertIsNone(enrich_web.website_of({'googleMaps': self.far}))
        self.assertIsNone(enrich_web.website_of({'googleMaps': {**self.near, 'nameMatch': 0.3}}))
        self.assertEqual(enrich_web.website_of({'googleMaps': self.far, 'fields': {'website': {'value': 'own.vn'}}}), 'own.vn')

    def test_site_read_earlier_from_far_google_is_ignored(self):
        w = {'status': 'ok', 'url': 'https://www.x.vn/', 'address': '1 Tạ Hiện', 'fields': {'phone': '0901111111'}}
        self.assertEqual(result._web_fields(w, {'street': {'value': 'Tạ Hiện'}}, self.far), {})

    def places(self, g):
        return [{'id': 's-01', 'name': 'Phở Vụ', 'status': 'open', 'confidence': 'high', 'photos': [],
                 'pins': {'algorithm': {'lat': 10.0, 'lon': 106.0}}, 'google': g}]

    def test_far_match_gets_a_visit_task(self):
        t = result.tasks_for(self.places(self.far), [])
        self.assertEqual(len(t), 1)
        self.assertIn('not on Google Maps nearby', t[0]['why'])
        self.assertEqual(result.tasks_for(self.places(self.near), []), [])
        self.assertEqual(len(result.tasks_for(self.places({'status': 'not found nearby'}), [])), 1)
        self.assertEqual(result.tasks_for(self.places({'status': 'error: timeout'}), []), [])

    def test_far_match_not_merged(self):
        f = {'name': {'value': 'Phở Vụ'}}
        self.assertEqual(result.merge_google(f, {**self.far, 'phone': '0901111111'}), f)


class BrowserRecheck(unittest.TestCase):
    """Item 5: the detail page's position is checked against the 300 m limit again."""

    def test_far_on_its_own_page_is_rejected(self):
        pick = {'name': 'Phở Vụ', 'distanceM': 40, 'nameMatch': 1.0}
        cands = [dict(pick)]
        det = {'lat': 21.03, 'lon': 105.85, 'address': 'Hà Nội'}            # ~1,100 km north
        self.assertIsNone(enrich_browser.checked(pick, det, 10.78, 106.77, cands))
        self.assertGreater(cands[0]['distanceM'], 1_000_000)
        self.assertIn('rejected', cands[0]['summary'])

    def test_near_is_kept_with_the_page_distance(self):
        pick = {'name': 'Phở Vụ', 'distanceM': 40, 'nameMatch': 1.0}
        g = enrich_browser.checked(pick, {'lat': 10.7801, 'lon': 106.77, 'phone': '090'}, 10.78, 106.77)
        self.assertEqual(g['distanceM'], 11)
        self.assertEqual(g['phone'], '090')

    def test_session_name(self):
        a = enrich_browser.session_name('C:/x/Tạ Hiện [261116]')
        self.assertRegex(a, r'^poi-ta-hien-261116-[0-9a-f]{6}$')
        self.assertNotEqual(a, enrich_browser.session_name('C:/y/Tạ Hiện [261116]'))


class Paths(unittest.TestCase):
    """Items 9 and 12."""

    def test_msys_path(self):
        self.assertEqual(paths.arg_path('/c/Users/x/Tạ Hiện [261116]', windows=True), 'C:/Users/x/Tạ Hiện [261116]')
        self.assertEqual(paths.arg_path('/d', windows=True), 'D:/')
        self.assertEqual(paths.arg_path('C:/Users/x', windows=True), 'C:/Users/x')
        self.assertEqual(paths.arg_path('rel/dir', windows=True), 'rel/dir')
        self.assertEqual(paths.arg_path('/c/Users/x', windows=False), '/c/Users/x')
        with self.assertRaises(SystemExit):
            paths.arg_path('/tmp/work', windows=True)

    def test_numeric_order(self):
        d = tempfile.mkdtemp()
        try:
            for n in ('01_a', '09_b', '10_c', '100_d', '11_e', '99_f', 'notes'):
                os.makedirs(os.path.join(d, n))
                if n != 'notes':
                    write(os.path.join(d, n, 'place.json'), '{}')
            self.assertEqual(paths.place_folders(d), ['01_a', '09_b', '10_c', '11_e', '99_f', '100_d'])
            self.assertEqual(paths.folder_no('104_Highlands_Coffee'), '104')
            self.assertEqual(paths.folder_no('07_Phở_Vụ'), '07')
        finally:
            shutil.rmtree(d)


class Roads(unittest.TestCase):
    """Items 1 and 2: the place's own road, and the road as a street suggestion."""

    def test_pick_road(self):
        self.assertEqual(result.pick_road('Tạ Hiện', ['Trương Văn Bang'], 'Trương Văn Bang / Tạ Hiện'), ('Tạ Hiện', 'cover photo'))
        self.assertEqual(result.pick_road(None, ['A', 'B', 'B', None], 'X'), ('B', 'photos'))
        self.assertEqual(result.pick_road('Unnamed road (residential)', ['Unnamed road (residential)', 'Đường Số 3'], None),
                         ('Đường Số 3', 'photos'))
        self.assertEqual(result.pick_road(None, [], 'Vành Đai Tây'), ('Vành Đai Tây', 'ride'))
        self.assertEqual(result.pick_road(None, [], 'Trương Văn Bang / Tạ Hiện'), (None, None))

    def test_ride_street_only_when_nobody_gives_one(self):
        out = result.add_ride_street({'name': {'value': 'X'}}, 'Tạ Hiện')
        self.assertEqual(out['street'], {'value': 'Tạ Hiện', 'source': 'ride', 'sure': 'medium'})
        photo = {'street': {'value': 'Nguyễn Văn Kỉnh, P. Thạnh Mỹ Lợi', 'photo': 3, 'sure': 'high'}}
        out = result.add_ride_street(photo, 'Tạ Hiện')
        self.assertEqual(out['street']['value'], 'Nguyễn Văn Kỉnh, P. Thạnh Mỹ Lợi')
        self.assertEqual((out['street']['ride'], out['street']['rideValue']), ('differs', 'Tạ Hiện'))
        g = {'street': {'value': 'Bát Nàn', 'source': 'google', 'sure': 'medium'}}
        self.assertEqual(result.add_ride_street(g, 'Tạ Hiện')['street']['value'], 'Bát Nàn')
        self.assertEqual(result.add_ride_street({}, None), {})

    def test_place_road_from_old_place_json(self):
        m = {'place': {'street': 'Trương Văn Bang / Tạ Hiện'},
             'photos': [{'cover': False, 'roadName': 'Trương Văn Bang'}, {'cover': True, 'roadName': 'Tạ Hiện'}]}
        self.assertEqual(result.place_road(m), 'Tạ Hiện')
        self.assertEqual(result.place_road({'place': {'street': 'Tạ Hiện', 'streetFrom': 'photos'}}), 'Tạ Hiện')


class Bearing(unittest.TestCase):
    """Item 8: bearingOffsetDeg applies to compass-measured bearings too."""

    def tearDown(self):
        build.BEARING_OFFSET, build.BEARING_ALL = 0.0, False

    def test_set_offset_applies_to_compass(self):
        off, note = build.bearing_correction([], {'bearingOffsetDeg': -6})
        build.BEARING_OFFSET, build.BEARING_ALL = off, True
        self.assertAlmostEqual(build.bearing_of({'cameraBearing': 90, 'cameraBearingSource': 'gyroCompass'}, 0.5), 84)
        self.assertIn('compass', note)

    def test_automatic_offset_only_for_assumed(self):
        build.BEARING_OFFSET, build.BEARING_ALL = 10.0, False
        self.assertAlmostEqual(build.bearing_of({'cameraBearing': 90, 'cameraBearingSource': 'compass'}, 0.5), 90)
        self.assertAlmostEqual(build.bearing_of({'cameraBearing': 90, 'cameraBearingSource': 'assumed'}, 0.5), 100)
        self.assertAlmostEqual(build.bearing_of({'cameraBearing': 90, 'cameraBearingSource': 'assumed+neighbour'}, 0.5), 100)


class Sheets(unittest.TestCase):
    """Item 11."""

    def test_landscape_cells_for_landscape_photos(self):
        boxes, (W, H) = prepare.sheet_layout([(3264, 1840), (1840, 3264), (3264, 1840), (3264, 1840)])
        self.assertEqual(boxes[0][2:], (1000, 564))
        self.assertEqual(boxes[1][2:], (564, 1000))
        self.assertEqual(boxes[3][1], boxes[2][1])            # the last two landscapes share one 564-high row
        self.assertLessEqual(H, 1000 + 564)
        self.assertLessEqual(W, 2000)

    def test_one_orientation_unchanged(self):
        boxes, size = prepare.sheet_layout([(3264, 1840)] * 4)
        self.assertEqual(size, (2000, 1128))
        boxes, size = prepare.sheet_layout([(1840, 3264)] * 4)
        self.assertEqual(boxes[1], (1000, 0, 1000, 1774))


class BuildRide(unittest.TestCase):
    """build.py on a tiny made-up ride: each place gets the road of its own photos, the ride street is a last
    fallback, the road becomes a street suggestion in result.json, and 100+ places list with full numbers."""

    def setUp(self):
        from PIL import Image
        self.d = tempfile.mkdtemp()
        work, self.out = os.path.join(self.d, 'work'), os.path.join(self.d, 'out', 'ride')
        os.makedirs(work)
        photos = []
        for i in range(6):
            f = os.path.join(work, f'p{i}.jpg')
            Image.new('RGB', (64, 36), (i * 30, 90, 120)).save(f)
            photos.append({'idx': i, 'id': f'p{i}', 'file': f, 't': f'2026-11-16T10:00:0{i}Z',
                           'lat': 10.78 + i * 0.00005, 'lon': 106.77, 'accuracyM': 4, 'cameraBearing': 90,
                           'cameraBearingSource': 'gyroCompass', 'hFovDeg': 69, 'width': 64, 'height': 36,
                           'speedMps': 5, 'roadName': 'Trương Văn Bang' if i < 3 else ('Tạ Hiện' if i < 5 else None),
                           'blurry': False, 'sharpness': 10, 'travelBearing': 0})
        write(os.path.join(work, 'photos.json'), photos)
        write(os.path.join(work, 'session.json'), {'id': 's_test', 'name': 'Test', 'kind': 'sweep'})
        places = [
            {'name': 'A', 'photos': [0, 2], 'cover': 1, 'sightings': [{'photo': 1, 'x': 0.5}]},
            {'name': 'B', 'photos': [3, 4], 'cover': 4, 'sightings': [{'photo': 4, 'x': 0.5}],
             'fields': {'street': {'value': 'Tạ Hiện, Phường Cát Lái', 'photo': 4, 'sure': 'low'}}},
            {'name': 'C', 'photos': [5, 5], 'cover': 5, 'sightings': [{'photo': 5, 'x': 0.5}]},
        ] + [{'name': f'P{n}', 'photos': [3, 4], 'cover': 3, 'sightings': [{'photo': 3, 'x': 0.5}]} for n in range(4, 102)]
        write(os.path.join(work, 'places.json'),
              {'street': 'Trương Văn Bang / Tạ Hiện', 'defaultFacadeDistanceM': 10, 'places': places})
        self.work = work

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_build(self):
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()):
            build.build(self.work, self.out)
        pj = lambda d: read(os.path.join(self.out, d, 'place.json'))['place']
        self.assertEqual((pj('01_A')['street'], pj('01_A')['streetFrom']), ('Trương Văn Bang', 'cover photo'))
        self.assertEqual(pj('02_B')['street'], 'Tạ Hiện')
        self.assertEqual(pj('03_C')['street'], None)          # no road on its photo; the ride names two: none
        res = read(os.path.join(self.out, 'result.json'))
        by = {p['name']: p for p in res['places']}
        self.assertEqual(by['A']['fields']['street'], {'value': 'Trương Văn Bang', 'source': 'ride', 'sure': 'medium'})
        self.assertEqual(by['B']['fields']['street']['value'], 'Tạ Hiện, Phường Cát Lái')   # the reading stays
        self.assertEqual(by['B']['fields']['street']['ride'], 'match')
        self.assertNotIn('street', by['C']['fields'])
        ids = [p['id'] for p in res['places']]
        self.assertEqual(len(ids), len(set(ids)))              # 100+ places: no id cut to 2 digits
        self.assertEqual(ids[-1], 's_test-101')
        readme = read(os.path.join(self.out, 'README.txt'), as_json=False).splitlines()
        nums = [l.split()[0] for l in readme if l[:3].strip().isdigit()]
        self.assertEqual(nums[:3], ['01', '02', '03'])
        self.assertEqual(nums[-3:], ['99', '100', '101'])


class Targets(unittest.TestCase):
    """docs/sweep-targets.md: addresses and signs from places.json become result.json entries of their own layer;
    the ride's target travels to result.json and the upload metadata; enrichment leaves them out."""

    def setUp(self):
        from PIL import Image
        self.d = tempfile.mkdtemp()
        work, self.out = os.path.join(self.d, 'work'), os.path.join(self.d, 'out', 'ride')
        os.makedirs(work)
        # riding north, ~5.5 m between photos; a sign 4 m right of the line, 40 m ahead of photo 0
        lat0, lon0 = 10.78, 106.77
        self.sign_ll = build.to_ll(4, 40, lat0, lon0)
        photos = []
        for i in range(6):
            f = os.path.join(work, f'p{i}.jpg')
            Image.new('RGB', (64, 36), (i * 30, 90, 120)).save(f)
            photos.append({'idx': i, 'id': f'p{i}', 'file': f, 't': f'2026-11-16T10:00:0{i}Z',
                           'lat': lat0 + i * 0.00005, 'lon': lon0, 'accuracyM': 4, 'cameraBearing': 90,
                           'cameraBearingSource': 'assumed', 'hFovDeg': 69, 'width': 64, 'height': 36,
                           'speedMps': 5, 'roadName': 'Trương Văn Bang', 'blurry': False, 'sharpness': 10,
                           'travelBearing': 0})
        fx = 0.5 / build.math.tan(build.math.radians(69 / 2))

        def x_at(i):
            _x, y = build.to_xy(photos[i]['lat'], lon0, lat0, lon0)
            return 0.5 + fx * 4 / (40 - y)
        write(os.path.join(work, 'photos.json'), photos)
        write(os.path.join(work, 'session.json'), {'id': 's_t', 'name': 'T', 'kind': 'sweep', 'target': 'signs'})
        write(os.path.join(work, 'places.json'), {
            'street': 'Trương Văn Bang', 'defaultFacadeDistanceM': 10, 'bearingOffsetDeg': 25,
            'places': [{'name': 'A', 'photos': [0, 2], 'cover': 1, 'sightings': [{'photo': 1, 'x': 0.5}]}],
            'addresses': [
                {'houseNumber': '106', 'photos': [1, 2], 'cover': 2, 'sightings': [{'photo': 2, 'x': 0.4}],
                 'confidence': 'high', 'placeRef': 'A', 'agentPin': {'snap': 'facade', 'why': 'on the facade'}},
                {'houseNumber': '12/3', 'street': 'Nguyễn Thanh Sơn', 'unit': 'Hẻm 12', 'photos': [3, 3], 'cover': 3,
                 'sightings': [{'photo': 3, 'x': 0.5}], 'confidence': 'low'}],
            'signs': [{'code': 'P.131a', 'label': 'Cấm đỗ xe', 'extra': '6h-9h', 'photos': [0, 5], 'cover': 4,
                       'sightings': [{'photo': i, 'x': x_at(i)} for i in (0, 4, 5)], 'side': 'right',
                       'confidence': 'high', 'agentPin': {'snap': 'facade'}}]})
        self.work = work

    def tearDown(self):
        shutil.rmtree(self.d)
        build.BEARING_OFFSET, build.BEARING_ALL = 0.0, False

    def run_build(self):
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()):
            build.build(self.work, self.out)
        return read(os.path.join(self.out, 'result.json'))

    def test_layers_in_result(self):
        res = self.run_build()
        self.assertEqual(res['session']['target'], 'signs')
        self.assertEqual(read(os.path.join(self.out, 'sweep.json'))['session']['target'], 'signs')
        by = {p['name']: p for p in res['places']}
        self.assertEqual(by['A']['layer'], 'place')
        a = by['106 Trương Văn Bang']                         # no street on the plate: the ride's road
        self.assertEqual((a['layer'], a['category'], a['placeRef']), ('address', 'address', 'A'))
        self.assertEqual(a['fields']['houseNumber']['value'], '106')
        self.assertEqual(a['fields']['street'], {'value': 'Trương Văn Bang', 'source': 'ride', 'sure': 'medium'})
        self.assertNotIn('name', a['fields'])
        self.assertIsNotNone(a['pins']['agent'])               # an address may be snapped to the facade
        b = by['12/3 Nguyễn Thanh Sơn']
        self.assertEqual(b['fields']['street']['value'], 'Nguyễn Thanh Sơn')
        self.assertEqual(b['fields']['other']['value'], 'Hẻm 12')
        s = by['Cấm đỗ xe']
        self.assertEqual((s['layer'], s['category']), ('sign', 'traffic_sign'))
        self.assertEqual(s['sign'], {'code': 'P.131a', 'group': 'prohibitory', 'facingDeg': 0, 'side': 'right'})
        self.assertEqual(s['fields']['signCode']['value'], 'P.131a')
        self.assertEqual(s['fields']['other']['value'], '6h-9h')
        self.assertNotIn('street', s['fields'])
        self.assertIsNone(s['pins']['agent'])                  # no facade snap for a sign
        self.assertTrue(s['photos'] and all(x['file'].startswith(s['folder'] + '/') for x in s['photos']))
        for x in s['photos']:
            self.assertTrue(os.path.isfile(os.path.join(self.out, x['file'])))
        # located from the forward view (travel bearing), the compass offset not applied
        pin = s['pins']['algorithm']
        self.assertIn('triangulated', pin['method'])
        err = build.math.hypot(*build.to_xy(pin['lat'], pin['lon'], *self.sign_ll))
        self.assertLess(err, 1.0)
        # tasks only for businesses
        self.assertTrue(all(t['place'] in (None, by['A']['id']) for t in res['tasks']))

    def test_geojson_and_csv(self):
        self.run_build()
        g = read(os.path.join(self.out, 'places.geojson'))
        props = {f['properties']['name']: f['properties'] for f in g['features']}
        self.assertEqual(props['A']['layer'], 'place')
        self.assertEqual(props['106 Trương Văn Bang']['layer'], 'address')
        self.assertEqual((props['Cấm đỗ xe']['layer'], props['Cấm đỗ xe']['signCode']), ('sign', 'P.131a'))
        rows = read(os.path.join(self.out, 'places.csv'), as_json=False).lstrip('﻿').splitlines()
        self.assertTrue(rows[0].startswith('#,layer,name,category,signCode,'))
        self.assertTrue(any(',sign,Cấm đỗ xe,traffic_sign,P.131a,' in r for r in rows))

    def test_enrichment_skips_addresses_and_signs(self):
        self.run_build()
        everything = paths.place_folders(self.out)
        self.assertEqual(len(everything), 4)
        self.assertEqual(paths.business_folders(self.out), ['01_A'])

    def test_target_default_and_cloud_metadata(self):
        import cloud, zipfile
        self.assertIn('FORWARD', prepare.target_line({'target': 'signs'}))
        self.assertTrue(prepare.target_line({}).startswith('target: shops'))
        res = self.run_build()
        z = os.path.join(self.d, 'r.zip')
        with zipfile.ZipFile(z, 'w') as f:
            f.writestr('ride/result.json', json.dumps(res))
        meta = cloud.push_metadata(cloud.read_session(z), 'r.zip', 'processed-result', None, None)
        self.assertEqual((meta['target'], meta['sessionId'], meta['kind']), ('signs', 's_t', 'sweep'))
        self.assertEqual(cloud.decode_metadata(cloud.encode_metadata(meta))['target'], 'signs')
        bare = cloud.push_metadata({}, 'r.zip', 'processed-result', 'x', None)
        self.assertNotIn('target', cloud.decode_metadata(cloud.encode_metadata(bare)))

    def test_old_ride_is_shops(self):
        s = read(os.path.join(self.work, 'session.json'))
        del s['target']
        write(os.path.join(self.work, 'session.json'), s)
        self.assertEqual(self.run_build()['session']['target'], 'shops')


class SignPositions(unittest.TestCase):
    """Traffic signs (docs/sweep-targets.md section 4): the phone's boxes in signHints, a range from the sign's
    standard size, the road layout and the snap across the road onto it."""

    HF = 69.1
    LAT0, LON0 = 10.78, 106.77

    def ride(self, n=8, step=5.5):
        """Riding north from (LAT0, LON0), a forward photo every `step` m."""
        out = []
        for i in range(n):
            lat, lon = build.to_ll(0, i * step, self.LAT0, self.LON0)
            out.append({'idx': i, 'lat': lat, 'lon': lon, 'accuracyM': 3, 'cameraBearing': 0, 'travelBearing': 0,
                        'cameraBearingSource': 'forward', 'hFovDeg': self.HF, 'width': 3264, 'height': 1840,
                        'orientation': 'landscape'})
        return out

    def seen(self, photos, i, east, north, width_m=0.70):
        """(x, w) of a sign at (east, north) metres, `width_m` wide, facing the rider, in photo i."""
        x0, y0 = build.to_xy(photos[i]['lat'], photos[i]['lon'], self.LAT0, self.LON0)
        z, lat = north - y0, east - x0
        f = 0.5 / build.math.tan(build.math.radians(self.HF / 2))
        return 0.5 + f * lat / z, width_m * f / z

    def err(self, lat, lon, east, north):
        return build.math.hypot(*build.to_xy(lat, lon, *build.to_ll(east, north, self.LAT0, self.LON0)))

    # --- prepare.py: the detector's box travels to photos.json ---

    def test_sign_hints_keep_the_box(self):
        item = {'signs': [{'code': 'P.130', 'group': 'prohibitory', 'label': 'Cấm dừng và đỗ xe', 'score': 0.92,
                           'box': [0.142, 0.305, 0.19, 0.395]},
                          {'code': 'P.102', 'label': 'x', 'score': 0.4}]}
        h = prepare.sign_hints(item)
        self.assertEqual(h[0], {'code': 'P.130', 'group': 'prohibitory', 'label': 'Cấm dừng và đỗ xe', 'score': 0.92,
                                'x': 0.166, 'w': 0.048, 'h': 0.09, 'box': [0.142, 0.305, 0.19, 0.395]})
        self.assertEqual((h[1]['x'], h[1]['w'], h[1]['box']), (None, None, None))   # no box: no position
        self.assertIsNone(prepare.sign_hints({}))
        self.assertIsNone(prepare.sign_hints({'signs': [{'box': [0.1, 'a', 0.2, 0.3]}]})[0]['w'])

    # --- sizes ---

    def test_standard_sizes(self):
        self.assertEqual(build.sign_shape('P.130'), 'circle')
        self.assertEqual(build.sign_shape('DP.135'), 'circle')
        self.assertEqual(build.sign_shape('W.207b'), 'triangle')
        self.assertEqual(build.sign_shape('R.122'), 'octagon')
        self.assertEqual(build.sign_shape('R.302a'), 'circle')
        for rect in ('R.412', 'R.415a', 'I.423b', 'S.508', 'W.245a', '', None):
            self.assertIsNone(build.sign_shape(rect), rect)
        self.assertAlmostEqual(build.sign_width_m({'code': 'P.127'}), 0.70)
        self.assertAlmostEqual(build.sign_width_m({'code': 'R.122'}), 0.60)
        self.assertAlmostEqual(build.sign_width_m({'code': 'P.127', 'side': 'over'}), 0.70 * 1.8)   # on a gantry
        self.assertAlmostEqual(build.sign_width_m({'code': 'P.127'}, {'signSizeFactor': 1.25}), 0.875)
        self.assertAlmostEqual(build.sign_width_m({'code': 'P.127', 'sizeFactor': 1.25, 'side': 'over'}), 0.875)
        self.assertAlmostEqual(build.sign_width_m({'code': 'R.412', 'sizeM': 1.2}), 1.2)
        self.assertIsNone(build.sign_width_m({'code': 'R.412'}))

    def test_depth_from_width(self):
        p = self.ride(1)[0]
        _x, w = self.seen([p], 0, 3, 20)
        self.assertAlmostEqual(build.sign_depth(0.70, w, p), 20, places=6)

    # --- ranging ---

    def test_one_photo_with_a_width_is_a_fix(self):
        photos = self.ride()
        x, w = self.seen(photos, 2, -5, 30)
        sign = {'code': 'P.130', 'cover': 2, 'sightings': [{'photo': 2, 'x': x, 'w': w}]}
        lat, lon, how = build.locate_sign(sign, photos, 15)
        self.assertLess(self.err(lat, lon, -5, 30), 0.1)
        self.assertTrue(how['method'].startswith('ranged from the sign size (0.70 m) on 1 photo'))
        self.assertEqual((how['sightings'][0]['rangeM'], how['sightings'][0]['wFrom']), (19.0, 'read'))
        # without a width: as before, the default distance ahead along the ray
        del sign['sightings'][0]['w']
        _la, _lo, how = build.locate_sign(sign, photos, 15)
        self.assertTrue(how['method'].startswith('single-photo estimate'))
        # a rectangle has no standard size: no range unless sizeM is given
        sign = {'code': 'R.412', 'cover': 2, 'sightings': [{'photo': 2, 'x': x, 'w': w}]}
        self.assertTrue(build.locate_sign(sign, photos, 15)[2]['method'].startswith('single-photo'))
        sign['sizeM'] = 0.70
        self.assertLess(self.err(*build.locate_sign(sign, photos, 15)[:2], -5, 30), 0.1)

    def test_height_when_cut_at_the_side(self):
        photos = self.ride()
        _x, w = self.seen(photos, 0, 3, 25)
        h = w / build.upright_aspect(photos[0])                 # a circle: as high as wide
        self.assertAlmostEqual(build.sighting_width({'x': 0.99, 'h': h}, photos[0], {'code': 'P.127'})[0], w)
        th = h * (3 ** 0.5 / 2)                                  # a triangle is 0.87 of its side high
        self.assertAlmostEqual(build.sighting_width({'x': 0.99, 'h': th}, photos[0], {'code': 'W.207b'})[0], w)

    def test_phone_boxes_range_a_sign_and_others_are_ignored(self):
        photos = self.ride()
        sights = []
        for i in (0, 3, 5):
            x, w = self.seen(photos, i, 3.5, 45)
            wb, hb = w + build.BOX_PAD, (w + build.BOX_PAD) / build.upright_aspect(photos[i])
            photos[i]['signHints'] = [
                {'code': 'P.127', 'x': round(x, 4), 'w': wb, 'h': hb},
                {'code': 'R.415a', 'x': round(x + 0.01, 4), 'w': 4 * wb, 'h': hb}]   # a lane board beside it
            sights.append({'photo': i, 'x': x})
        sign = {'code': 'P.127', 'cover': 5, 'sightings': sights}
        lat, lon, how = build.locate_sign(sign, photos, 15)
        self.assertLess(self.err(lat, lon, 3.5, 45), 0.5)
        self.assertEqual([s.get('wFrom') for s in how['sightings']], ['phone box'] * 3)
        # a box around something else on one photo (twice as wide): dropped, the other two still place it
        photos[3]['signHints'][0]['w'] *= 2
        photos[3]['signHints'][0]['h'] *= 2
        lat, lon, how = build.locate_sign(sign, photos, 15)
        self.assertTrue(how['sightings'][1].get('rangeDropped'))
        self.assertLess(self.err(lat, lon, 3.5, 45), 1.0)
        # the lane board alone (wrong shape for a circle) gives no range
        for i in (0, 3, 5):
            photos[i]['signHints'] = photos[i]['signHints'][1:]
        self.assertFalse(build.locate_sign(sign, photos, 15)[2]['method'].startswith('ranged'))

    def test_range_beats_nearly_parallel_rays(self):
        """Rays from a forward camera barely diverge: with a 1 deg bearing error they put the sign tens of metres
        off along the road; one range holds it."""
        photos = self.ride()
        sights = []
        for i in (0, 2, 4):
            x, w = self.seen(photos, i, 3, 60)
            sights.append({'photo': i, 'x': x + 0.012, 'w': w})       # ~1 deg off
        sign = {'code': 'P.127', 'cover': 4, 'sightings': sights}
        lat, lon, how = build.locate_sign(sign, photos, 15)
        self.assertLess(self.err(lat, lon, 3, 60), 3.0)
        self.assertLess(how['uncertaintyM'], 15)
        for s in sights:                                              # the same rays without the widths
            del s['w']
        lat, lon, _how = build.locate_sign(sign, photos, 15)
        self.assertGreater(self.err(lat, lon, 3, 60), 10)

    # --- layout and snap ---

    def test_layout_lines(self):
        two = build.layout_lines({'lanes': [3.2, 3.2], 'riderLane': 1, 'divider': True})
        self.assertAlmostEqual(two['right'], 1.6 + 1.0)                   # kerb + sign setback
        self.assertAlmostEqual(two['left'], -(4.8 + 0.5))                 # to the median + half its width
        self.assertEqual((two['lanes'], two['leftIs']), ([0.0, -3.2], 'median'))
        kinds = build.layout_lines({'lanes': ['moto', 'car', 'car'], 'riderLane': 2, 'divider': True,
                                    'medianWidthM': 2})
        self.assertAlmostEqual(kinds['right'], 2.75 + 1.75 + 1.0)
        self.assertAlmostEqual(kinds['left'], -(1.75 + 3.5 + 1.0))
        self.assertEqual(kinds['lanes'], [3.12, 0.0, -3.5])
        # measured offsets win; no divider: the far kerb past the other direction's lanes
        meas = build.layout_lines({'lanes': 2, 'rightKerbOffsetM': 2.2, 'medianOffsetM': 4.4, 'divider': True})
        self.assertAlmostEqual(meas['right'], 3.2)
        self.assertAlmostEqual(meas['left'], -4.9)
        undiv = build.layout_lines({'lanes': 1, 'oppositeLanes': 1, 'signSetbackM': 0.5})
        self.assertAlmostEqual(undiv['right'], 1.75 + 0.5)
        self.assertAlmostEqual(undiv['left'], -(1.75 + 3.5 + 0.5))
        self.assertEqual(undiv['leftIs'], 'left kerb')
        self.assertAlmostEqual(build.lane_offset([1.0, -2.0, -5.0], 2.5), -3.5)
        self.assertIsNone(build.lane_offset([1.0], 3))

    def snapped(self, side, east, north, lane=None, road=None, method='ranged from the sign size'):
        photos = self.ride()
        spec = {'road': [road or {'fromPhoto': 0, 'toPhoto': 99, 'lanes': [3.2, 3.2], 'divider': True}]}
        lat, lon = build.to_ll(east, north, self.LAT0, self.LON0)
        sign = {'side': side, 'lane': lane, 'cover': 3}
        la, lo, how = build.snap_sign(sign, lat, lon, {'method': method, 'uncertaintyM': 5}, photos, spec)
        x, y = build.to_xy(la, lo, self.LAT0, self.LON0)
        return x, y, how

    def test_snap_keeps_the_along_road_position(self):
        x, y, how = self.snapped('left', -2.5, 20)
        self.assertAlmostEqual(x, -5.3, places=1)
        self.assertAlmostEqual(y, 20, places=1)
        self.assertEqual((how['unsnapped']['acrossM'], how['snap']['done'], how['snap']['side']), (-2.5, True, 'left'))
        self.assertIn('snapped 2.8 m left onto the median line', how['method'])
        x, _y, _h = self.snapped('right', 4.0, 20)
        self.assertAlmostEqual(x, 2.6, places=1)
        x, _y, how = self.snapped('over', 1.0, 20, lane=2)
        self.assertAlmostEqual(x, -3.2, places=1)
        self.assertIn('over lane 2', how['method'])
        x, _y, how = self.snapped('over', 1.0, 20)                       # no lane: the middle of the carriageway
        self.assertAlmostEqual(x, -1.6, places=1)

    def test_a_large_move_is_flagged_not_made(self):
        x, y, how = self.snapped('right', -6.0, 20)                      # 8.6 m from the right kerb line
        self.assertAlmostEqual((x, y)[0], -6.0, places=1)
        self.assertFalse(how['snap']['done'])
        self.assertIn('NOT snapped', how['method'])

    def test_no_layout_no_snap(self):
        photos = self.ride()
        lat, lon = build.to_ll(-2, 20, self.LAT0, self.LON0)
        how = {'method': 'm'}
        self.assertEqual(build.snap_sign({'side': 'left'}, lat, lon, how, photos, {}), (lat, lon, how))
        spec = {'road': [{'fromPhoto': 50, 'toPhoto': 60, 'lanes': 2}]}      # another stretch
        self.assertEqual(build.snap_sign({'side': 'left'}, lat, lon, how, photos, spec), (lat, lon, how))
        self.assertEqual(build.snap_sign({'side': 'left', 'snap': False}, lat, lon, how, photos,
                                         {'road': [{'lanes': 2, 'divider': True}]}), (lat, lon, how))

    def test_rays_meet_the_line_without_a_range(self):
        """A lane board (no standard size) placed 15 m ahead by default: snapped across, its along-road position
        comes from where its rays cross the kerb line."""
        photos = self.ride()
        road = {'fromPhoto': 0, 'toPhoto': 99, 'lanes': [3.2, 3.2], 'divider': True}
        sights = [{'photo': i, 'x': self.seen(photos, i, 2.6, 14)[0]} for i in (0, 1)]   # 10 and 17 deg off
        sign = {'side': 'right', 'cover': 3, 'sightings': sights}
        lat, lon = build.to_ll(2.0, 22, self.LAT0, self.LON0)            # where the default put it
        la, lo, how = build.snap_sign(sign, lat, lon, {'method': 'single-photo estimate', 'uncertaintyM': 8},
                                      photos, {'road': [road]})
        self.assertLess(self.err(la, lo, 2.6, 14), 0.3)
        self.assertIn('where 2 rays cross that line', how['method'])
        self.assertLess(how['uncertaintyM'], 8)


if __name__ == '__main__':
    unittest.main()


class BlurTest(unittest.TestCase):
    """blur.py without the models: the anchors, the pixelation, apply from the originals, and push refusing."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_plate_anchors_match_the_zoo_order(self):
        import blur
        p = blur.Plates.__new__(blur.Plates)
        p.size = None
        p._priors(64, 48)
        # zoo order: cell by cell (row, column), each cell's sizes in turn; first map is stride 8
        self.assertEqual(p.priors.shape[1], 4)
        self.assertAlmostEqual(float(p.priors[0][0]), 0.5 * 8 / 64)
        self.assertAlmostEqual(float(p.priors[1][2]), 16 / 64)        # same cell, second size
        self.assertAlmostEqual(float(p.priors[3][0]), 1.5 * 8 / 64)   # next cell

    def test_apply_blurs_from_originals_and_marks_the_place(self):
        import blur, cloud
        from PIL import Image
        out, check = os.path.join(self.tmp, 'out'), os.path.join(self.tmp, 'check')
        os.makedirs(os.path.join(out, '01_Cafe'))
        json.dump({'place': {'name': 'Cafe'}}, open(os.path.join(out, '01_Cafe', 'place.json'), 'w'))
        im = Image.new('RGB', (200, 100), (255, 255, 255))
        for x in range(0, 200, 2):   # stripes: pixelation flattens them
            for y in range(100):
                im.putpixel((x, y), (0, 0, 0))
        im.save(os.path.join(out, '01_Cafe', 'COVER_#1_p.jpg'), quality=95)
        Image.new('RGB', (50, 50), (9, 9, 9)).save(os.path.join(out, '01_Cafe', 'WEB_1.jpg'))
        self.assertEqual(cloud.unblurred(out), ['01_Cafe'])
        os.makedirs(os.path.join(check, 'orig', '01_Cafe'))
        shutil.copy(os.path.join(out, '01_Cafe', 'COVER_#1_p.jpg'), os.path.join(check, 'orig', '01_Cafe'))
        rel = '01_Cafe/COVER_#1_p.jpg'
        json.dump({'boxes': [{'id': 'b0001', 'file': rel, 'kind': 'face', 'box': [10, 10, 60, 60], 'state': 'auto'},
                             {'id': 'b0002', 'file': rel, 'kind': 'plate', 'box': [120, 10, 180, 40], 'state': 'check'}]},
                  open(os.path.join(check, 'boxes.json'), 'w'))
        json.dump({'keep': ['b0002'], 'add': [{'file': rel, 'box': [0.6, 0.6, 0.9, 0.9]}]},
                  open(os.path.join(check, 'decisions.json'), 'w'))
        for _ in range(2):   # twice: always from the original
            blur.apply(out, check)
        res = Image.open(os.path.join(out, rel)).convert('L')
        spread = lambda box: max(res.crop(box).getdata()) - min(res.crop(box).getdata())
        self.assertLess(spread((20, 20, 50, 50)), 120)      # the face: flattened
        self.assertGreater(spread((130, 15, 170, 35)), 200)  # kept (a sign): stripes still there
        self.assertLess(spread((130, 65, 170, 85)), 120)    # added by hand
        m = json.load(open(os.path.join(out, '01_Cafe', 'place.json')))
        self.assertEqual((m['privacy']['faces'], m['privacy']['plates'], m['privacy']['added']), (1, 0, 1))
        self.assertEqual(cloud.unblurred(out), [])
        self.assertTrue(os.path.exists(os.path.join(check, 'after_00.jpg')))
        self.assertEqual(m['privacy']['files'], ['COVER_#1_p.jpg'])
        # keeping a sure box (the face) is refused: only boxes up for checking can be kept
        json.dump({'keep': ['b0001']}, open(os.path.join(check, 'decisions.json'), 'w'))
        with self.assertRaises(SystemExit):
            blur.apply(out, check)
        json.dump({'keep': ['b0002'], 'add': [{'file': rel, 'box': [0.6, 0.6, 0.9, 0.9]}]},
                  open(os.path.join(check, 'decisions.json'), 'w'))
        # a photo added after the blur: push refuses the folder (and a zip of it), apply refuses to run
        os.makedirs(os.path.join(out, '01_Cafe', 'more'))
        shutil.copy(os.path.join(check, 'orig', rel), os.path.join(out, '01_Cafe', 'more', 'p2.jpg'))
        self.assertEqual(cloud.unblurred(out), ['01_Cafe/more/p2.jpg'])   # privacy.json: the photo itself
        z = cloud.zip_folder(out)
        try:
            self.assertEqual(cloud.unblurred_zip(z), ['01_Cafe/more/p2.jpg'])
        finally:
            os.remove(z)
        with self.assertRaises(SystemExit):
            blur.apply(out, check)
        self.assertEqual(json.load(open(os.path.join(out, '01_Cafe', 'place.json')))['privacy']['files'], ['COVER_#1_p.jpg'])

    def test_removal_photos_are_blurred_and_checked_too(self):
        import blur, cloud
        from PIL import Image
        out, check = os.path.join(self.tmp, 'out'), os.path.join(self.tmp, 'check')
        os.makedirs(os.path.join(out, '01_A')); os.makedirs(os.path.join(out, '_removals'))
        json.dump({'place': {'name': 'A'}}, open(os.path.join(out, '01_A', 'place.json'), 'w'))
        for f in ('01_A/COVER_#1_p.jpg', '_removals/REMOVED_#2_p.jpg'):
            Image.new('RGB', (60, 40), (200, 200, 200)).save(os.path.join(out, f))
        self.assertEqual(cloud.unblurred(out), ['01_A', '_removals/REMOVED_#2_p.jpg'])   # not blurred; a loose photo too
        self.assertIn('_removals/REMOVED_#2_p.jpg', [r for r, _ in blur.photos_of(out)])
        for rel, _ in blur.photos_of(out):
            os.makedirs(os.path.dirname(os.path.join(check, 'orig', rel)), exist_ok=True)
            shutil.copy(os.path.join(out, rel), os.path.join(check, 'orig', rel))
        json.dump({'boxes': []}, open(os.path.join(check, 'boxes.json'), 'w'))
        blur.apply(out, check)
        self.assertEqual(json.load(open(os.path.join(out, 'privacy.json')))['files'], ['01_A/COVER_#1_p.jpg', '_removals/REMOVED_#2_p.jpg'])
        self.assertEqual(cloud.unblurred(out), [])
        z = cloud.zip_folder(out)
        try:
            self.assertEqual(cloud.unblurred_zip(z), [])
        finally:
            os.remove(z)
        Image.new('RGB', (60, 40)).save(os.path.join(out, '_removals', 'REMOVED_#3_p.jpg'))   # added after the blur
        self.assertEqual(cloud.unblurred(out), ['_removals/REMOVED_#3_p.jpg'])

    def test_push_check_on_older_results_compares_the_count(self):
        import cloud
        self.assertEqual(cloud._unblurred_places({'a': ({'privacy': {'blurred': True, 'photos': 1}}, ['x.jpg', 'y.jpg']),
                                                  'b': ({'privacy': {'blurred': True, 'photos': 2}}, ['x.jpg', 'y.jpg']),
                                                  'c': ({}, [])}), ['a'])
