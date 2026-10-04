package kr.archsurvey.ui

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.archsurvey.map.LocalProjection
import kr.archsurvey.map.MapSurface
import kr.archsurvey.map.MapViewState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import kotlin.math.cos
import kotlin.math.ln

/**
 * MapLibre 기반 지도(옵트인 빌드 `-PwithMapLibre` 에서만 컴파일). **컴파일만 확인했고 실기기/에뮬레이터에서 그려 본 적 없다.**
 *
 * 구조: 바탕은 MapLibre(지금은 빈 스타일 — 타일 소스 연결 전), 그 위에 기존 Canvas 가 점·꼬리·현재 위치·업로드 도면을 그린다
 * (글자는 MapLibre 글리프 소스가 필요해 Compose 쪽에서 그리는 편이 현실적). 카메라는 앱 상태(center/dpPerM)로만 움직이고 MapLibre 자체 제스처는 끈다.
 * 네트워크 요청이 없는 스타일이라 디스크 캐시에 쓸 것이 없지만, 캐시 크기 0 설정도 호출한다(효과는 미검증).
 */
class MapLibreMapSurface(private val projection: LocalProjection) : MapSurface {
    private val overlay = CanvasMapSurface(showBackdrop = false)

    @SuppressLint("ComposableNaming")
    @Composable
    override fun Draw(state: MapViewState, modifier: Modifier) {
        val ctx = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val mapView = remember {
            MapLibre.getInstance(ctx.applicationContext)
            OfflineManager.getInstance(ctx.applicationContext).setMaximumAmbientCacheSize(0L, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) {}
            })
            MapView(ctx).also { it.onCreate(Bundle()) }
        }
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        DisposableEffect(lifecycle, mapView) {
            val obs = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> {}
                }
            }
            lifecycle.addObserver(obs)
            // 이미 시작된 상태로 들어온 경우를 따라잡는다
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
            onDispose { lifecycle.removeObserver(obs); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
        }
        LaunchedEffect(mapView) {
            mapView.getMapAsync { m ->
                m.uiSettings.setAllGesturesEnabled(false)
                m.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#F4F1E6"}}]}""")) { map = m }
            }
        }
        LaunchedEffect(map, state.center, state.dpPerM) {
            val m = map ?: return@LaunchedEffect
            val (lat, lon) = projection.toLatLon(state.center)
            // MapLibre 는 512 화소(dp) 타일: 줌 z 에서 1 dp = 78271.517 * cos(lat) / 2^z m  →  dpPerM = 2^z / (78271.517 cos(lat))
            val z = ln(78271.517 * cos(Math.toRadians(lat)) * state.dpPerM) / ln(2.0)
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(z.coerceIn(1.0, 22.0)).build()))
        }
        Box(modifier) {
            AndroidView({ mapView }, Modifier.fillMaxSize())
            overlay.Draw(state, Modifier.fillMaxSize())
        }
    }
}
