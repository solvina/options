// Provenance of an open position's P&L, mirroring SpreadMarkService.Source on the engine.
// The point of surfacing it: a number derived from the monitor's last quote snapshot can be hours
// old and disagree with TWS, and the operator needs to see which one they are looking at.
export type PnlSource = 'IBKR_PNL' | 'IBKR_MARK' | 'LAST_MONITOR_MARK'

const labels: Record<PnlSource, string> = {
  IBKR_PNL: "IBKR position P&L — matches TWS (net of commissions)",
  IBKR_MARK: "derived from IBKR's live leg marks (gross of commissions)",
  LAST_MONITOR_MARK: 'last monitor quote mark — broker feed had no fresh row, may be stale',
}

export function pnlSourceLabel(source: PnlSource | null | undefined): string {
  return source ? labels[source] : 'source unknown'
}

/** True when the figure is our own possibly-frozen snapshot rather than a broker number. */
export function isStalePnl(source: PnlSource | null | undefined): boolean {
  return source === 'LAST_MONITOR_MARK'
}
