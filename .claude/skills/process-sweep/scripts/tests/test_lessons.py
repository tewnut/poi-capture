"""Tests for the changes from the house-number rides of 24 Nov 2026 (lessons A, B, C): the addresses' new fields,
street stretches, the Google checks, website checks, crop sheets, blur copies and zoom sheets, merging reader parts
and the resumable push.

    python -m unittest discover -s .claude/skills/process-sweep/scripts/tests -v
"""
import contextlib, io, json, os, shutil, sys, tempfile, unittest, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import build  # noqa: E402
import enrich  # noqa: E402
import enrich_browser  # noqa: E402
import enrich_web  # noqa: E402
import merge_places  # noqa: E402
import prepare  # noqa: E402
import result  # noqa: E402


def read(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def write(path, data):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False))


def quiet(fn, *a, **k):
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return fn(*a, **k)


def make_work(d, n=8, size=(64, 36), road='Đường Số 7'):
    from PIL import Image
    work = os.path.join(d, 'work')
    os.makedirs(work)
    photos = []
    for i in range(n):
        f = os.path.join(work, f'p{i}.jpg')
        Image.new('RGB', size, (i * 20, 90, 120)).save(f)
        photos.append({'idx': i, 'id': f'p{i}', 'file': f, 't': f'2026-11-24T10:00:0{i}Z',
                       'lat': 10.78 + i * 0.00005, 'lon': 106.77, 'accuracyM': 4, 'cameraBearing': 90,
                       'cameraBearingSource': 'gyroCompass', 'hFovDeg': 69, 'width': size[0], 'height': size[1],
                       'speedMps': 5, 'roadName': road, 'blurry': False, 'sharpness': 10, 'travelBearing': 0})
    write(os.path.join(work, 'photos.json'), photos)
    write(os.path.join(work, 'session.json'), {'id': 's_l', 'name': 'L', 'kind': 'sweep', 'target': 'addresses'})
    return work


