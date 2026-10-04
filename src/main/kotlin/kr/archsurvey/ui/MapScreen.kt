package kr.archsurvey.ui

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.archsurvey.map.MapBase
import kr.archsurvey.map.MapLayers
import kr.archsurvey.map.TileLayerSpec
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/**
 * 배경 지도 (osmdroid). V-World 위성/지적도 타일.
 * 약관 확인 전이므로 타일을 디스크에 저장하지 않는다(파일시스템 캐시 모듈 없음, 메모리 캐시만).
 */
private fun tileSource(spec: TileLayerSpec) = object : OnlineTileSourceBase("vw_${spec.id}", MapLayers.MIN_ZOOM, MapLayers.MAX_ZOOM, 256, "." + spec.ext, arrayOf("https://api.vworld.kr/")) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        spec.urlOf(MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex))
}

private fun tilesOverlay(ctx: Context, spec: TileLayerSpec): TilesOverlay {
    val src = tileSource(spec)
    val provider = MapTileProviderArray(src, null, arrayOf(MapTileDownloader(src, null, NetworkAvailabliltyCheck(ctx))))
    val o = TilesOverlay(provider, ctx)
    o.loadingBackgroundColor = android.graphics.Color.TRANSPARENT
    o.loadingLineColor = android.graphics.Color.TRANSPARENT
    if (spec.alpha < 1f) o.setColorFilter(ColorMatrixColorFilter(ColorMatrix().apply { setScale(1f, 1f, 1f, spec.alpha) }))
    return o
}

@Composable
fun MapScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val pts by vm.points.collectAsStateWithLifecycle()
    val g by vm.engine.gnss.collectAsStateWithLifecycle()
    val gga = g.gga
    val fallback = MapLayers.fallbackMessage(vm.mapBase, vm.mapKey)
    val layers = remember(vm.mapBase, vm.cadOverlay, vm.mapKey, vm.mapDomain) { MapLayers.resolve(vm.mapBase, vm.cadOverlay, vm.mapKey, vm.mapDomain) }
    var centered by remember { mutableStateOf(false) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MapBase.entries.forEach { b -> Chip(b.labelKo, vm.mapBase == b) { vm.mapBase = b; vm.saveBasics() } }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.mapBase == MapBase.SATELLITE) Chip("지적도 겹쳐보기", vm.cadOverlay) { vm.cadOverlay = !vm.cadOverlay; vm.saveBasics() }
            OutlineBigButton("내 위치", { gga?.takeIf { it.hasPosition }?.let { mapRef?.controller?.animateTo(GeoPoint(it.lat!!, it.lon!!)) } }, height = 48.dp)
        }
        if (fallback != null) StatusBanner(fallback, Kind.INFO, Modifier.padding(12.dp))
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                Configuration.getInstance().userAgentValue = c.packageName
                MapView(c).apply {
                    setMultiTouchControls(true)
                    minZoomLevel = MapLayers.MIN_ZOOM.toDouble(); maxZoomLevel = MapLayers.MAX_ZOOM.toDouble()
                    controller.setZoom(17.0)
                    controller.setCenter(GeoPoint(36.5, 127.8))
                    mapRef = this
                }
            },
            update = { mv ->
                mv.overlays.clear()
                layers.forEach { mv.overlays.add(tilesOverlay(ctx, it)) }
                pts.forEach { pw ->
                    val p = pw.point
                    mv.overlays.add(Marker(mv).apply { position = GeoPoint(p.lat, p.lon); title = p.pointId; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
                }
                if (gga?.hasPosition == true) {
                    val gp = GeoPoint(gga.lat!!, gga.lon!!)
                    mv.overlays.add(Marker(mv).apply { position = gp; title = "현재 위치"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) })
                    if (!centered) { mv.controller.setCenter(gp); centered = true }
                } else if (!centered) pts.firstOrNull()?.let { mv.controller.setCenter(GeoPoint(it.point.lat, it.point.lon)); centered = true }
                mv.invalidate()
            },
            onRelease = { it.onDetach() },
        )
    }
    DisposableEffect(Unit) { onDispose { mapRef?.onDetach() } }
}
