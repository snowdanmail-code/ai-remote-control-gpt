import com.example.airemote.*
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val base = File(args.singleOrNull() ?: "tools/fixtures/turn")
    var checks = 0
    fun checkThat(ok: Boolean, text: String) { check(ok) { text }; checks++ }
    fun load(name: String): VisionRaster {
        val image = ImageIO.read(File(base, "$name.png"))
        checkThat(image.width == 540 && image.height == 70, "Fixture shape $name")
        val pixels = IntArray(540 * 1170) { 0xff202020.toInt() }
        image.getRGB(0, 0, 540, 70, pixels, 1085 * 540, 540)
        return VisionRaster(540, 1170, pixels)
    }
    val samples = listOf(
        "preaction_a" to TurnDisplayState.PREACTION,
        "preaction_b" to TurnDisplayState.PREACTION,
        "preaction_c" to TurnDisplayState.PREACTION,
        "empty_between" to TurnDisplayState.WAITING,
        "empty_b" to TurnDisplayState.WAITING,
        "active_call_raise" to TurnDisplayState.READY,
        "active_check_bet_disabled" to TurnDisplayState.READY,
        "active_check_bet" to TurnDisplayState.READY,
        "sizing_confirm" to TurnDisplayState.BET_SIZING
    )
    val read = samples.associate { (name, expected) ->
        val prediction = TurnControlsVision.read(load(name))
        checkThat(prediction.state == expected, "$name: expected $expected, got ${prediction.state}; $prediction")
        if (expected == TurnDisplayState.READY) {
            checkThat(VisibleButtonRole.RED_FOLD in prediction.roles, "Active red $name")
            checkThat(VisibleButtonRole.GREEN_CHECK_OR_CALL in prediction.roles, "Active green $name")
        }
        if (expected == TurnDisplayState.PREACTION || expected == TurnDisplayState.WAITING)
            checkThat(prediction.roles.isEmpty(), "Passive cannot provide actions $name")
        name to prediction
    }
    checkThat(VisibleButtonRole.GOLD_BET_OR_RAISE in read.getValue("active_call_raise").roles,
        "Raise option is coloured")
    checkThat(VisibleButtonRole.GOLD_BET_OR_RAISE !in read.getValue("active_check_bet_disabled").roles,
        "Disabled bet button must not be active")
    checkThat(read.getValue("sizing_confirm").roles == setOf(VisibleButtonRole.GOLD_CONFIRM_SIZE),
        "Sizing is not a fresh turn")
    checkThat(TurnControlsVision.read(VisionRaster(540, 100, IntArray(54000))).state ==
        TurnDisplayState.UNKNOWN, "Wrong aspect must fail closed")

    fun sig(seq: Long, ms: Long, reading: TurnVisualReading, epoch: Long = 1,
            sourceVerified: Boolean = false) = TurnVisualSignal(ms, epoch, seq, sourceVerified, reading)
    val tracker = TurnDisplayConsistency()
    val active = read.getValue("active_call_raise")
    val inactive = read.getValue("preaction_a")
    checkThat(tracker.accept(sig(1, 1000, active), 1000) == null, "One frame is untrusted")
    val confirmed = tracker.accept(sig(2, 2200, active), 2200)
    checkThat(confirmed?.state == TurnDisplayState.READY, "Two independent matching frames confirm UI")
    checkThat(confirmed?.authorizedToAct == false && confirmed.sourceVerified == false,
        "Confirmed UI does not authorize gestures")
    checkThat(tracker.current(3901) == null, "Old turn is stale")
    checkThat(tracker.accept(sig(3, 4200, inactive), 4200) == null, "Old READY invalidated immediately")
    checkThat(tracker.current(4200) == null, "No cached READY while transition pending")
    checkThat(tracker.accept(sig(4, 5300, inactive), 5300)?.state == TurnDisplayState.PREACTION,
        "Preaction needs two frames")
    checkThat(tracker.accept(sig(4, 5500, active), 5500) == null, "Duplicate sequence blocked")
    checkThat(tracker.accept(sig(5, 6000, active), 6000) == null, "Reconfirm after invalid")
    checkThat(tracker.accept(sig(6, 7000, active), 7000)?.state == TurnDisplayState.READY,
        "Reconfirmed visible turn")
    checkThat(tracker.accept(sig(7, 7000, active, epoch=2), 7000) == null,
        "Switching capture epoch requires reconfirm")
    checkThat(tracker.accept(sig(8, 8400, active, epoch=2), 8400)?.authorizedToAct == false,
        "Even verified pixels do not authorize commands")
    tracker.reset()
    checkThat(tracker.current(8400) == null, "STOP reset clears turn")
    checkThat(tracker.accept(sig(9, 1, active), 3000) == null, "Stale frame blocked")
    val unknown = TurnVisualReading(TurnDisplayState.UNKNOWN,emptySet(),"bad",0.0,0.0,0.0)
    checkThat(tracker.accept(sig(10, 4000, unknown), 4000) == null, "Unknown never confirms")
    println("PASS: $checks Kotlin action-strip and turn-consensus assertions")
}
