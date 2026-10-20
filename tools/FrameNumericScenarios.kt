import com.example.airemote.*
object FrameNumericScenarios {
 @JvmStatic fun main(args: Array<String>) {
  val gate=FrameBoundNumericEvidence()
  val first=FrameNumericReading("table-a",1,1,1000,true,100,20,15)
  check(gate.accept(first,1100))
  check(gate.current("table-a",1,1100)==first)
  check(!gate.accept(first.copy(frameSequence=1),1101))
  check(gate.current("table-a",1,1101)==null)
  check(!gate.accept(first.copy(frameSequence=2,pot=-1),1102))
  check(!gate.accept(first.copy(frameSequence=3,sourceVerified=false),1103))
  check(gate.accept(first.copy(frameSequence=4),1104))
  check(gate.current("table-b",1,1104)==null)
  check(gate.accept(first.copy(frameSequence=5),1105))
  check(gate.current("table-a",1,2500)==null)
  println("FrameNumericScenarios: 9 checks passed")
 }
}