class AddressFields(unittest.TestCase):
    """Item 1: source, partly read numbers, old / other numbers, medium confidence, street stretches."""

    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.work = make_work(self.d)
        self.out = os.path.join(self.d, 'out', 'ride')

    def tearDown(self):
        shutil.rmtree(self.d)

    def spec(self, **extra):
        s = {'street': 'Đường Số 7', 'defaultFacadeDistanceM': 10,
             'streets': [{'fromPhoto': 4, 'toPhoto': 7, 'street': 'Thích Mật Thể'}],
             'places': [{'name': 'Phở Liên', 'photos': [0, 1], 'cover': 1, 'sightings': [{'photo': 1, 'x': 0.5}],
                         'confidence': 'medium'},
                        {'name': 'Tạp Hóa', 'photos': [5, 6], 'cover': 5, 'sightings': [{'photo': 5, 'x': 0.5}]}],
             'addresses': [
                 {'houseNumber': '103', 'oldNumber': '7/12', 'source': 'plate', 'photos': [1, 2], 'cover': 2,
                  'sightings': [{'photo': 2, 'x': 0.4}], 'confidence': 'high'},
                 {'houseNumber': '1…', 'source': 'plate', 'photos': [3, 3], 'cover': 3,
                  'sightings': [{'photo': 3, 'x': 0.5}], 'confidence': 'medium', 'note': 'cut by the frame'},
                 {'houseNumber': '18', 'altNumbers': ['34'], 'source': 'sign', 'photos': [4, 5], 'cover': 5,
                  'sightings': [{'photo': 5, 'x': 0.5}], 'confidence': 'high'}]}
        s.update(extra)
        return s

    def build(self, spec):
        write(os.path.join(self.work, 'places.json'), spec)
        quiet(build.build, self.work, self.out)
        return read(os.path.join(self.out, 'result.json'))

    def test_new_fields_reach_result_and_exports(self):
        res = self.build(self.spec())
        by = {p['name']: p for p in res['places']}
        a = by['103 Đường Số 7']
        self.assertEqual(a['numbering'], {'source': 'plate', 'oldNumber': '7/12', 'altNumbers': [], 'partial': False})
        self.assertIn('old number 7/12', a['note'])
        p = by['1… Đường Số 7']
        self.assertEqual(p['confidence'], 'low')                  # a partly read number is never more than low
        self.assertTrue(p['numbering']['partial'])
        self.assertTrue(p['note'].startswith('cut by the frame; number read only in part'))
        # photo 5 is in the "streets" stretch: Thích Mật Thể, although the phone said Đường Số 7
        s = by['18 Thích Mật Thể']
        self.assertEqual((s['numbering']['source'], s['numbering']['altNumbers']), ('sign', ['34']))
        self.assertEqual(s['fields']['street']['value'], 'Thích Mật Thể')
        self.assertEqual(by['Tạp Hóa']['fields']['street']['value'], 'Thích Mật Thể')
        self.assertEqual(by['Phở Liên']['fields']['street']['value'], 'Đường Số 7')
        self.assertEqual(by['Phở Liên']['confidence'], 'medium')
        pj = read(os.path.join(self.out, s['folder'], 'place.json'))['place']
        self.assertEqual(pj['streetFrom'], 'streets in places.json')
        g = {f['properties']['name']: f['properties'] for f in read(os.path.join(self.out, 'places.geojson'))['features']}
        self.assertEqual((g['103 Đường Số 7']['oldNumber'], g['103 Đường Số 7']['numberSource']), ('7/12', 'plate'))
        self.assertTrue(g['1… Đường Số 7']['partial'])
        self.assertNotIn('numberSource', g['Phở Liên'])
        with open(os.path.join(self.out, 'places.csv'), encoding='utf-8-sig') as f:
            rows = f.read().splitlines()
        self.assertTrue(rows[0].endswith('folder,house number,number source,old number,other numbers,sign post,sign order,level,unit,building'))
        self.assertTrue(any(r.endswith(',18,sign,,34,,,,,') for r in rows))

    def test_unknown_values_are_refused(self):
        spec = self.spec()
        spec['places'][0]['confidence'] = 'med'
        spec['addresses'][0]['source'] = 'banner'
        with self.assertRaises(SystemExit) as e:
            self.build(spec)
        self.assertIn("confidence 'med'", str(e.exception))
        self.assertIn("source 'banner'", str(e.exception))

    def test_street_at(self):
        spec = {'streets': [{'fromPhoto': 0, 'toPhoto': 30, 'street': 'A'}, {'fromPhoto': 31, 'street': 'B'}]}
        self.assertEqual([build.street_at(spec, i) for i in (0, 30, 31, 500)], ['A', 'A', 'B', 'B'])
        self.assertIsNone(build.street_at({}, 3))


class Phones(unittest.TestCase):
    """Item 2: old 11-digit mobile numbers compare with today's."""

    def test_old_prefixes(self):
        self.assertEqual(result._digits('0168 123 4567'), '0381234567')
        self.assertEqual(result._digits('+84 168 123 4567'), '0381234567')
        self.assertEqual(result._digits('0122.345.6789'), '0773456789')
        self.assertEqual(result._digits('01234 567 890'), '0834567890')
        self.assertEqual(result._digits('0904523417'), '0904523417')
        self.assertEqual(result._digits('028 3896 1234'), '02838961234')   # a landline is left alone
        self.assertTrue(result._same('phone', '0168 123 4567', '0381234567'))
        self.assertEqual(enrich_browser.phones_in('Rửa xe · 0168 123 4567 · Mở cửa'), {'0381234567'})


