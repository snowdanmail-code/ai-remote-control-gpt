package com.example.airemote

import java.io.InputStream

/** Host-JVM PNG fixtures. Android Kotlin's boot classpath omits java.desktop;
 * ImageIO is resolved at test runtime, without placing desktop APIs in the APK. */
object JvmFixtureDecoder {
    fun read(stream: InputStream): VisionRaster {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = stream.use { imageIo.getMethod("read", InputStream::class.java).invoke(null, it) }
            ?: error("Invalid PNG fixture")
        val type = image.javaClass
        val width = type.getMethod("getWidth").invoke(image) as Int
        val height = type.getMethod("getHeight").invoke(image) as Int
        val intType = Int::class.javaPrimitiveType!!
        val pixels = type.getMethod("getRGB", intType, intType, intType, intType,
            IntArray::class.java, intType, intType)
            .invoke(image, 0, 0, width, height, null, 0, width) as IntArray
        return VisionRaster(width, height, pixels)
    }
}
