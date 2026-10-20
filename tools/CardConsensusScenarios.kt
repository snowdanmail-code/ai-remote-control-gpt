import com.example.airemote.*

fun check(value: Boolean, why: String) { if (!value) error(why) }
fun valid(hole: List<String> = listOf("Jc","Kh"), board: List<String> = listOf("3s","Th","4s")) =
    CardReading(hole, board, true, 0.95, "sample")
fun signal(epoch: Long, seq: Long, time: Long, cards: CardReading = valid()) =
    CardVisionSignal(time, epoch, seq, cards)
fun main() {
 var n=0
 fun t(ok:Boolean,why:String) { check(ok,why);n++ }
 val gate=CardReadingConsistency()
 t(gate.accept(signal(1,1,1000),1050)==null, "first observation not confirmed")
 t(gate.accept(signal(1,2,2100),2150)!=null, "second observation confirmed")
 t(gate.current(2300)!=null, "fresh observation usable for UI")
 t(gate.current(3400)==null, "stale observation never current")
 t(gate.accept(signal(1,2,3000),3100)==null,"duplicate sequence blocked")
 t(gate.accept(signal(1,3,4200),4250)!=null,"2nd new observation allowed")
 gate.reset()
 t(gate.current(4250)==null,"STOP invalidates consensus")
 t(gate.accept(signal(1,4,5000),6301)==null,"age > 1200 refused")
 t(gate.accept(signal(1,5,6000),6050)==null,"new candidate starts")
 t(gate.accept(signal(2,6,7000),7050)==null,"epoch change requires two")
 t(gate.accept(signal(2,7,8100),8150)!=null,"epoch second confirms")
 t(gate.accept(signal(2,8,8200,valid(listOf("9d","Ah"))),8250)==null,"changed hand invalidates")
 t(gate.accept(signal(2,9,9000,valid(listOf("9d","Ah"))),9050)!=null,"different two frames confirms")
 t(gate.accept(signal(2,10,9100,valid(listOf("Jc","Jc"))),9150)==null,"duplicate impossible card rejected")
 val unknown=CardReading(listOf("Jc", null),listOf("3s","Th","4s"),false,0.0,"unknown")
 t(gate.accept(signal(2,11,9200,unknown),9250)==null,"partial recognition rejected")
 t(gate.accept(signal(2,12,9000),9300)==null,"future inspected bad age rejected")
 t(gate.accept(signal(2,13,10000),9900)==null,"future frame refused")
 val far=CardReading(listOf("Jc","Kh"),emptyList(),true,0.95,"preflop")
 t(gate.accept(signal(3,1,12000,far),12050)==null,"preflop first")
 t(gate.accept(signal(3,2,13100,far),13150)!=null,"preflop confirmed")
 println("PASS $n card-consensus scenarios")
}
