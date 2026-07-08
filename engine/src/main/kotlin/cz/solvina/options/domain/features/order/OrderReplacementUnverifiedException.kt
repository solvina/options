package cz.solvina.options.domain.features.order

/**
 * Thrown by the order-replacement path when the old (working) order could NOT be confirmed removed
 * from the broker within the verification window — so it is unsafe to submit a replacement (both the
 * old and the new order could rest and fill, doubling the position).
 *
 * This is a RECOVERABLE control-flow signal, not a fatal error: the ladder must catch it, stop
 * repricing, and ride the existing order to its authoritative resolution (fill watcher or the
 * execution timeout's cancel-and-await). It must NEVER be allowed to escape the entry coroutine —
 * doing so leaves the spread stuck in PENDING while its order keeps working at the broker and later
 * fills as an untracked orphan.
 */
class OrderReplacementUnverifiedException(
    val existingOrderId: Int,
    message: String,
) : IllegalStateException(message)
