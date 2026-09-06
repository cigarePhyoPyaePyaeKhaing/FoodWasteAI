# FoodWaste AI consistency implementation audit

This change builds on `92ec44b`, preserves the approved Prolog policy, and changes no production records. The requested desktop checkout has unrelated Git history and was preserved. Implementation and tests ran in `C:\FoodWasteAI\risk-fix`, a clean clone of the requested GitHub repository on `main`.

## 1–7. Recorded inventory, outcomes, and dates

1. **Pizza quantity:** historical totals included `MANUAL_COUNT`, incorrectly adding the physical snapshot to purchases. Receipt totals now include only `PURCHASE` / `STOCK_IN`. A physical count sets current stock; it does not add a receipt. Purchase 8 → count 7 → waste 7 yields recorded stock-in 8 and current stock 0. History retains all three events and displays `+ 8`, `= 7`, and `− 7`.
2. **Pending Dispatches:** backend aggregation includes only `PENDING`, with quantity totals separated by unit and a dispatch count. `CONFIRMED`, `COMPLETED`, `COLLECTED`, and `CANCELLED` do not inflate pending totals.
3. **Total Food Rescued:** completed outcomes only; legacy `COLLECTED` is treated as completed. Completed 55.5 pcs and pending 30 kg remain distinct.
4. **Confirmed Value Saved:** completed quantity × inventory price. The equivalent example is 55,500 MMK confirmed and 60,000 MMK pending. Compatibility mass fields include kg/g conversion only, never liters or pieces.
5. **Milk badge:** Inventory GET exposes the backend assessment's redistribution status. Inventory badges and dispatch selection require `PRIORITY_DONATION` or `DONATION_RECOMMENDED`; expiry alone no longer generates a redistribution badge. Milk with stock 10 and daily demand 12.857143 has NO_SURPLUS and no dispatch CTA, while retaining HIGH / 85 current waste risk.
6. **Dashboard scope:** confirmed waste and loss are explicitly all recorded history. The Today label and the fallback from unconfirmed expiry projections were removed from those totals. Reports retains date-filtered waste and labels completed redistribution as all recorded history.
7. **Timezone:** event timestamps use UTC in Java/database access and convert to Asia/Yangon for display. JDBC reads/writes use `LocalDateTime` directly to prevent implicit conversion through the host timezone. Database columns and historical rows are unchanged. Yangon report dates become a UTC half-open interval: inclusive start midnight, exclusive next-day midnight. `pickupTime` is separately a scheduled Yangon wall time and is not shifted twice. The browser test runs in America/Los_Angeles: UTC `2026-09-06T17:58:59` displays `2026-09-07 00:28`; scheduled `2026-09-08T00:35:00` remains `00:35`.

## 8–11. Forecast contract and detail view

8. **Root cause:** action cards used the raw risk heuristic while tomorrow batches applied stock constraints. Seven-day projections also subtracted sales independently of predicted waste. This could allocate the same stock twice and produce different values across views. Distinct batches were additionally discarded by normalized name in the legacy tomorrow summary.
9. **Canonical contract:** `forecastContractVersion: 2`. Current `items` remain today's assessments. `forecastItems` contains separate current facts and daily projections. Each day uses the previous closing stock, predicts sales up to expected demand, and limits waste to stock remaining after sales. On the expiry day, unsold remaining stock is projected as waste. There is no modeled replenishment, no negative stock, and no new active daily row after depletion. Day/week totals aggregate these exact daily quantities by unit. `tomorrowPrediction`, `tomorrowItems`, and `/api/prediction/tomorrow` are the expiring-tomorrow subset of the same day-one projection, preserving batch IDs. Current redistribution surplus is a different concept: max(current stock − daily demand, 0); it is not renamed predicted waste. Demand uses the existing shared historical SUM/7 calculation and its existing baseline for absent positive history.
10. **View Details:** the existing Dashboard modal now renders item cards with expandable daily details, using the same backend forecast as the KPI and action cards. No separate prediction page or visual-theme redesign was introduced. No-inventory, not-generated, valid-zero, API-error, and partial-data states have distinct English/Myanmar messages. One delegated handler opens or closes the modal once per action.
11. **Fields:** forecast period; summary by unit; batch ID in the API; name, category, unit, current stock, expiry date, current days remaining, current risk/score, expected daily demand, seven-day waste, projected surplus. Each expanded day shows date, opening stock, expected demand, days to expiry, future risk/score, predicted sales, predicted waste, closing stock, and bilingual reason. API action data includes recommended action, suggested donation quantity, redistribution status, and potential savings. Current facts are never overwritten by future facts.

Equivalent fixture results: fish 55.714286 kg seven-day waste, milk 0 liter, chicken 4.285714 kg; weekly total 60.0 kg. Current risks remain fish HIGH 85, milk HIGH 85, chicken MEDIUM 50. Fish surplus 55.714286 kg and chicken surplus 8.571429 kg are current demand comparisons, distinct from forecast waste.

## 12–16. Messages and button audit

