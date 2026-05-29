# Epic: Intraday Bull Flag Momentum Strategy Engine
## Architectural Context & Constraints
  * Target Environment: Interactive Brokers TWS API / Gateway (Socket-based infrastructure).
  * We need to be restricted by free cash on the account. There are other orders on the account we only can risk 1% of the cash pool with one trade. 
  * Trading limits need to store in the database, shown to the user. 
  * We must not risk capital not in the account or is in other orders.

## User Story 1: Quantitative Pattern Identification & State Tracking

As an Algorithmic Strategy Developer,
I want to build an in-memory data aggregation pipeline and pattern-matching engine.
So that I can quantitatively identify valid Bull Flag setups on liquid equities without visual charts.
Acceptance Criteria:
  * Data Stream Ingestion:
    * The system must boot by pulling 3 days of historical data (reqHistoricalData) to calculate baseline metrics.
    * The system must subscribe to live trading streams via reqRealTimeBars to receive pre-computed 5-second OHLCV bars (mitigating raw tick data conflation/gaps).
  * Local Bar Construction:
    * The ingestion loop must aggregate exactly sixty (60) 5-second bars in-memory to build an accurate, un-delayed 5-minute candle.
    * Make sure that those 60 bars are realy consistent with the 5-second bar stream.
      * Log, or alert if there are any discrepancies.
  * Flagpole (Momentum) Logic:
    * Detect a sharp vertical price expansion over 5–10 candles where the move is greater than 2×ATR (Average True Range).
    * Confirm institutional volume spikes where Volume is greater than 1.5×Moving Average Volume (20).
  * Flag (Consolidation) Logic:
    * Track a horizontal or downward-sloping consolidation channel over 5–20 bars using a linear regression line fit through candle highs and lows.
    * Validate that the retracement does not exceed 38.2% to 50% of the vertical flagpole height.
    * Confirm that volume visibly dries up below the 20-period moving average during consolidation.

## User Story 2: Execution & Server-Side Risk Management
As an Algorithmic Execution Engine,
I want to calculate precise risk-adjusted position sizes and route native server-side bracket orders,
So that I can protect capital and maximize the mathematical expectancy of the strategy.

Acceptance Criteria:
  * Dynamic Position Sizing:
    * The system must query AvailableFunds prior to order routing.
    * Risk per trade (1R) must be hard-capped at $200 (1% of the $20,000 cash pool).
    * Calculate exact share sizes dynamically using the formula:
        Shares=$100/(Breakout price - Stop loss price)

  * Order Structure & Routing:
    * The strategy must trigger an entry the exact second a live 5-second bar close crosses above the calculated flag upper resistance line.
    * Order must be sent as a native TWS Bracket / One-Cancels-All (OCA) structure to guarantee server-side management.
    * Parent Order: Stop Market or Stop Limit order to enter on breakout.
    * Child 1 (Stop Loss): Stop Market order placed just below the lowest point of the flag consolidation channel (Max loss capped at $100 + slippage friction).
    * Child 2 (Profit Target): Limit order set at a strict 1:2 Reward-to-Risk ratio (Targeting a net +$200 gain).

## User Story 3: Time-Based Session Filtering (The Guardrails)
As a System Architect,
I want to enforce strict temporal gates on order entry and position lifecycles,

So that the engine eliminates late-day friction and completely avoids catastrophic overnight gap risk.
Acceptance Criteria:
RTH Focus: Enforce execution strictly within Regular Trading Hours on each exchange.
 * Afternoon Entry Block:
   * Program an entry restriction gate: if (currentTime >= (exchnage.close - 2hrs)) { allowNewEntries = false; }
   * This limits noise and ensures trades have a multi-hour window to naturally reach their 1:2 targets.
 * EOD Auto-Liquidation Handshake:
   * If a trade is still open and grinding sideways at (exchange.close - 15min), the engine must automatically cancel all remaining child bracket orders and issue an immediate Market Close order.
   * This completely immunizes us from overnight gap-downs and margin interest borrowing decay.

## User story 4:
As a user I want to be able to see the strategy in action.
So that I can validate the strategy's performance and identify potential gaps in real time use in our web ui.
  * I want to see all the orders and their status in real time.
  * I want to see the current position and its status in real time.


## Developer Statistical Benchmark for Unit/Backtesting
When backtesting or validating this engine over a 100-trade sample size, the developer should expect a statistically sound but visually "low" win-rate environment due to false breakout noise:
* Target Win Rate: ~42% to 46% 
* Expected Average Winner: +$195 (Net of $5 commissions/slippage)
* Expected Average Loser: -$105 (Net of $5 commissions/slippage)
* Expected Forced 3:45 PM Cuts: ~6% to 8% of trades averaging a small -$15 scratch loss.
* Target 100-Trade P&L Output: +$2,820 (Below Average Edge) to +$3,840 (Average Edge). The code must run flawlessly through losing streaks without human intervention to let this risk asymmetry compound.
