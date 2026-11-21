package com.avatarmocap.tracking

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Vector 3D simple (convención de Unity: X derecha, Y arriba, Z adelante). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(dot(this))
    fun norm(): V3 { val l = len(); return if (l < 1e-6f) V3(0f, 0f, 0f) else this * (1f / l) }
}

/** Cuaternión (x, y, z, w) con las mismas fórmulas que Unity. */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(q: Quat) = Quat(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z,
    )

    fun inverse() = Quat(-x, -y, -z, w)

    fun rotate(v: V3): V3 {
        val qv = V3(x, y, z)
        val t = qv.cross(v) * 2f
        return v + t * w + qv.cross(t)
    }

    fun slerpTo(o: Quat, t: Float): Quat {
        var d = x * o.x + y * o.y + z * o.z + w * o.w
        val b = if (d < 0) { d = -d; Quat(-o.x, -o.y, -o.z, -o.w) } else o
        val r = Quat(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t)
        val l = sqrt(r.x * r.x + r.y * r.y + r.z * r.z + r.w * r.w).coerceAtLeast(1e-6f)
        return Quat(r.x / l, r.y / l, r.z / l, r.w / l)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun axisAngle(axis: V3, deg: Float): Quat {
            val a = Math.toRadians(deg.toDouble()).toFloat() / 2f
            val n = axis.norm()
            val s = sin(a)
            return Quat(n.x * s, n.y * s, n.z * s, cos(a))
        }

        /** Rotación mínima que lleva la dirección [a] a la [b]. */
        fun fromTo(a: V3, b: V3): Quat {
            val u = a.norm(); val v = b.norm()
            val d = u.dot(v).coerceIn(-1f, 1f)
            if (d > 0.99999f) return IDENTITY
            if (d < -0.99999f) {
                var axis = V3(1f, 0f, 0f).cross(u)
                if (axis.len() < 1e-3f) axis = V3(0f, 1f, 0f).cross(u)
                return axisAngle(axis, 180f)
            }
            val axis = u.cross(v)
            return axisAngle(axis, Math.toDegrees(acos(d).toDouble()).toFloat())
        }

        /** Desde una matriz de rotación dada por sus columnas. */
        fun fromColumns(c0: V3, c1: V3, c2: V3): Quat {
            val m00 = c0.x; val m10 = c0.y; val m20 = c0.z
            val m01 = c1.x; val m11 = c1.y; val m21 = c1.z
            val m02 = c2.x; val m12 = c2.y; val m22 = c2.z
            val tr = m00 + m11 + m22
            return if (tr > 0f) {
                val s = sqrt(tr + 1f) * 2f
                Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(max(1e-6f, 1f + m00 - m11 - m22)) * 2f
                Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            } else if (m11 > m22) {
                val s = sqrt(max(1e-6f, 1f + m11 - m00 - m22)) * 2f
                Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            } else {
                val s = sqrt(max(1e-6f, 1f + m22 - m00 - m11)) * 2f
                Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }
        }
    }
}
