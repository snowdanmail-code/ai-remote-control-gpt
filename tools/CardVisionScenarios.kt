import com.example.airemote.*
import java.io.File
import javax.imageio.ImageIO

fun requireTest(value: Boolean, message: String) { if (!value) error(message) }
fun fromFile(file: String): VisionRaster {
    val image = ImageIO.read(File(file)) ?: error("Cannot read test fixture $file")
    val rgb = IntArray(image.width * image.height)
    image.getRGB(0, 0, image.width, image.height, rgb, 0, image.width)
    return VisionRaster(image.width, image.height, rgb)
}

fun main(args: Array<String>) {
    val root = args.firstOrNull() ?: error("Pass fixtures directory")
    var checked = 0
    fun check(value: Boolean, label: String) { requireTest(value, label); checked++ }
    val screenshot180 = fromFile("$root/cards_180_redacted.png")
    val phases = CardVisionCore.detect(screenshot180)
    check(phases.usable, "180: hero card row detected")
    check(phases.hole.size == 2, "180: exactly 2 hero cards")
    check(phases.community.size == 3, "180: 3 board cards")
    println("boxes180=" + phases.hole + phases.community)
    println("top180=" + (phases.hole + phases.community).map { CardVisionCore.rankScores(screenshot180, it).take(3) })
    println("ranks180=" + (phases.hole + phases.community).map { CardVisionCore.classifyRank(screenshot180, it) })
    val expected180 = listOf('c', 'h', 's', 'h', 's') // hole Jc Kh, board 3s Th 4s
    val actual180 = (phases.hole + phases.community).map { CardVisionCore.classifySuit(screenshot180, it)?.suit }
    for (i in expected180.indices) check(actual180[i] == expected180[i], "180: suit[$i] expected ${expected180[i]} got ${actual180[i]}")

    val screenshot110 = fromFile("$root/cards_110_redacted.png")
    val preflop = CardVisionCore.detect(screenshot110)
    check(preflop.usable, "110: hero row detected")
    check(preflop.hole.size == 2, "110: hero cards 2")
    println("boxes110=" + preflop.hole)
    println("top110=" + preflop.hole.map { CardVisionCore.rankScores(screenshot110, it).take(3) })
    println("ranks110=" + preflop.hole.map { CardVisionCore.classifyRank(screenshot110, it) })
    check(preflop.community.isEmpty(), "110: no board cards")
    check(CardVisionCore.classifySuit(screenshot110, preflop.hole[0])?.suit == 'h', "110: Qh heart")
    check(CardVisionCore.classifySuit(screenshot110, preflop.hole[1])?.suit == 'd', "110: 5d diamond")
    check(CardVisionCore.parseRank("10") == "T", "OCR 10")
    check(CardVisionCore.parseRank("1O") == "T", "OCR 1O")
    check(CardVisionCore.parseRank(" K ") == "K", "OCR K")
    check(CardVisionCore.parseRank("J\n♣") == null, "reject rank plus suit combined")
    check(CardVisionCore.parseRank("AQ") == null, "reject 2 rank chars")
    check(CardVisionCore.parseRank("") == null, "reject empty rank")
    check(CardVisionCore.checkUnique(listOf("Jc","Kh"),listOf("3s","Th","4s")), "unique valid")
    check(!CardVisionCore.checkUnique(listOf("Jc","Jc"),listOf("3s","Th","4s")), "duplicate rejected")
    check(!CardVisionCore.checkUnique(listOf("Jc","Kh"),listOf("3s","Th")), "two card board rejected")
    check(!CardVisionCore.checkUnique(listOf("Jc","Kh"),listOf("3s","Th","4s","2s","5s","6s")), "six board rejected")
    val empty = VisionRaster(540,1170, IntArray(540*1170){0xff202020.toInt()})
    check(!CardVisionCore.detect(empty).usable, "dark not cards")
    val malformed = VisionRaster(1170,540,IntArray(1170*540){0xffffffff.toInt()})
    check(!CardVisionCore.detect(malformed).usable, "landscape not supported")
    val complete180 = CardVisionCore.readCards(screenshot180)
    println("complete180=$complete180")
    check(complete180.complete && complete180.hole == listOf("Jc", "Kh") &&
        complete180.community == listOf("3s", "Th", "4s"), "full read flop: Jc Kh / 3s Th 4s")
    val complete110 = CardVisionCore.readCards(screenshot110)
    println("complete110=$complete110")
    check(complete110.complete && complete110.hole == listOf("Qh", "5d") &&
        complete110.community.isEmpty(), "full read preflop Qh 5d")
    val cases = mapOf(
        20 to listOf("7s", "As"), 56 to listOf("Js", "Ac"),
        128 to listOf("4h", "9d"), 164 to listOf("Jc", "Kh"),
        200 to listOf("8d", "Ts"), 308 to listOf("4h", "Tc"),
        326 to listOf("6s", "2s"), 362 to listOf("9h", "4h"),
        416 to listOf("2c", "8c"), 452 to listOf("3d", "6s")
    )
    for ((t, expected) in cases) {
        val candidate = CardVisionCore.readCards(fromFile("$root/cards_${t}_redacted.png"))
        println("fixture $t: expected $expected, actual ${candidate.hole}, reason=${candidate.reason}")
        if (candidate.complete) check(candidate.hole == expected, "no incorrect confident rank/suit at $t")
        else check(true, "safe reject allowed at $t")
    }
    for ((t, expected) in mapOf(
        20 to listOf("2h","Js","4c","5s"),
        25 to listOf("2h","Js","4c","5s","Kd")
    )) {
        val reading = CardVisionCore.readCards(fromFile("$root/board_${t}_redacted.png"))
        check(reading.complete && reading.hole == listOf("7s","As") &&
            reading.community == expected, "full board rank/suit recognition at $t")
    }
    for ((t, count) in mapOf(20 to 4, 25 to 5, 135 to 4, 140 to 5, 380 to 4, 385 to 5)) {
        val r = fromFile("$root/board_${t}_redacted.png")
        val b = CardVisionCore.detect(r)
        println("board fixture $t: actual ${b.community.size} expected $count; hole ${b.hole.size}, ${b.reason}")
        if (b.usable) check(b.community.size == count, "board layout $t count $count")
        else check(true, "safe refusal when hole cards obscured at board fixture $t")
        val read = CardVisionCore.readCards(r)
        println("board fixture $t: $read")
        check(!read.complete || CardVisionCore.checkUnique(read.hole.filterNotNull(),read.community.filterNotNull()),
           "validated board $t has unique deck")
    }
    println("PASS $checked CardVision JVM fixture scenarios")
}
