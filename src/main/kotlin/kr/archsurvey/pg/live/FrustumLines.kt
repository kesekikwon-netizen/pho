package kr.archsurvey.pg.live

import kr.archsurvey.pg.data.Still

/**
 * 0.0.10: 찍은 사진마다 카메라 사각뿔 선(x,y,z,r,g,b,a). 사진 장수 제한 없음 —
 * 새 사진만 덧붙이고(박싱 없는 FloatArray, 2배씩 늘림), 사진이 줄면(되돌리기) 처음부터 다시.
 */
class FrustumLines {
    var data = FloatArray(0); private set
    var floats = 0; private set
    var stills = 0; private set
    private val oc = floatArrayOf(1f, 0.62f, 0.15f, 0.95f)

    /** @return 바뀌었으면 true */
    fun sync(all: List<Still>, toMatrix: (DoubleArray) -> FloatArray): Boolean {
        if (all.size == stills) return false
        if (all.size < stills) { floats = 0; stills = 0 }
        for (i in stills until all.size) add(all[i], toMatrix)
        stills = all.size
        return true
    }

    private fun push(a: FloatArray, c: FloatArray) {
        if (floats + 7 > data.size) data = data.copyOf(maxOf(1024, data.size * 2))
        System.arraycopy(a, 0, data, floats, 3); System.arraycopy(c, 0, data, floats + 3, 4); floats += 7
    }

    private fun add(st: Still, toMatrix: (DoubleArray) -> FloatArray) {
        val p = st.pose ?: return
        val m = toMatrix(p)
        fun w(x: Float, y: Float, z: Float) = floatArrayOf(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])
        val d = 0.12f; val hw = (d * st.w / 2 / st.fx).toFloat(); val hh = (d * st.h / 2 / st.fy).toFloat()
        val o = w(0f, 0f, 0f); val a = w(-hw, hh, -d); val b = w(hw, hh, -d); val c = w(hw, -hh, -d); val e = w(-hw, -hh, -d)
        for ((u, v) in listOf(o to a, o to b, o to c, o to e, a to b, b to c, c to e, e to a)) { push(u, oc); push(v, oc) }
    }
}
