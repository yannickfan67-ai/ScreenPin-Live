package com.screenpin.live

data class NPoint(val x: Float, val y: Float)

data class Quad(
    val tl: NPoint,
    val tr: NPoint,
    val br: NPoint,
    val bl: NPoint
) {
    fun asList() = listOf(tl, tr, br, bl)

    companion object {
        val FULL = Quad(
            NPoint(0f, 0f), NPoint(1f, 0f),
            NPoint(1f, 1f), NPoint(0f, 1f)
        )
    }
}
