import http from './http'

export interface CaptchaResult {
  key: string
  imageBase64: string
}

export async function fetchCaptcha(): Promise<CaptchaResult> {
  const res = await http.get('/captcha')
  return res.data.data as CaptchaResult
}

export function toDataUrl(imageBase64: string): string {
  return imageBase64.startsWith('data:') ? imageBase64 : `data:image/png;base64,${imageBase64}`
}
