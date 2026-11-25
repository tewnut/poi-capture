// The data the web app works on. A dataset is one imported thing: a result bundle from the agentic processing
// (result.json, schema poi-result/1, see docs/pipeline.md) or a raw session zip from the phone (schema poi-capture/3).

export type LatLon = { lat: number; lon: number }

/** A value read on the photos, or filled from the website / Google when the photos lack it (source "website" /
 *  "google"); compared with a matched Google place: google = "match" | "differs" (then googleValue is Google's).
 *  A street nobody gives is the road the photos were taken on (source "ride", a suggestion: fields.ts); ride =
 *  "differs" + rideValue when another source names a different street. */
export type Field = {
  value: string; photo?: number | string | null; sure?: 'high' | 'medium' | 'low'
  source?: 'photo' | 'google' | 'website' | 'ride'; google?: 'match' | 'differs'; googleValue?: string; note?: string | null
  website?: 'match' | 'differs'; websiteValue?: string
  ride?: 'match' | 'differs'; rideValue?: string
  /** an unclear photo reading made clear by the source; photoValue = what the photo showed */
  clarified?: boolean; photoValue?: string
}

export const FIELD_KEYS = ['name', 'phone', 'houseNumber', 'street', 'openingHours', 'website', 'signText', 'other', 'signCode'] as const
export type FieldKey = (typeof FIELD_KEYS)[number]
export const FIELD_LABEL: Record<FieldKey, string> = {
  name: 'Name', phone: 'Phone', houseNumber: 'House number', street: 'Street / area', openingHours: 'Opening hours',
  website: 'Website', signText: 'Sign text', other: 'Other text', signCode: 'Sign code',
}

/** What a result entry is (docs/sweep-targets.md): a place (shop), a house number, or a traffic sign. Missing = place. */
export type Layer = 'place' | 'address' | 'sign'
export const LAYERS: Layer[] = ['place', 'address', 'sign']
/** What a ride was captured for (chosen on the phone). Missing = shops. */
export type Target = 'shops' | 'addresses' | 'signs'

export type Sighting = { i: number; lat: number; lon: number; bearing?: number }

export type Pin = LatLon & { method?: string; uncertaintyM?: number; sightings?: Sighting[]; why?: string }
export type GooglePin = LatLon & { name?: string; distanceM?: number; nameMatch?: number; url?: string }

export type PlaceStatus = 'open' | 'closed' | 'for_rent' | 'under_construction' | 'gone' | 'unknown'

export type Place = {
  id: string
  folder?: string
  name: string
  category?: string | null
  status: PlaceStatus
  confidence?: string | null
  note?: string | null
  fields: Partial<Record<FieldKey, Field>>
  photos: { file: string; index?: number | null; cover?: boolean; why?: string | null; source?: 'website'; url?: string }[]
  pins: { algorithm?: Pin; contributor?: Pin; google?: GooglePin | null; agent?: Pin | null }
  google?: Record<string, any> | null
  layer?: Layer
  /** A traffic sign: QCVN 41 code and group, which way a driver facing it drives, left / right / over the road;
   *  lane (over the road: 1 = rightmost); post = the id of the pole / gantry it shares with other signs of the ride,
   *  order = its place on it (1 = top; on a gantry the leftmost board). See signPosts.ts. */
  sign?: { code?: string; group?: string; facingDeg?: number | null; side?: string; lane?: number | null
    post?: string | null; order?: number | null }
  /** An address: the place (shop) whose house number it is, when there is one. */
  placeRef?: string | null
  /** An address, optional (newer processing): where the number was read (plate | sign | painted | other), the old
   *  number on the same plate, other numbers on the gate, read only in part. Read by addresses.ts addressExtras. */
  numbering?: { source?: string | null; oldNumber?: string | null; altNumbers?: string[] | null; partial?: boolean } | null
}

export const layerOf = (p: Place): Layer => p.layer || 'place'

export type Task = {
  type: 'visit' | 'verify_removal' | 'removal'
  place: string | null
  name: string
  lat: number
  lon: number
  why: string
  photo?: string | null
}

export type Dataset = {
  id: string                       // session id (+ "-raw" for a raw zip)
  source: 'result' | 'raw'
  importedAt: string
  session: { id: string; name?: string; kind?: 'sweep' | 'poi'; holding?: string | null; startedAt?: string; street?: string | null; target?: Target }
  track: [number, number][]
  shots: [number, number, number, number | null][]   // [photo index, lat, lon, camera bearing]
  shotFiles?: Record<number, string>                  // raw zips: photo index -> file path
  places: Place[]
  tasks: Task[]
  files: string[]                                     // paths of the stored photos
}

export type Decision = 'todo' | 'confirmed' | 'rejected' | 'visit'
export type PinSource = 'algorithm' | 'contributor' | 'agent' | 'manual'

/** What the reviewer decided about one place (stored per dataset id + place id). */
export type Review = {
  decision: Decision
  fields: Partial<Record<FieldKey, { state: 'ok' | 'edited' | 'rejected'; value: string }>>
  category?: string
  pin?: LatLon & { source: PinSource }
  comment?: string
  photos?: PhotoEdits
  reviewer?: string
  at?: string
}

/** Crop rectangle as fractions of the photo: [x, y, width, height]. */
export type CropRect = [number, number, number, number]

/** The reviewer's changes to a place's photos. The imported photos are never changed: a removed one is only
 *  listed, a crop is a new image stored beside the original, an added one is stored under added/. */
export type PhotoEdits = {
  removed?: string[]                                        // imported photo files left out
  added?: string[]                                          // stored paths of photos the reviewer added
  crops?: Record<string, { rect: CropRect; file: string }>  // photo file -> its cropped copy
  cover?: string                                            // photo file chosen as the cover
}

/** A removal task's outcome: confirmed (the place is gone) or turned into a check on site. */
export type RemovalReview = { decision: 'todo' | 'confirmed' | 'verify' | 'dismissed'; reviewer?: string; at?: string }
