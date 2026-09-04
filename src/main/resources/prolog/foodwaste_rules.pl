% =====================================================================
% FoodWaste AI - SWI-Prolog Expert System Knowledge Base
% Explainable AI Decision Engine for Food Waste Prediction, Prevention & Redistribution
% =====================================================================

:- module(foodwaste_rules, [
    assess_item/11,
    assess_waste_risk/6,
    recommend_production/6,
    evaluate_priority_use/3,
    evaluate_redistribution/6,
    evaluate_redistribution_status/6,
    evaluate_redistribution_decision/8,
    evaluate_redistribution_policy/10
]).

% ---------------------------------------------------------------------
% MAIN ASSESSMENT PREDICATE
% assess_item(+Stock, +ExpectedDemand, +ExpiryDays, +HistWasteRate, +CurrentProduction, -RiskLevel, -Reasons, -RecProduction, -RecAction, -Priority, -Redistribute)
% ---------------------------------------------------------------------
assess_item(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, CurrentProduction, RiskLevel, Reasons, RecProduction, RecAction, Priority, Redistribute) :-
    assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, RiskLevel, Reasons),
    recommend_production(Stock, ExpectedDemand, CurrentProduction, RiskLevel, RecProduction, RecAction),
    evaluate_priority_use(ExpiryDays, RiskLevel, Priority),
    Surplus is max(0, Stock - ExpectedDemand),
    evaluate_redistribution(Stock, ExpectedDemand, ExpiryDays, Surplus, Redistribute, _).

% ---------------------------------------------------------------------
% 1. WASTE RISK ASSESSMENT RULES
% Risk Levels: high, medium, low
% PRIORITY: Expiry date strictly prioritizes over quantity.
% ---------------------------------------------------------------------

% High Risk Rule 1: Item has passed expiration date (< 0 days)
assess_waste_risk(Stock, _, ExpiryDays, _, high, ['Item has passed expiration date. Do not serve to customers.']) :-
    ExpiryDays < 0,
    Stock > 0,
    !.

% High Risk Rule 2: Item expires today (= 0 days)
assess_waste_risk(Stock, _, 0, _, high, ['Product expires today. Immediate consumption or action required.']) :-
    Stock > 0,
    !.

% High Risk Rule 3: Expiry is tomorrow (within 24 hours, = 1 day)
assess_waste_risk(Stock, _, 1, _, high, ['Product expires within 24 hours. Immediate action recommended.']) :-
    Stock > 0,
    !.

% High Risk Rule 4: Heavy Overstock (Stock >= 150% of demand) with Near Expiry (<= 3 days) or Elevated Waste (>= 20%)
assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, high, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.50,
    (ExpiryDays =< 3 ; HistWasteRate >= 0.20),
    build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons),
    !.

% High Risk Rule 5: Moderate-to-Heavy Overstock (>= 130% of demand) with 2-day expiry or High Waste (>= 25%)
assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, high, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.30,
    (ExpiryDays =< 2 ; HistWasteRate >= 0.25),
    build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons),
    !.

% High Risk Rule 6: Critical historical waste pattern (>= 30%) and stock exceeds demand
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, high, ['Historical waste rate is critical (>= 30%). Stock exceeds expected demand.']) :-
    HistWasteRate >= 0.30,
    Stock > ExpectedDemand,
    !.

% Medium Risk Rule 1: Expiry approaching within 2 to 3 days with positive stock
assess_waste_risk(Stock, _, ExpiryDays, _, medium, ['Product expires within 2-3 days. Monitor stock velocity closely.']) :-
    ExpiryDays > 1,
    ExpiryDays =< 3,
    Stock > 0,
    !.

% Medium Risk Rule 2: Quantity significantly higher than demand (>= 125% of demand)
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, medium, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.25,
    build_medium_reasons(Ratio, HistWasteRate, Reasons),
    !.

% Medium Risk Rule 3: Moderate historical waste rate (>= 15%) with stock >= demand
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, medium, ['Moderate historical waste rate recorded (>= 15%). Potential over-ordering pattern.']) :-
    HistWasteRate >= 0.15,
    Stock >= ExpectedDemand,
    !.

