<script setup lang="ts">
/**
 * 高德物流地图（买家/商家共用实现，两个工程各自持有一份）。
 *
 * <p>从轨迹中提取 ROUTE 折线 + 最新 STATUS 坐标，渲染路线、起终点与当前位置。
 * 无 Key / 脚本加载失败 / 无路线时降级为文字提示，不影响页面其它操作。</p>
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { extractRoute, latestPosition, pointAtRatio, statusRatio } from '../utils/logisticsMap'
import type { LogisticsTraceLike, LngLat } from '../utils/logisticsMap'

const props = defineProps<{
  traces: LogisticsTraceLike[]
  shipment?: { currentStatus?: string | null } | null
}>()

/* env 在构建时静态替换；未配置时走降级分支 */
const AMAP_KEY = import.meta.env.VITE_AMAP_JS_KEY as string | undefined
const AMAP_SECURITY = import.meta.env.VITE_AMAP_SECURITY_CODE as string | undefined

const container = ref<HTMLDivElement | null>(null)
const ready = ref(false)
const failed = ref(false)

const route = computed<LngLat[] | null>(() => extractRoute(props.traces))
const current = computed<LngLat | null>(() => {
  const explicit = latestPosition(props.traces)
  if (explicit) return explicit
  const points = route.value
  return points ? pointAtRatio(points, statusRatio(props.shipment?.currentStatus)) : null
})
const usable = computed(() => !!AMAP_KEY && !!route.value && !failed.value)

// 脚本单例：同一页面多个地图组件只加载一次
let amapPromise: Promise<any> | null = null
let map: any = null

function loadAMap(): Promise<any> {
  if (!amapPromise) {
    amapPromise = new Promise<any>((resolve, reject) => {
      if (!AMAP_KEY) {
        reject(new Error('未配置高德地图 Key'))
        return
      }
      const w = window as unknown as { AMap?: any; _AMapSecurityConfig?: unknown }
      // 必须先设置安全密钥，再加载脚本（JS API 2.0 要求）
      if (AMAP_SECURITY) {
        w._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY }
      }
      if (w.AMap) {
        resolve(w.AMap)
        return
      }
      const script = document.createElement('script')
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${AMAP_KEY}`
      script.async = true
      script.onload = () => (w.AMap ? resolve(w.AMap) : reject(new Error('高德地图脚本未就绪')))
      script.onerror = () => reject(new Error('高德地图脚本加载失败'))
      document.head.appendChild(script)
    })
  }
  return amapPromise
}

function destroyMap(): void {
  if (map) {
    map.destroy()
    map = null
  }
  ready.value = false
}

async function render(): Promise<void> {
  await nextTick()
  if (!usable.value || !container.value) return
  try {
    const AMap = await loadAMap()
    const points = route.value
    const position = current.value
    if (!container.value || !points || !position) return

    destroyMap()
    map = new AMap.Map(container.value, { zoom: 11, viewMode: '2D', resizeEnable: true })
    const line = new AMap.Polyline({
      path: points,
      strokeColor: '#8b5e34',
      strokeWeight: 5,
      strokeOpacity: 0.85,
      showDir: true,
      lineJoin: 'round',
    })
    map.add(line)
    map.add(new AMap.Marker({ position: points[0], title: '发货地' }))
    map.add(new AMap.Marker({ position: points[points.length - 1], title: '收货地' }))
    map.add(
      new AMap.CircleMarker({
        center: position,
        radius: 8,
        strokeColor: '#ffffff',
        strokeWeight: 2,
        fillColor: '#c0392b',
        fillOpacity: 1,
        zIndex: 120,
        title: '当前位置',
      }),
    )
    map.setFitView([line])
    ready.value = true
  } catch {
    failed.value = true
    destroyMap()
  }
}

watch(
  () => [props.traces, props.shipment?.currentStatus],
  () => {
    void render()
  },
  { deep: true, immediate: true },
)

onBeforeUnmount(destroyMap)
</script>

<template>
  <div class="logi-map">
    <div v-if="usable" ref="container" class="logi-map__canvas" :class="{ 'is-ready': ready }"></div>
    <div v-else class="logi-map__fallback">
      {{ route ? '地图未配置或加载失败，请查看下方文字轨迹' : '暂无路线数据，请查看下方文字轨迹' }}
    </div>
  </div>
</template>

<style scoped>
.logi-map {
  position: relative;
  margin-bottom: 14px;
  border: 1px solid var(--wood-border, #e5dccf);
  border-radius: 10px;
  overflow: hidden;
  background: #faf6ef;
}

.logi-map__canvas {
  width: 100%;
  height: 240px;
  opacity: 0;
  transition: opacity 0.25s ease;
}

.logi-map__canvas.is-ready {
  opacity: 1;
}

.logi-map__fallback {
  padding: 18px 16px;
  font-size: 13px;
  color: var(--wood-accent, #8a7a66);
  text-align: center;
}
</style>
