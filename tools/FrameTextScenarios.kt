import com.example.airemote.*

object FrameTextScenarios {
 @JvmStatic fun main(args:Array<String>) {
  var checks = 0
  fun verify(value:Boolean) { check(value); checks++ }
  fun row(s:String,x:Int=500,y:Int=960) = PositionedOcrLine(s,NormalizedTextBox(x-40,y-13,x+40,y+13))
  fun parse(vararg l:PositionedOcrLine)=FrameTextEvidence.parse(l.toList())
  verify(parse(row("Fold",130)).actions.single().action==ExactActionCandidate.FOLD)
  verify(parse(row("Чек",490)).actions.single().action==ExactActionCandidate.CHECK)
  verify(parse(row("Колл",490)).actions.single().action==ExactActionCandidate.CALL)
  verify(parse(row("Raise",815)).actions.single().action==ExactActionCandidate.RAISE)
  verify(parse(row("Подтвердить",815)).actions.single().action==ExactActionCandidate.CONFIRM)
  verify(parse(row("Fold",490)).actions.isEmpty())
  verify(parse(row("Fold",130,200)).actions.isEmpty())
  verify(parse(row("Call me",490)).actions.isEmpty())
  verify(parse(row("Check",490),row("Call",490)).actions.isEmpty())
  verify(parse(row("Raise",815),row("Bet",815)).actions.isEmpty())
  verify(parse(row("Fold",130),row("Fold",130)).actions.isEmpty())
  verify(parse(row("Банк: 12 500",490,400)).numbers.single().amount==12500L)
  verify(parse(row("Pot 120",490,400)).numbers.single().kind==NumericCandidateKind.POT)
  verify(parse(row("Колл 2 000",490)).numbers.single().amount==2000L)
  verify(parse(row("Call 3.5",490)).numbers.isEmpty())
  verify(parse(row("Bank 100",490,400)).numbers.isEmpty())
  verify(parse(row("Банк 10 00",490,400)).numbers.isEmpty())
  verify(parse(row("Pot -90",490,400)).numbers.isEmpty())
  verify(parse(row("Timer 20s",490,810)).numbers.single().amount==20L)
  verify(parse(row("Время 121с",490,810)).numbers.isEmpty())
  verify(parse(row("Pot 100",490,400),row("Pot 200",490,450)).numbers.isEmpty())
  verify(parse(row("Pot 100",490)).numbers.isEmpty())
  verify(parse(row("Call 30",130)).numbers.isEmpty())
  verify(parse(PositionedOcrLine("Fold",NormalizedTextBox(-1,940,100,970))).actions.isEmpty())
  verify(parse(row("Pot 999999999999999",490,400)).numbers.isEmpty())
  verify(parse(row("Pot 10",490,400),row("Fold",130),row("Call 5",490)).numbers.size==2)
  println("FrameTextScenarios: $checks checks passed")
 }
}
