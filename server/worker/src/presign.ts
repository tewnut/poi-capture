// Presigned R2 (S3 API) URLs: AWS Signature Version 4, query-string auth, region "auto". The phone PUTs its parts
// straight to R2 with these (../README.md, "Direct uploads"); only the Worker holds the R2 access key.

const enc = new TextEncoder()

export type R2Keys = { accountId: string; bucket: string; accessKeyId: string; secretAccessKey: string }

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('')
const rfc3986 = (s: string) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())

async function hmac(key: ArrayBuffer | Uint8Array, data: string): Promise<ArrayBuffer> {
  const k = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return crypto.subtle.sign('HMAC', k, enc.encode(data))
}

/**
 * A URL for [method] on [key] in the bucket, valid [expires] seconds from [now]; [query]: extra parameters that are
 * part of the signature (partNumber, uploadId). Only the host header is signed, the body is UNSIGNED-PAYLOAD.
 */
export async function presign(k: R2Keys, method: string, key: string, query: Record<string, string>, expires: number,
  now = new Date()): Promise<string> {
  const host = `${k.accountId}.r2.cloudflarestorage.com`
  const amz = now.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
  const day = amz.slice(0, 8)
  const scope = `${day}/auto/s3/aws4_request`
  const path = '/' + k.bucket + '/' + key.split('/').map(rfc3986).join('/')
  const q: Record<string, string> = { ...query, 'X-Amz-Algorithm': 'AWS4-HMAC-SHA256', 'X-Amz-Credential': `${k.accessKeyId}/${scope}`,
    'X-Amz-Date': amz, 'X-Amz-Expires': String(expires), 'X-Amz-SignedHeaders': 'host' }
  const qs = Object.keys(q).sort().map((n) => `${rfc3986(n)}=${rfc3986(q[n])}`).join('&')
  const creq = [method, path, qs, `host:${host}\n`, 'host', 'UNSIGNED-PAYLOAD'].join('\n')
  const sts = ['AWS4-HMAC-SHA256', amz, scope, hex(await crypto.subtle.digest('SHA-256', enc.encode(creq)))].join('\n')
  let sk: ArrayBuffer = await hmac(enc.encode('AWS4' + k.secretAccessKey), day)
  for (const p of ['auto', 's3', 'aws4_request']) sk = await hmac(sk, p)
  return `https://${host}${path}?${qs}&X-Amz-Signature=${hex(await hmac(sk, sts))}`
}