class GoogleChecks(unittest.TestCase):
    """Item 2: house number, generic names, phone matches."""

    def f(self, name, phone=None, house=None, sure='high'):
        out = {'name': {'value': name, 'photo': 1, 'sure': 'high'}}
        if phone:
            out['phone'] = {'value': phone, 'photo': 1, 'sure': sure}
        if house:
            out['houseNumber'] = {'value': house, 'photo': 1, 'sure': sure}
        return out

    def test_house_number_differs(self):
        g = {'name': 'Mắt Kính Sao Việt', 'nameMatch': 1.0, 'distanceM': 13, 'address': '644 Nguyễn Thị Định, Thạnh Mỹ Lợi'}
        ok, why = result.google_check(g, self.f('Mắt Kính Sao Việt', house='553'))
        self.assertFalse(ok)
        self.assertIn('house number differs', why)
        self.assertTrue(result.google_check(g, self.f('Mắt Kính Sao Việt', house='644/2'))[0])
        self.assertTrue(result.google_check(g, self.f('Mắt Kính Sao Việt', house='55…', sure='low'))[0])   # unclear
        self.assertTrue(result.google_check({**g, 'thisPlace': True}, self.f('Mắt Kính Sao Việt', house='553'))[0])
        self.assertEqual(result.merge_google(self.f('X', house='553'), {**g, 'name': 'X'}), self.f('X', house='553'))

    def test_generic_name_needs_phone_or_number(self):
        self.assertTrue(result.generic_name('Mắt Kính'))
        self.assertTrue(result.generic_name('Hớt Tóc Nam'))
        self.assertTrue(result.generic_name('Phở - Bò Kho'))
        self.assertTrue(result.generic_name('CƠM TẤM'))
        self.assertFalse(result.generic_name('Phở Liên'))
        self.assertFalse(result.generic_name('Bún bò 105'))
        g = {'name': 'Mắt Kính Sao Việt', 'nameMatch': 1.0, 'distanceM': 13, 'phone': '0909 111 222',
             'address': 'Nguyễn Thị Định, Thạnh Mỹ Lợi'}
        ok, why = result.google_check(g, self.f('Mắt Kính'))
        self.assertFalse(ok)
        self.assertIn('generic name', why)
        self.assertTrue(result.google_check(g, self.f('Mắt Kính', phone='0909111222'))[0])
        self.assertTrue(result.google_check({**g, 'address': '12 Nguyễn Thị Định'}, self.f('Mắt Kính', house='12'))[0])

    def test_phone_match_beats_a_low_name_match(self):
        g = {'name': 'Rửa Xe ĐT MotorBike', 'nameMatch': 0.3, 'distanceM': 7, 'phone': '+84 168 222 3333'}
        f = self.f('Rửa Xe ĐT MotorBike - Rửa Xe, Thay Nhớt, Vá Vỏ', phone='01682223333')
        ok, why = result.google_check(g, f)
        self.assertTrue(ok)
        self.assertIn('same phone', why)
        self.assertFalse(result.google_check(g, self.f('Rửa Xe ĐT MotorBike - Rửa Xe'))[0])
        self.assertFalse(result.google_check({**g, 'distanceM': 150}, f)[0])
        self.assertEqual(result.merge_google(f, g)['phone']['google'], 'match')
        # the browser takes such a result too
        self.assertTrue(enrich_browser.acceptable({'nameMatch': 0.1, 'distanceM': 60, 'phoneMatch': True}))
        self.assertFalse(enrich_browser.acceptable({'nameMatch': 0.1, 'distanceM': 160, 'phoneMatch': True}))
        self.assertEqual(enrich_browser.sign_phones(f), {'0382223333'})
        self.assertEqual(enrich_browser.sign_phones({'phone': {'value': '0909111222', 'source': 'google'}}), set())

    def test_api_pick_by_phone(self):
        places = [{'displayName': {'text': 'Cửa hàng ABC'}, 'location': {'latitude': 10.78, 'longitude': 106.7701},
                   'nationalPhoneNumber': '038 222 3333'},
                  {'displayName': {'text': 'Something else'}, 'location': {'latitude': 10.78, 'longitude': 106.77},
                   'nationalPhoneNumber': '0909 000 000'}]
        self.assertIsNone(enrich.best(places, 'Rửa Xe ĐT', 10.78, 106.77))
        g = enrich.best(places, 'Rửa Xe ĐT', 10.78, 106.77, {'0382223333'})
        self.assertEqual((g['name'], g.get('phoneMatch')), ('Cửa hàng ABC', True))

    def test_visit_task_says_why_not_merged(self):
        g = {'status': 'found', 'name': 'Mắt Kính Sao Việt', 'nameMatch': 1.0, 'distanceM': 13,
             'merge': {'merged': False, 'why': 'house number differs: photos 553, Google 644'}}
        t = result.tasks_for([{'id': 's-01', 'name': 'Mắt Kính', 'status': 'open', 'confidence': 'high', 'photos': [],
                               'pins': {'algorithm': {'lat': 10.0, 'lon': 106.0}}, 'google': g}], [])
        self.assertEqual(len(t), 1)
        self.assertIn('house number differs', t[0]['why'])