% Low Risk Rule 1: Safe shelf-life (> 3 days), balanced stock (< 125% of demand), low waste history (< 15%)
assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, low, ['Safe shelf life remaining (> 3 days) and stock is balanced with demand.']) :-
    ExpiryDays > 3,
    HistWasteRate < 0.15,
    (ExpectedDemand =< 0 ; (Stock / ExpectedDemand) < 1.25),
    !.

% Fallback Low Risk
assess_waste_risk(_, _, _, _, low, ['Standard operational parameters. Safe shelf life and normal consumption expected.']).

% ---------------------------------------------------------------------
% HELPER REASON BUILDERS (Explainable AI clauses)
% ---------------------------------------------------------------------
build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons) :-
    findall(R, high_reason_item(Ratio, ExpiryDays, HistWasteRate, R), Reasons).

high_reason_item(Ratio, _, _, 'Stock significantly exceeds expected demand') :- Ratio >= 1.30.
high_reason_item(_, ExpiryDays, _, 'Short remaining shelf life (<= 3 days)') :- ExpiryDays =< 3.
high_reason_item(_, _, HistWasteRate, 'High historical waste rate recorded') :- HistWasteRate >= 0.20.

build_medium_reasons(Ratio, HistWasteRate, Reasons) :-
    findall(R, medium_reason_item(Ratio, HistWasteRate, R), Reasons).

medium_reason_item(Ratio, _, 'Current stock moderately exceeds forecasted demand') :- Ratio >= 1.25.
medium_reason_item(_, HistWasteRate, 'Historical waste rate indicates slight overproduction') :- HistWasteRate >= 0.15.

% ---------------------------------------------------------------------
% 2. PRODUCTION REDUCTION & MITIGATION RECOMMENDATION RULES
% ---------------------------------------------------------------------
% Expired item -> Halt production
recommend_production(Stock, _, CurrentProduction, high, 0, 'Halt production and dispose of expired inventory safely') :-
    Stock > 0,
    CurrentProduction =< 0,
    !.

% High Risk & Excess Stock -> Reduce production or redistribute immediately
recommend_production(Stock, ExpectedDemand, CurrentProduction, high, RecProduction, 'Reduce production or redistribute immediately') :-
    Stock > ExpectedDemand,
    CurrentProduction > 0,
    RecProduction is max(0, round(CurrentProduction * 0.70)),
    !.

recommend_production(Stock, ExpectedDemand, CurrentProduction, high, RecProduction, 'Reduce production or redistribute immediately') :-
    Stock >= ExpectedDemand,
    RecProduction is max(0, round(CurrentProduction * 0.50)),
    !.

recommend_production(_, _, CurrentProduction, high, RecProduction, 'Prioritize remaining stock in specials and reduce batch prep') :-
    RecProduction is max(0, round(CurrentProduction * 0.75)),
    !.

% Medium Risk -> Slightly reduce by 10-15%
recommend_production(Stock, ExpectedDemand, CurrentProduction, medium, RecProduction, 'Slightly reduce production by 10-15% and monitor inventory turnover') :-
    Stock > ExpectedDemand,
    CurrentProduction > 0,
    RecProduction is max(0, round(CurrentProduction * 0.85)),
    !.

recommend_production(_, _, CurrentProduction, medium, RecProduction, 'Feature in daily specials to accelerate turnover') :-
    RecProduction is max(0, round(CurrentProduction * 0.90)),
    !.

% Low Risk & Demand exceeds stock -> Maintain or scale up to match demand
recommend_production(Stock, ExpectedDemand, CurrentProduction, low, RecProduction, 'Maintain optimal production aligned with customer demand') :-
    Stock < ExpectedDemand,
    Deficit is ExpectedDemand - Stock,
    RecProduction is max(CurrentProduction, Deficit),
    !.

recommend_production(_, _, CurrentProduction, low, CurrentProduction, 'Maintain standard scheduled production batch').

% ---------------------------------------------------------------------
% 3. PRIORITY USAGE RECOMMENDATION RULES
% ---------------------------------------------------------------------
evaluate_priority_use(ExpiryDays, _, 'DISPOSE_OR_COMPOST') :-
    ExpiryDays < 0,
    !.
