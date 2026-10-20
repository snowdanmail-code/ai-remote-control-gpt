package com.example.airemote

/** Direct-game safety preflight; unit checks only, NOT a game simulator. */
fun main() {
    var n=0
    fun case(name:String, block:()->Unit) { block(); n++; println("PASS $name") }
    val view = VerifiedLiveView(
        1,10,1000,"table-a/hand-1","state:one",true,true,true,true,
        setOf(LiveAction.FOLD, LiveAction.CHECK))
    val provider=AuthenticatedActionEffects(IndependentEffectVerifier { it.providerId == "trusted-test" })
    val guard=LiveGameActionGuard(effectTicketVerifier = { token,c,v,ms -> provider.accept(token,c,v,ms) })
    case("initially disarmed") { check(guard.begin("a",LiveAction.FOLD,view,1010,true)==LiveCommandStatus.REFUSED) }
    case("arm requires operator") { check(guard.armByOperator()) }
    case("external permission required") { check(guard.begin("a",LiveAction.FOLD,view,1010,false)==LiveCommandStatus.REFUSED) }
    case("source identity required") { check(guard.begin("a",LiveAction.FOLD,view.copy(foregroundVerified=false),1010,true)==LiveCommandStatus.REFUSED) }
    case("stage required") { check(guard.begin("a",LiveAction.FOLD,view.copy(stageVerified=false),1010,true)==LiveCommandStatus.REFUSED) }
    case("turn required") { check(guard.begin("a",LiveAction.FOLD,view.copy(turnVerified=false),1010,true)==LiveCommandStatus.REFUSED) }
    case("exact label required") { check(guard.begin("a",LiveAction.FOLD,view.copy(exactLabelsVerified=false),1010,true)==LiveCommandStatus.REFUSED) }
    case("action availability required") { check(guard.begin("a",LiveAction.RAISE,view,1010,true)==LiveCommandStatus.REFUSED) }
    case("stale frame refused") { check(guard.begin("a",LiveAction.FOLD,view,4000,true)==LiveCommandStatus.REFUSED) }
    case("begin real hand guarded") { check(guard.begin("a",LiveAction.FOLD,view,1010,true)==LiveCommandStatus.READY_TO_DISPATCH) }
    case("only one dispatch ticket") { check(guard.claimDispatch("a")); check(!guard.claimDispatch("a")) }
    case("same frame cannot confirm") { check(guard.observe(view,1100)==LiveCommandStatus.AWAITING_CONFIRMATION) }
    case("new frame same state not confirmed") { check(guard.observe(view.copy(frameSequence=11),1200)==LiveCommandStatus.AWAITING_CONFIRMATION) }
    case("animation change does not prove execution") {
        check(guard.observe(view.copy(frameSequence=12,capturedAtMs=1250,stateFingerprint="state:two"),1300)==LiveCommandStatus.AWAITING_CONFIRMATION)
    }
    case("changed state requires independently authenticated effect") {
        val changed = view.copy(frameSequence=12,capturedAtMs=1300,stateFingerprint="state:two",actionEffectVerified=true)
        check(guard.observe(changed,1350) == LiveCommandStatus.AWAITING_CONFIRMATION)
        val cmd = LiveCommand("a",view.handKey,LiveAction.FOLD,1,10,view.stateFingerprint,1010,1000)
        fun receipt(seq:Long,id:String,time:Long) = PostActionReceipt("trusted-test",id,"a",view.handKey,
            LiveAction.FOLD,1,3,seq,time,"state:two",true,true,10,10,100,100)
        check(provider.inspect(cmd,receipt(11,"proof-one",1200),1250,false).status == EffectGateStatus.COLLECTING)
        val granted = provider.inspect(cmd,receipt(12,"proof-two",1300),1350,false)
        check(granted.status == EffectGateStatus.CONFIRMED_EFFECT)
        check(guard.observe(changed.copy(verifiedEffectTicket=granted.ticket),1350)==LiveCommandStatus.CONFIRMED)
    }
    case("command ID replay blocked") { check(guard.begin("a",LiveAction.FOLD,view.copy(frameSequence=15,capturedAtMs=1500),1510,true)==LiveCommandStatus.REFUSED) }
    case("explicit STOP disarms") { guard.stop(); check(guard.begin("b",LiveAction.CHECK,view,1510,true)==LiveCommandStatus.REFUSED) }
    case("pending timeout creates uncertainty") {
        val other=LiveGameActionGuard(); check(other.armByOperator())
        check(other.begin("x",LiveAction.CHECK,view,1010,true)==LiveCommandStatus.READY_TO_DISPATCH)
        check(other.claimDispatch("x"))
        check(other.observe(view.copy(frameSequence=12,capturedAtMs=6000),6000)==LiveCommandStatus.UNCERTAIN)
        check(other.hasUnconfirmedAction())
        check(other.begin("z",LiveAction.CHECK,view,6100,true)==LiveCommandStatus.REFUSED)
    }
    println("Passed $n direct-game action guard scenarios")
}
