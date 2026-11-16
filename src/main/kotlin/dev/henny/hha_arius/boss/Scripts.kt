package dev.henny.hha_arius.boss

/** Ablauf-Skripte als Kotlin-Sequenzen: Jeder `yield` ist ein Tick. */
typealias Script = Sequence<Unit>

suspend fun SequenceScope<Unit>.wait(ticks: Int) {
    repeat(ticks.coerceAtLeast(0)) { yield(Unit) }
}

/** Wartet, bis [condition] gilt, höchstens [max] Ticks. */
suspend fun SequenceScope<Unit>.waitUntil(max: Int, condition: () -> Boolean) {
    var n = 0
    while (!condition() && n++ < max) yield(Unit)
}

fun script(block: suspend SequenceScope<Unit>.() -> Unit): Script = sequence(block)

/** Ein laufendes Skript, das jeden Tick einen Schritt macht. */
class Runner(script: Script) {
    private val iterator = script.iterator()
    var done = false
        private set

    fun step(): Boolean {
        if (done) return false
        if (!iterator.hasNext()) {
            done = true
            return false
        }
        iterator.next()
        return true
    }
}