12. **Savings:** action cards say Potential Savings / ခန့်မှန်း သက်သာနိုင်မည့် ပမာဏ. They use daily forecast waste and existing potential-prevention factors, not a completed-event claim. Completed redistribution alone uses Confirmed Value Saved.
13. **Messages:** failed API envelopes are rejected even when HTTP is 200. Server errors are logged privately and return a general retry message; technical errors are filtered from user toasts. Dashboard, inventory, sales, waste, redistribution, and report failures display unavailable states rather than successful-looking zeros. Dispatch messages distinguish scheduled/completed/cancelled. Evaluation messages identify seven days. Waste logging confirms the stock update. Inventory history distinguishes physical counts from receipts/outflows. Settings no longer advertises account controls. Error states and new forecast text include Myanmar equivalents. Unused login translation keys are inert and do not render controls.

14–16. **Button decisions:**

| Page / context | Button | Before | Reason removed or kept | After |
| --- | --- | --- | --- | --- |
| Dashboard | Open Action Center | Decorative span styled as an action | No wired destination or unique action | Removed |
| Dashboard | Run 7-Day Evaluation | Working evaluation | Essential computation and refresh | Kept; pending-state protection |
| Dashboard | Predicted Waste View Details | Multiple overlapping event bindings; vague daily content | Essential item detail workflow | Kept; single binding and item/day modal |
| Dashboard | Confirmed Waste View Details | Working history modal with overlapping bindings | Unique audit detail | Kept; single binding and all-history scope |
| Dashboard action card | View Inventory | Working navigation | Useful stock context | Kept |
| Dashboard action card | Go to Redistribution | Could be based on inconsistent quantities | Valid only for authoritative eligible status | Shown only for eligible positive-stock items |
| Dashboard controller | Legacy apply/dismiss handlers | Unreferenced code with simulated success/savings | No live workflow and misleading potential behavior | Removed unused handlers |
| Inventory | Add Food Item | Header plus floating duplicate | Same action; header remains accessible | Header kept, floating duplicate removed |
| Inventory | Edit / History / Summary | Unique working controls | Essential editing and audit functions | Kept, including depleted-item history |
| Inventory | Delete | Existing supported deletion with confirmation and backend checks | Preserve intentional product behavior | Kept; no historical deletion performed by this task |
| Inventory | Review Disposal / Record Waste / Already Disposed / Review Later | Existing disposal flow | Valid supported expired-stock workflow | Kept; positive-stock context |
| Sales | Record Sale | Header plus floating duplicate | Same action | Header kept, floating duplicate removed |
| Sales | Refresh / Delete / Save / Cancel | Working list reload and record workflow | Reload can pick up changes from another session; preserve existing record correction | Kept; stock availability validation retained |
| Waste | Log Food Waste | Header plus floating duplicate | Same action | Header kept, floating duplicate removed |
| Waste | Refresh / Delete / Save / Cancel | Working list reload and record workflow | Unique supported workflow actions | Kept; stock availability validation retained |
| Redistribution | Schedule Surplus Dispatch | Header plus floating duplicate | Same action | Header kept, floating duplicate removed; disabled with no eligible stock |
| Redistribution item | Dispatch | Potentially inappropriate selection | NO_SURPLUS, OUT_OF_STOCK, NOT_NEEDED_YET, unavailable, and unsafe/expired statuses are not dispatchable through this selector | Backend eligibility controls selection and modal guard |
| Redistribution partner | Dispatch | Always visible | Requires at least one eligible food item | Hidden without eligible stock |
| Redistribution pending / confirmed | Complete / Cancel | Working status transitions | Valid workflow | Kept |
| Redistribution completed / cancelled | Complete | Must not permit repeated completion | Invalid transition | No completion button; read-only status icon |
| Redistribution | Refresh | Working external-change reload | Useful across sessions | Kept |
| Reports | Period filters / Custom Apply / Export | Working distinct functions | Essential reporting | Kept; export rejects unavailable data |
| Settings | Theme / language selectors | Working local preferences | Actual supported settings | Kept |
| Shared navigation | Sidebar / mobile navigation / More | Different responsive navigation surfaces | Essential access on each screen size | Kept |
| Shared modals | Header X / footer Close / Cancel | Accessible dismissal at different positions | Useful in long scrollable dialogs | Kept |
| Shared | English / Myanmar | Working language switch | Essential | Kept |
| Normal product UI | AI Assistant / login / logout / diagnostics controls | Already absent from active pages | No live controls to remove | Remain absent |

No essential workflow was removed. Desktop/tablet/mobile checks include the header action buttons in both languages; mobile header wrapping keeps Myanmar actions inside the viewport.

## 17–20. Files and validation