evaluate_priority_use(0, _, 'IMMEDIATE_USE') :- !.
evaluate_priority_use(1, _, 'IMMEDIATE_USE') :- !.
evaluate_priority_use(ExpiryDays, high, 'IMMEDIATE_USE') :-
    ExpiryDays =< 2,
    !.
evaluate_priority_use(ExpiryDays, _, 'HIGH_PRIORITY') :-
    ExpiryDays =< 3,
    !.
evaluate_priority_use(_, high, 'HIGH_PRIORITY') :- !.
evaluate_priority_use(_, medium, 'MODERATE_PRIORITY') :- !.
evaluate_priority_use(_, low, 'STANDARD').

% ---------------------------------------------------------------------
% 4. AUTHORITATIVE REDISTRIBUTION POLICY RULES (PROLOG-AUTHORITATIVE)
% Policy Matrix:
% - Stock <= 0: OUT_OF_STOCK (Not eligible)
% - Expiry < 0: EXPIRED_NOT_FOR_HUMAN_DONATION (Strictly blocked from human donation)
% - Unsafe (IsSafe = false): UNSAFE (Strictly blocked)
% - Surplus <= 0 or Stock <= ExpectedDemand: NO_SURPLUS (Not eligible)
% - Safe + Active Stock + Surplus + Expiry 0-7 days: PRIORITY_DONATION (High / Urgent priority)
% - Safe + Active Stock + Surplus + Expiry 8-30 days: DONATION_RECOMMENDED (Normal / Recommended priority)
% - Safe + Active Stock + Surplus + Expiry > 30 days: NOT_NEEDED_YET (Low / Standard priority, not actionable yet)
% ---------------------------------------------------------------------

% 4.1 Full Policy Predicate
% evaluate_redistribution_policy(+Stock, +ExpectedDemand, +ExpiryDays, +Surplus, +IsSafe, -Status, -Priority, -Eligible, -ReasonEn, -ReasonMy)

% Rule 1: Zero Stock
evaluate_redistribution_policy(Stock, _, _, _, _, 'OUT_OF_STOCK', 'NONE', false,
    'Zero remaining stock in inventory. Not eligible for redistribution.',
    'လက်ကျန်ပစ္စည်း မရှိတော့သဖြင့် ပြန်လည်လှူဒါန်းရန် မဖြစ်နိုင်ပါ။') :-
    Stock =< 0,
    !.

% Rule 2: Expired Food (< 0 days) - strictly blocked from human donation
evaluate_redistribution_policy(_, _, ExpiryDays, _, _, 'EXPIRED_NOT_FOR_HUMAN_DONATION', 'BLOCKED', false,
    'Expired food is unsafe for human consumption. Never eligible for human donation. Use disposal workflow.',
    'သက်တမ်းကုန်ဆုံးသွားသော အစားအစာဖြစ်၍ လူသားများ စားသုံးရန် လှူဒါန်းခွင့်မပြုပါ။ စွန့်ပစ်မှု လုပ်ငန်းစဉ်ကို အသုံးပြုပါ။') :-
    ExpiryDays < 0,
    !.

% Rule 3: Unsafe Food (Food safety cannot be confirmed)
evaluate_redistribution_policy(_, _, _, _, false, 'UNSAFE', 'BLOCKED', false,
    'Food safety cannot be confirmed. Not eligible for human donation.',
    'အစားအသောက် ဘေးကင်းလုံခြုံမှု မသေချာသဖြင့် လူသားများ စားသုံးရန် လှူဒါန်းခွင့်မပြုပါ။') :-
    !.

% Rule 4: No Surplus or Stock absorbed by expected demand
evaluate_redistribution_policy(Stock, ExpectedDemand, _, Surplus, _, 'NO_SURPLUS', 'NONE', false,
    'Expected customer demand absorbs current inventory. No surplus available for external redistribution.',
    'ခန့်မှန်းဝယ်လိုအားနှင့် လက်ကျန်မျှတနေပြီး အပြင်သို့ လှူဒါန်းရန် ပိုလျှံမှုမရှိပါ။') :-
    (Surplus =< 0 ; Stock =< ExpectedDemand),
    !.

