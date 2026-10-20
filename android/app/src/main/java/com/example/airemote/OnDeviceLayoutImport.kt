package com.example.airemote

import org.json.JSONObject

/** Explicit on-device JSON import. No hardcoded app identity, screen layout or cloud upload. */
object OnDeviceLayoutImport {
    private const val MAX_FILE_BYTES = 32 * 1024
    fun parse(bytes: ByteArray): RealFrameCalibration {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES) { "Размер файла калибровки недопустим" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == 1) { "Неподдерживаемая схема" }
        val regions = root.getJSONArray("zones")
        require(regions.length() in 2..52) { "Необходимо от 2 до 52 областей" }
        val zones = (0 until regions.length()).map { i ->
            val zone = regions.getJSONObject(i)
            val rect = zone.getJSONArray("bounds")
            require(rect.length() == 4) { "Для области нужны четыре координаты" }
            val box = NormalizedTextBox(rect.getInt(0), rect.getInt(1), rect.getInt(2), rect.getInt(3))
            val role = RealFieldRole.valueOf(zone.getString("role"))
            val seat = if (zone.has("seatIndex") && !zone.isNull("seatIndex")) zone.getInt("seatIndex") else null
            RealFrameZone(role, box, seat)
        }
        val profile = RealFrameCalibration(root.getString("layoutKey"), root.getString("expectedPackage"),
            root.getInt("frameWidth"), root.getInt("frameHeight"), zones)
        require(profile.valid() && profile.seatVisualCalibration().isValid()) { "Геометрия неверна или пересекается" }
        return profile
    }
}