17. **Files:** see the complete changed-path list below. No approved Prolog rule file or `PrologService` policy implementation changed.
18. **Added regression coverage:** `MetricForecastConsistencyTest` adds six Java tests covering snapshot history, status/unit/value totals, current-risk and cross-view forecast reconciliation, no-inventory versus zero, Yangon database date boundaries, and distinct nonzero units. Existing tests were updated only where the new contract intentionally changes behavior: preserve distinct batch IDs; do not resurrect depleted stock; compare recording instants in UTC. `ui-consistency.test.cjs` exercises actual page scripts with intercepted API fixtures, including all seven pages, header actions, six viewport/language modal combinations, five detail states, failed HTTP/JSON envelopes, time conversion, milk eligibility, completed dispatch controls, and stock-history labels. The prior Dashboard risk test remains passing.
19. **Maven baseline:** 320 tests, 0 failures, 0 errors, 0 skipped; `mvn clean test package` BUILD SUCCESS (45.234 s). Final full build: 326 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS. Final duration: 39.045 s; local log: `consistency-final-build.log`.
20. **Additional checks:** real SWI-Prolog targeted suite: 55 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS (40.766 s). All frontend JavaScript syntax checks and the prior Dashboard risk test passed. Browser QA uses Chrome, desktop 1440×1000, tablet 768×1024, mobile 390×844, both languages, and a different browser timezone. Modal screenshots are generated in ignored `target/ui-consistency/`. Test traffic is intercepted locally; no production mutation is performed.

Reproduce: run `mvn clean test package`; run `node src/test/dashboard-risk.test.cjs`; with Playwright available, run `node src/test/ui-consistency.test.cjs`. The browser test consumes the Java-generated `target/consistency-forecast-fixture.json`; Chrome is the default, with `BROWSER_EXECUTABLE` as an override. Real engine suites use `SWIPL_PATH` and `RISK_TEST_SWIPL_PATH` pointing to the installed SWI-Prolog executable. The isolated development database is not the Aiven production database.

## 21–23. Delivery and limits

21. **Commit:** requested message `fix(ui): align metrics, forecast details, actions, and messaging`. The final commit hash is supplied in the completion message (a commit cannot embed its own hash).
22. **Push:** target `origin/main`; the final completion message records the actual push result and any live deployment verification separately.
23. **Remaining limits:** forecasts are estimates with constant demand and no replenishment; the existing no-history demand baseline and prevention factors are retained and are not empirical guarantees. The API computes a fresh read-only forecast on GET, so the not-generated UI state is mainly for an absent payload rather than a mandatory saved-evaluation gate. A missing price contributes zero rather than invented money. Historical dispatches have no immutable unit-price snapshot, so confirmed value uses the linked inventory price; later price edits can change valuation. Legacy inventory with no receipt transaction retains the existing current-quantity fallback; no historical receipts are invented. Historic rows from an incorrectly configured non-UTC deployment cannot be reliably repaired without independent timezone evidence; none are rewritten. Prototype partners retain their configured contact completeness. Local fixtures establish implementation behavior; live Railway deployment status must be checked separately from Git push success.


## Changed paths

- `src/main/java/com/foodwasteai/controller/BaseServlet.java`
- `src/main/java/com/foodwasteai/controller/InventoryServlet.java`
- `src/main/java/com/foodwasteai/controller/PredictionServlet.java`
- `src/main/java/com/foodwasteai/dao/FoodItemDao.java`
- `src/main/java/com/foodwasteai/dao/InventoryTransactionDao.java`
- `src/main/java/com/foodwasteai/dao/RedistributionDao.java`
- `src/main/java/com/foodwasteai/dao/SalesDao.java`
- `src/main/java/com/foodwasteai/dao/WasteRecordDao.java`
- `src/main/java/com/foodwasteai/model/FoodItem.java`
- `src/main/java/com/foodwasteai/model/InventoryTransaction.java`
- `src/main/java/com/foodwasteai/prolog/PrologAssessment.java`
- `src/main/java/com/foodwasteai/service/FoodItemService.java`
- `src/main/java/com/foodwasteai/service/PredictionService.java`
- `src/main/java/com/foodwasteai/service/RedistributionService.java`
- `src/main/java/com/foodwasteai/service/SalesService.java`
- `src/main/java/com/foodwasteai/service/WasteService.java`
- `src/main/webapp/WEB-INF/protected/dashboard.html`
- `src/main/webapp/WEB-INF/protected/inventory.html`
- `src/main/webapp/WEB-INF/protected/redistribution.html`
- `src/main/webapp/WEB-INF/protected/sales.html`
- `src/main/webapp/WEB-INF/protected/settings.html`
- `src/main/webapp/WEB-INF/protected/waste.html`
- `src/main/webapp/css/responsive.css`
- `src/main/webapp/js/api.js`
- `src/main/webapp/js/dashboard.js`
- `src/main/webapp/js/i18n/en.js`
- `src/main/webapp/js/i18n/mm.js`
- `src/main/webapp/js/inventory.js`
- `src/main/webapp/js/redistribution.js`
- `src/main/webapp/js/reports.js`
- `src/main/webapp/js/sales.js`
- `src/main/webapp/js/waste.js`
- `src/test/java/com/foodwasteai/PredictionUnitAndRiskScoreConsistencyTest.java`
- `src/test/java/com/foodwasteai/SevenDayForecastRegressionTest.java`
- `src/test/java/com/foodwasteai/WasteValidationAndInventoryDeductionTest.java`
- `src/test/java/com/foodwasteai/MetricForecastConsistencyTest.java`
- `src/test/ui-consistency.test.cjs`
- `CONSISTENCY_AUDIT.md`
