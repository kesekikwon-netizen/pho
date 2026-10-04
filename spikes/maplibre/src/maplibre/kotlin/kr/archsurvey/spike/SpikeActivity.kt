package kr.archsurvey.spike

import android.app.Activity
import android.os.Bundle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import okhttp3.OkHttpClient

/**
 * WP-9 스파이크: **컴파일되는 것**만 증명한다(API 존재). 이 코드는 실기기/에뮬레이터에서 실행해 본 적 없다.
 * extras: "mbtiles" = 절대 경로(.mbtiles), "pmtiles" = 절대 경로(.pmtiles)
 */
class SpikeActivity : Activity() {
    private lateinit var map: MapView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // (1) HTTP: 기본 클라이언트는 dispatcher 만 설정(캐시 없음). 필요하면 캐시 없는 클라이언트를 명시 지정
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().cache(null).build())
        MapLibre.getInstance(this)
        // (2) 디스크 캐시(주변 캐시) 크기 0 + 비우기: 효과는 실기기 확인 전
        val om = OfflineManager.getInstance(this)
        om.setMaximumAmbientCacheSize(0L, object : OfflineManager.FileSourceCallback { override fun onSuccess() {}; override fun onError(message: String) {} })
        om.clearAmbientCache(object : OfflineManager.FileSourceCallback { override fun onSuccess() {}; override fun onError(message: String) {} })
        map = MapView(this); setContentView(map); map.onCreate(b)
        map.getMapAsync { m ->
            m.cameraPosition = CameraPosition.Builder().target(LatLng(37.0, 127.0)).zoom(12.0).build()
            val mb = intent.getStringExtra("mbtiles"); val pm = intent.getStringExtra("pmtiles")
            // (3) 빈 스타일 + 로컬 래스터 소스. mbtiles:///절대경로 / pmtiles://file:///절대경로 (문자열은 libmaplibre.so 안에서 확인됨, 동작은 미확인)
            val style = Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#FAF9F5"}}]}""")
            m.setStyle(style) { s ->
                if (mb != null) {
                    s.addSource(RasterSource("up_mb", TileSet("2.2.0", "mbtiles://$mb"), 256))
                    s.addLayer(RasterLayer("up_mb_l", "up_mb").withProperties(PropertyFactory.rasterOpacity(0.5f)))
                }
                if (pm != null) {
                    s.addSource(RasterSource("up_pm", "pmtiles://file://$pm", 256))
                    s.addLayer(RasterLayer("up_pm_l", "up_pm").withProperties(PropertyFactory.rasterOpacity(0.5f)))
                }
                // (4) 투명도 런타임 변경
                (s.getLayer("up_mb_l") as? RasterLayer)?.setProperties(PropertyFactory.rasterOpacity(0.3f))
            }
        }
    }
    override fun onStart() { super.onStart(); map.onStart() }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onStop() { super.onStop(); map.onStop() }
    override fun onLowMemory() { super.onLowMemory(); map.onLowMemory() }
    override fun onDestroy() { super.onDestroy(); map.onDestroy() }
}
