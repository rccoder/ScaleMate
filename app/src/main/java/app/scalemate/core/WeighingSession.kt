package app.scalemate.core

enum class Phase { IDLE, WAITING, MEASURING, STABLE, COUNTDOWN, SAVING, SAVE_FAILED, CONFIRMED, CANCELLED, EXPIRED }
data class SessionState(val phase: Phase = Phase.IDLE, val kg: Double? = null, val candidateKg: Double? = null, val candidateAt: Long = 0, val seconds: Int = 5, val token: Int? = null)

/** Pure monotonic-time state machine; never uses an unverified protocol stable flag. */
class WeighingSession(private val identity: String, private val automatic: Boolean, private val previousToken: Int? = null) {
    var state = SessionState(Phase.WAITING); private set
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var started = false
    private var lastSample = -1L
    private var lastSeen = -1L
    private var candidateStart = -1L
    private var zeroSeen = false
    fun frame(frame: ScaleFrame, now: Long, wall: Long) {
        if (terminal() || frame.identity != identity || now <= lastSample) return
        lastSample = now
        lastSeen = now
        if (frame.kg == 0.0) { zeroSeen = true; started = false; samples.clear(); candidateStart = -1; state = SessionState(Phase.WAITING); return }
        if (!started) {
            if (frame.state != 1 || (frame.token == previousToken && !zeroSeen)) return
            started = true
        }
        if (state.token != null && state.token != frame.token) { samples.clear(); candidateStart = -1; state = SessionState(Phase.MEASURING) }
        if (state.phase in listOf(Phase.STABLE, Phase.COUNTDOWN)) {
            if (kotlin.math.abs(frame.kg - state.candidateKg!!) <= 0.10 + 1e-9) {
                state = state.copy(kg = frame.kg, token = frame.token); return
            }
            samples.clear(); candidateStart = -1
        }
        samples.addLast(now to frame.kg)
        while (samples.size > 1 && now - samples.first().first > 4000) samples.removeFirst()
        val stable = samples.size >= 3 && now - samples.first().first >= 3000 &&
            samples.maxOf { it.second } - samples.minOf { it.second } <= 0.10 + 1e-9 &&
            samples.zipWithNext().all { it.second.first - it.first.first <= 2000 }
        state = SessionState(Phase.MEASURING, frame.kg, token = frame.token)
        if (stable) {
            candidateStart = now
            state = state.copy(phase = if (automatic) Phase.COUNTDOWN else Phase.STABLE, candidateKg = frame.kg, candidateAt = wall)
        }
    }
    fun tick(now: Long): Boolean {
        if (terminal()) return false
        if (lastSeen >= 0 && now - lastSeen > 10000) { cancel(Phase.EXPIRED); return false }
        if (state.phase == Phase.COUNTDOWN) {
            val remaining = 5000 - (now - candidateStart)
            state = state.copy(seconds = ((remaining.coerceAtLeast(0) + 999) / 1000).toInt())
            if (remaining <= 0) { state = state.copy(phase = Phase.CONFIRMED); return true }
        }
        return false
    }
    fun confirm(now: Long): Boolean {
        if (lastSeen < 0 || now - lastSeen > 10000 || state.phase != Phase.STABLE) return false
        state = state.copy(phase = Phase.CONFIRMED); return true
    }
    fun cancel(phase: Phase = Phase.CANCELLED) { if (!terminal()) { samples.clear(); state = state.copy(phase = phase, candidateKg = null) } }
    private fun terminal() = state.phase in listOf(Phase.CONFIRMED, Phase.CANCELLED, Phase.EXPIRED)
}