class Websites(unittest.TestCase):
    """Item 2: a brand's site and a site whose address is in another province are not this shop's."""

    def test_brand_site(self):
        self.assertEqual(enrich_web.brand_site('https://www.castrol.com/vi_vn/vietnam.html'), 'castrol')
        self.assertEqual(enrich_web.brand_site('honda.com.vn'), 'honda')
        self.assertEqual(enrich_web.brand_site('https://yamaha-motor.com.vn/'), 'yamaha-motor')
        self.assertIsNone(enrich_web.brand_site('https://phovu.vn'))
        self.assertIsNone(enrich_web.brand_site('https://hondasuaxe-hai.vn'))

    def test_far_address(self):
        far = enrich_web.far_address('Số 12 ngõ 34 Trần Duy Hưng, Cầu Giấy, Hà Nội', 10.78, 106.77)
        self.assertEqual(far[0], 'ha noi')
        self.assertGreater(far[1], 1000)
        self.assertIsNone(enrich_web.far_address('97 Trương Văn Bang, Cát Lái, TP. Hồ Chí Minh', 10.78, 106.77))
        self.assertIsNone(enrich_web.far_address('123 Hậu Giang, Quận 6, TP.HCM', 10.75, 106.63))   # street named after a province
        self.assertIsNone(enrich_web.far_address('Đường số 3, Thủ Đức', 10.78, 106.77))
        self.assertIsNone(enrich_web.far_address('KCN Biên Hòa, Đồng Nai', 10.78, 106.77))           # 30 km: near


class Crops(unittest.TestCase):
    """Item 3: full-resolution tiles around the phone's plate hints, own boxes, the kerb band."""

    def setUp(self):
        self.d = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_crop_box(self):
        import crops
        x0, y0, x1, y1 = crops.crop_box([1000, 2000, 1060, 2040], 2160, 3840)
        self.assertLessEqual(x0, 1000)
        self.assertGreaterEqual(x1, 1060)
        self.assertAlmostEqual((x1 - x0) / (y1 - y0), 4 / 3, places=1)
        self.assertEqual(crops.crop_box([0, 0, 30, 20], 2160, 3840)[:2], (0, 0))        # kept inside the photo
        self.assertEqual(crops.crop_box([2150, 3830, 2160, 3840], 2160, 3840)[2:], (2160, 3840))

    def test_plate_hints_from_the_phone(self):
        item = {'plates': [{'text': '106', 'heightPx': 40, 'box': [100, 200, 160, 240]}, {'text': '45K', 'heightPx': 20}]}
        self.assertEqual(prepare.plate_hints(item), [{'text': '106', 'box': [100, 200, 160, 240], 'heightPx': 40},
                                                     {'text': '45K', 'box': None, 'heightPx': 20}])
        self.assertIsNone(prepare.plate_hints({}))
        # app 0.20+: kind / src / why travel along, and the crop label says them
        new = {'plates': [{'text': '12', 'box': [1, 2, 3, 4], 'kind': 'doubt', 'src': 'sign', 'why': 'small'}],
               'platesDropped': [{'text': '45K', 'why': 'price'}]}
        h = prepare.plate_hints(new)
        self.assertEqual((h[0]['kind'], h[0]['src']), ('doubt', 'sign'))
        self.assertEqual(prepare.plate_hints(new, 'platesDropped')[0]['why'], 'price')
        import crops
        self.assertEqual(crops.hint_tiles([{'idx': 4, 'plateHints': h}])[0][0][2], '12 (sign ?)')

    def test_sheets(self):
        import crops
        from PIL import Image
        work = make_work(self.d, n=3, size=(600, 1000))
        photos = read(os.path.join(work, 'photos.json'))
        photos[1]['plateHints'] = [{'text': '106', 'box': [300, 500, 340, 530]}, {'text': '7', 'box': None}]
        photos[2]['plateHints'] = [{'text': '99B', 'box': [10, 900, 50, 950]}]
        write(os.path.join(work, 'photos.json'), photos)
        n, t, nobox = crops.plates(work)
        self.assertEqual((n, t, nobox), (1, 2, 1))
        idx = read(os.path.join(work, 'crops', 'plates.json'))
        self.assertEqual([(x['tile'], x['photo'], x['hint']) for x in idx], [(1, 1, '106'), (2, 2, '99B')])
        self.assertTrue(os.path.isfile(os.path.join(work, 'crops', 'plates_00.jpg')))
        self.assertEqual(crops.boxes(work, ['2:0.1,0.8,0.5,0.95', '0:0,0,1,0.5']), 1)
        self.assertEqual(len(read(os.path.join(work, 'crops', 'boxes.json'))), 2)
        with self.assertRaises(SystemExit):
            crops.parse_box('2:0.5,0.8,0.1,0.95')
        self.assertEqual(crops.band(work), 2)                                          # 3 photos, 2 per sheet
        self.assertEqual(Image.open(os.path.join(work, 'crops', 'band_00.jpg')).size[0], 1400 + 44)


