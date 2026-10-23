import http from '../api/http'

/** 上传图片到 /api/file/upload，返回可访问的 /static/** URL。 */
export async function uploadImage(file: File): Promise<string> {
  const fd = new FormData()
  fd.append('file', file)
  const res = await http.post('/file/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return (res.data.data as { url: string }).url
}
