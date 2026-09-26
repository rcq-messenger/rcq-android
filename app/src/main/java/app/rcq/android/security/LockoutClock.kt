package app.rcq.android.security

/**
 * How much of a PIN lockout is left, decided from plain numbers so it can be
 * pinned by tests ([PanicPinService.lockedOutUntilElapsed] reads the clocks and
 * the stored record and writes the re-anchor back).
 *
 * The stored record carries the deadline twice: [wallUntil] on the wall clock
 * and [elapsedUntil] on `elapsedRealtime`, with the boot the latter was taken
 * on. The monotonic one decides on that boot, because the clock in Settings
 * cannot move it. After a reboot it means nothing (uptime starts again), so the
 * wall-clock deadline is used, capped, and re-anchored on the new boot.
 *
 * ⚠ A monotonic deadline further away than one whole step is itself a reboot,
 * whatever the boot counter says: on the boot it was set on it can never be
 * more than a step ahead. Some ROMs do not keep BOOT_COUNT, or it can be read
 * before the system bumps it, and that deadline (the old boot's uptime plus
 * the step) against the new boot's small uptime was capped to "one step left"
 * on every check and never ran down (#1045 review: ten days of uptime, ten
 * days locked out). Where the counter is missing the monotonic deadline is
 * trusted by the same test. Whatever the source, what is left is never more
 * than [ceiling], the current step.
 */
internal object LockoutClock {
    /** [leftMs] of lockout remain; [reanchor]: store `now + leftMs` as the new
     *  monotonic deadline on the current boot. */
    data class Read(val leftMs: Long, val reanchor: Boolean)

    fun read(
        wallUntil: Long?,
        elapsedUntil: Long?,
        storedBoot: Int?,
        ceiling: Long,
        now: Long,
        nowElapsed: Long,
        boot: Int?,
    ): Read? {
        if (ceiling <= 0) return null
        val elapsedLeft = elapsedUntil?.let { it - nowElapsed }
        val sameBoot = boot == null || storedBoot == boot
        if (elapsedLeft != null && sameBoot && elapsedLeft <= ceiling) {
            return if (elapsedLeft > 0) Read(elapsedLeft, reanchor = false) else null
        }
        val until = wallUntil ?: return null
        val left = (until - now).coerceAtMost(ceiling)
        if (left <= 0) return null
        return Read(left, reanchor = true)
    }
}