class BlurCopies(unittest.TestCase):
    """Item 4: a hand-added box reaches every copy of the photo; zoomed sheets of the kerb band."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_photo_key_and_targets(self):
        import blur
        self.assertEqual(blur.photo_key('07_Pho/COVER_#38_p_x.jpg'), '#38_p_x')
        self.assertEqual(blur.photo_key('12_addr_106/BEST2_#38_p_x.jpg'), '#38_p_x')
        self.assertEqual(blur.photo_key('07_Pho/more/#38_p_x.jpg'), '#38_p_x')
        self.assertEqual(blur.photo_key('_removals/REMOVED_#38_p_x.jpg'), '#38_p_x')
        self.assertIsNone(blur.photo_key('01/photo.jpg'))
        files = ['07_Pho/COVER_#38_p_x.jpg', '12_addr_106/BEST2_#38_p_x.jpg', '12_addr_106/COVER_#39_p_y.jpg']
        self.assertEqual(blur.add_targets({'file': files[0]}, files), files[:2])
        self.assertEqual(blur.add_targets({'file': files[0], 'only': True}, files), files[:1])
        self.assertEqual(blur.add_targets({'file': '05_Old/COVER_#38_p_x.jpg'}, files), files[:2])   # renamed folder
        self.assertEqual(blur.add_targets({'file': 'gone.jpg'}, files), [])

    def test_add_applies_to_every_copy(self):
        import blur, cloud
        from PIL import Image
        out, check = os.path.join(self.tmp, 'out'), os.path.join(self.tmp, 'check')
        a, b = '01_Pho/COVER_#3_p3.jpg', '02_addr_106/BEST2_#3_p3.jpg'
        im = Image.new('RGB', (200, 400), (255, 255, 255))
        for x in range(0, 200, 2):
            for y in range(400):
                im.putpixel((x, y), (0, 0, 0))
        for rel in (a, b):
            os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
            os.makedirs(os.path.dirname(os.path.join(check, 'orig', rel)), exist_ok=True)
            json.dump({'place': {'name': rel}}, open(os.path.join(out, rel.split('/')[0], 'place.json'), 'w'))
            im.save(os.path.join(out, rel), quality=95)
            im.save(os.path.join(check, 'orig', rel), quality=95)
        json.dump({'boxes': []}, open(os.path.join(check, 'boxes.json'), 'w'))
        json.dump({'keep': [], 'add': [{'file': a, 'box': [0.5, 0.7, 0.9, 0.8], 'why': 'parked bike plate'}]},
                  open(os.path.join(check, 'decisions.json'), 'w'))
        quiet(blur.apply, out, check)
        for rel in (a, b):
            res = Image.open(os.path.join(out, rel)).convert('L').crop((120, 290, 170, 310))
            self.assertLess(max(res.getdata()) - min(res.getdata()), 120, rel)
            self.assertEqual(json.load(open(os.path.join(out, rel.split('/')[0], 'place.json')))['privacy']['added'], 1)
        self.assertEqual(cloud.unblurred(out), [])
        self.assertTrue(os.path.isfile(os.path.join(check, 'zoom_00.jpg')))
        self.assertEqual(len(read(os.path.join(check, 'zoom.json'))), 1)              # one per sweep photo
        # an add for a photo that is not there stops apply (it would silently blur nothing)
        json.dump({'keep': [], 'add': [{'file': 'x/y.jpg', 'box': [0, 0, 1, 1]}]}, open(os.path.join(check, 'decisions.json'), 'w'))
        with self.assertRaises(SystemExit):
            quiet(blur.apply, out, check)


class MergeParts(unittest.TestCase):
    """Item 5: reader parts of a long ride with overlapping photo ranges."""

    def test_merge(self):
        p1 = {'street': 'Nguyễn Thị Định', 'defaultFacadeDistanceM': 12, 'removals': [{'name': 'Cafe X', 'photo': 80}],
              'places': [{'name': 'Kim Tiền', 'photos': [80, 87], 'cover': 86, 'confidence': 'low',
                          'sightings': [{'photo': 82, 'x': 0.7}, {'photo': 86, 'x': 0.4}],
                          'fields': {'phone': {'value': '0909 1…', 'photo': 86, 'sure': 'low'}}, 'note': 'cut at 87'},
                         {'name': 'Phở Hòa', 'photos': [10, 12], 'cover': 11}],
              'addresses': [{'houseNumber': '702', 'photos': [86, 87], 'cover': 87, 'confidence': 'high'}],
              'road': [{'fromPhoto': 0, 'toPhoto': 87}]}
        p2 = {'street': 'other', 'defaultFacadeDistanceM': 20, 'removals': [{'name': 'Cafe X', 'photo': 80}],
              'places': [{'name': 'KIM TIỀN', 'photos': [84, 90], 'cover': 88, 'confidence': 'high',
                          'sightings': [{'photo': 86, 'x': 0.41}, {'photo': 90, 'x': 0.1}],
                          'fields': {'phone': {'value': '0909 123 456', 'photo': 88, 'sure': 'high'}},
                          'best': [{'photo': 88, 'why': 'whole'}]},
                         {'name': 'Phở Hòa', 'photos': [150, 152], 'cover': 151}],
              'addresses': [{'houseNumber': '702', 'photos': [84, 85], 'cover': 85, 'confidence': 'high'},
                            {'houseNumber': '704', 'photos': [90, 90], 'cover': 90}],
              'road': [{'fromPhoto': 84, 'toPhoto': 173}]}
        out, report = merge_places.merge([p1, p2])
        self.assertEqual((out['street'], out['defaultFacadeDistanceM']), ('Nguyễn Thị Định', 12))
        names = [(p['name'], p['photos']) for p in out['places']]
        self.assertEqual(names, [('Phở Hòa', [10, 12]), ('KIM TIỀN', [80, 90]), ('Phở Hòa', [150, 152])])
        kt = out['places'][1]
        self.assertEqual(kt['cover'], 88)                                       # the surer reader's
        self.assertEqual(kt['confidence'], 'low')                               # the lower one
        self.assertEqual([s['photo'] for s in kt['sightings']], [82, 86, 90])
        self.assertEqual(kt['fields']['phone']['value'], '0909 123 456')
        self.assertEqual(kt['note'], 'cut at 87')
        self.assertEqual([(a['houseNumber'], a['photos']) for a in out['addresses']], [('702', [84, 87]), ('704', [90, 90])])
        self.assertEqual(len(out['removals']), 1)
        self.assertEqual(len(out['road']), 2)
        self.assertTrue(any('merged' in r and 'Kim Tiền' in r for r in report))
        self.assertTrue(any('CHECK' in r and 'Phở Hòa' in r for r in report))


class FakeCloud:
    """A tus server that times out on some PATCHes (sometimes after storing the bytes)."""

    def __init__(self, size, fail=(), stored_on_fail=(), gone_at=None):
        self.size, self.server, self.calls, self.fail, self.stored = size, 0, 0, set(fail), set(stored_on_fail)
        self.chunks, self.gone_at, self.created = [], gone_at, 0

    def create(self, size, meta):
        self.created += 1
        self.server = 0
        return f'https://x/files/u{self.created}'

    def offset(self, url):
        return None if self.gone_at is not None and self.calls >= self.gone_at and self.created == 1 else self.server

    def patch(self, url, offset, chunk):
        self.calls += 1
        if self.gone_at is not None and self.calls >= self.gone_at and self.created == 1:
            raise urllib.error.HTTPError(url, 404, 'gone', {}, None)
        assert offset == self.server, (offset, self.server)
        if self.calls in self.fail:
            if self.calls in self.stored:
                self.server += len(chunk)
            raise TimeoutError('The write operation timed out')
        self.chunks.append(len(chunk))
        self.server += len(chunk)
        return self.server


class Push(unittest.TestCase):
    """Item 6: pushes ride out timeouts, resync with the server, shrink chunks, resume a folder's upload."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_send_rides_out_timeouts(self):
        import cloud
        size = 3 * 1024 * 1024 + 5
        data = os.urandom(size)
        fc = FakeCloud(size, fail={2, 3}, stored_on_fail={3})
        with io.BytesIO(data) as f:
            end = cloud.send(fc, 'u', f, 0, size, chunk=1024 * 1024, sleep=lambda s: None, log=lambda m: None)
        self.assertEqual((end, fc.server), (size, size))
        self.assertLess(min(fc.chunks), 1024 * 1024)                       # smaller chunks after the timeouts

    def test_send_gives_up_without_progress(self):
        import cloud
        fc = FakeCloud(10, fail=set(range(1, 100)))
        with self.assertRaises(SystemExit) as e:
            cloud.send(fc, 'u', io.BytesIO(b'0123456789'), 0, 10, sleep=lambda s: None, log=lambda m: None)
        self.assertIn('run the same push again', str(e.exception))
        self.assertEqual(fc.calls, cloud.MAX_STALLS + 1)

    def test_lost_upload_starts_again(self):
        import cloud
        folder = os.path.join(self.tmp, 'ride')
        os.makedirs(folder)
        write(os.path.join(folder, 'result.json'), {'session': {'id': 's1'}})
        fc = FakeCloud(0, gone_at=1)
        url = quiet(cloud.push, fc, folder, 'processed-result', None, None, sleep=lambda s: None)
        self.assertEqual((url, fc.created), ('https://x/files/u2', 2))

    def test_folder_push_resumes_with_the_same_zip(self):
        import cloud
        folder = os.path.join(self.tmp, 'ride')
        os.makedirs(folder)
        write(os.path.join(folder, 'result.json'), {'session': {'id': 's1'}})
        with open(os.path.join(folder, 'big.bin'), 'wb') as f:
            f.write(os.urandom(600_000))
        fc = FakeCloud(0, fail=set(range(2, 200)))
        old = cloud.CHUNK, cloud.MIN_CHUNK
        cloud.CHUNK, cloud.MIN_CHUNK = 200_000, 200_000
        try:
            with self.assertRaises(SystemExit):
                quiet(cloud.push, fc, folder, 'processed-result', None, None, sleep=lambda s: None)
            state = os.path.join(self.tmp, 'ride.zip.tus.json')
            self.assertTrue(os.path.isfile(state))
            done = fc.server
            self.assertGreater(done, 0)
            fc.fail = set()
            url = quiet(cloud.push, fc, folder, 'processed-result', None, None, sleep=lambda s: None)
        finally:
            cloud.CHUNK, cloud.MIN_CHUNK = old
        self.assertEqual((url, fc.created), ('https://x/files/u1', 1))     # the same upload, resumed
        self.assertFalse(os.path.exists(state))
        self.assertGreater(fc.server, 600_000)


if __name__ == '__main__':
    unittest.main()
