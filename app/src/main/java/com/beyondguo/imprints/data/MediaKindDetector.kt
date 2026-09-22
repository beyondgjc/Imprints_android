package com.beyondguo.imprints.data

/** 根据文件头与声明 MIME 识别媒体类型；不依赖 Context，可在 JVM 上单测。 */
object MediaKindDetector {
    fun detect(header: ByteArray, mime: String, audio: Boolean): String {
        fun starts(value: String) = header.size >= value.length &&
            String(header, 0, value.length, Charsets.US_ASCII) == value
        val riff = starts("RIFF") && header.size >= 12
        val ftyp = header.size >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
        val brand = if (ftyp) String(header, 8, 4, Charsets.US_ASCII) else ""
        val gif = starts("GIF87a") || starts("GIF89a")
        val jpeg = header.size >= 3 && (header[0].toInt() and 0xff) == 0xff &&
            (header[1].toInt() and 0xff) == 0xd8 && (header[2].toInt() and 0xff) == 0xff
        val png = header.size >= 8 && header.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val webp = riff && header.size >= 12 && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
        val heif = ftyp && brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
        if (audio) {
            require(mime.startsWith("audio/")) { "不支持的音频类型" }
            val mp3 = starts("ID3") || (header.size >= 2 && (header[0].toInt() and 0xff) == 0xff &&
                (header[1].toInt() and 0xff and 0xe0) == 0xe0)
            val wav = riff && header.size >= 12 && String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
            val ogg = starts("OggS")
            val flac = starts("fLaC")
            require(mp3 || wav || ogg || flac || ftyp) { "音频文件头无效或格式暂不支持" }
            return "AUDIO"
        }
        if (gif && mime.startsWith("image/")) return "GIF"
        if ((jpeg || png || webp || heif) && mime.startsWith("image/")) return "IMAGE"
        val matroska = header.size >= 4 && header.copyOfRange(0, 4).contentEquals(
            byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
        if ((ftyp && !heif || matroska) && mime.startsWith("video/")) return "VIDEO"
        throw IllegalArgumentException("不支持的媒体类型")
    }
}