% Rule 5: Same-Day Expiry (= 0 days) with confirmed safety and true surplus
evaluate_redistribution_policy(Stock, ExpectedDemand, 0, Surplus, true, 'PRIORITY_DONATION', 'HIGH', true,
    'Priority donation — same-day expiry with verified safety and true surplus. Redistribute immediately.',
    'ဦးစားပေး လှူဒါန်းရန် — ယနေ့ သက်တမ်းကုန်မည်ဖြစ်ပြီး ဘေးကင်းမှုနှင့် ပိုလျှံမှု စစ်ဆေးပြီးဖြစ်၍ အမြန်ဆုံး လှူဒါန်းသင့်ပါသည်။') :-
    Stock > ExpectedDemand,
    Surplus > 0,
    !.

% Rule 6: Expiry within 7 days (1-7 days) with true surplus
evaluate_redistribution_policy(Stock, ExpectedDemand, ExpiryDays, Surplus, true, 'PRIORITY_DONATION', 'HIGH', true,
    'Priority donation — redistribute as soon as possible.',
    'ဦးစားပေး လှူဒါန်းရန် — သက်တမ်းကုန်ရန် နီးကပ်နေသောကြောင့် အမြန်ဆုံး ပြန်လည်ဖြန့်ဝေသင့်ပါသည်။') :-
    ExpiryDays >= 1,
    ExpiryDays =< 7,
    Stock > ExpectedDemand,
    Surplus > 0,
    !.

% Rule 7: Expiry within 1 month (8-30 days) with true surplus
evaluate_redistribution_policy(Stock, ExpectedDemand, ExpiryDays, Surplus, true, 'DONATION_RECOMMENDED', 'RECOMMENDED', true,
    'Donation recommended.',
    'လှူဒါန်းသင့်ပါသည်။') :-
    ExpiryDays >= 8,
    ExpiryDays =< 30,
    Stock > ExpectedDemand,
    Surplus > 0,
    !.

% Rule 8: Expiry more than 1 month (> 30 days)
evaluate_redistribution_policy(_, _, ExpiryDays, _, _, 'NOT_NEEDED_YET', 'LOW', false,
    'Redistribution is not necessary yet.',
    'လောလောဆယ် လှူဒါန်းရန် မလိုသေးပါ။') :-
    ExpiryDays > 30,
    !.

% Fallback
evaluate_redistribution_policy(_, _, _, _, _, 'NO_SURPLUS', 'NONE', false,
    'No actionable surplus for external redistribution.',
    'အပြင်သို့ လှူဒါန်းရန် ပိုလျှံမှု မရှိပါ။').

% ---------------------------------------------------------------------
% 4.2 Convenience / Shorthand Predicates
% ---------------------------------------------------------------------
% evaluate_redistribution_decision(+Stock, +ExpectedDemand, +ExpiryDays, +Surplus, -Status, -Priority, -Eligible, -ReasonEn)
evaluate_redistribution_decision(Stock, ExpectedDemand, ExpiryDays, Surplus, Status, Priority, Eligible, ReasonEn) :-
    evaluate_redistribution_policy(Stock, ExpectedDemand, ExpiryDays, Surplus, true, Status, Priority, Eligible, ReasonEn, _).

% evaluate_redistribution(+Stock, +ExpectedDemand, +ExpiryDays, +Surplus, -Redistribute, -Reason)
evaluate_redistribution(Stock, ExpectedDemand, ExpiryDays, Surplus, Redistribute, Reason) :-
    evaluate_redistribution_policy(Stock, ExpectedDemand, ExpiryDays, Surplus, true, _, _, Redistribute, Reason, _).

% evaluate_redistribution_status(+Stock, +ExpectedDemand, +ExpiryDays, +Surplus, -Status, -Reason)
evaluate_redistribution_status(Stock, ExpectedDemand, ExpiryDays, Surplus, Status, Reason) :-
    evaluate_redistribution_policy(Stock, ExpectedDemand, ExpiryDays, Surplus, true, Status, _, _, Reason, _).
