% =====================================================================
% FoodWaste AI - SWI-Prolog Expert System Knowledge Base
% Explainable AI Decision Engine for Food Waste Prediction, Prevention & Redistribution
% =====================================================================

:- module(foodwaste_rules, [
    assess_item/7,
    assess_waste_risk/6,
    recommend_production/6,
    evaluate_priority_use/3,
    evaluate_redistribution/6
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
% Evaluates: Expiry Risk, Overstock Risk, Demand Mismatch, Historical Rate
% ---------------------------------------------------------------------

% High Risk Rule 1: Expiry is imminent (<= 1 day) and positive stock remains
assess_waste_risk(Stock, _, ExpiryDays, _, high, ['Expiry is near (within 1-2 days)', 'Stock remaining requires immediate consumption']) :-
    ExpiryDays =< 1,
    Stock > 0,
    !.

% High Risk Rule 2: Overstock + Demand Mismatch (Stock >= 130% of demand) with Near Expiry (<= 3 days) or High Waste History (>= 20%)
assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, high, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.30,
    (ExpiryDays =< 3 ; HistWasteRate >= 0.20),
    build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons),
    !.

% High Risk Rule 3: Severe historical waste pattern (>= 30%) and stock exceeds demand
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, high, ['Historical waste rate is high (>= 30%)', 'Stock exceeds expected demand']) :-
    HistWasteRate >= 0.30,
    Stock > ExpectedDemand,
    !.

% Medium Risk Rule 1: Expiry approaching within 2 to 3 days with positive stock
assess_waste_risk(Stock, _, ExpiryDays, _, medium, ['Expiry approaching within 3 days', 'Requires monitoring to prevent spoilage']) :-
    ExpiryDays > 1,
    ExpiryDays =< 3,
    Stock > 0,
    !.

% Medium Risk Rule 2: Moderate overstock / demand mismatch (110% - 130% of demand)
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, medium, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.10,
    Ratio < 1.30,
    build_medium_reasons(Ratio, HistWasteRate, Reasons),
    !.

% Medium Risk Rule 3: Moderate historical waste rate (15% - 30%) with non-zero stock
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, medium, ['Moderate historical waste rate recorded', 'Potential over-ordering pattern']) :-
    HistWasteRate >= 0.15,
    Stock >= ExpectedDemand,
    !.

% Low Risk Rule 1: Well-balanced stock, safe shelf-life (> 3 days), low waste history (< 15%)
assess_waste_risk(_, _, ExpiryDays, HistWasteRate, low, ['Stock is balanced with expected demand', 'Safe shelf life remaining', 'Low historical waste rate']) :-
    ExpiryDays > 3,
    HistWasteRate < 0.15,
    !.

% Fallback Low Risk
assess_waste_risk(_, _, _, _, low, ['Standard operational parameters', 'Normal consumption expected']).

% ---------------------------------------------------------------------
% HELPER REASON BUILDERS (Explainable AI clauses)
% ---------------------------------------------------------------------
build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons) :-
    findall(R, high_reason_item(Ratio, ExpiryDays, HistWasteRate, R), Reasons).

high_reason_item(Ratio, _, _, 'Stock exceeds expected demand') :- Ratio >= 1.30.
high_reason_item(_, ExpiryDays, _, 'Expiry is near (within 1-3 days)') :- ExpiryDays =< 3.
high_reason_item(_, _, HistWasteRate, 'Historical waste is high') :- HistWasteRate >= 0.20.

build_medium_reasons(Ratio, HistWasteRate, Reasons) :-
    findall(R, medium_reason_item(Ratio, HistWasteRate, R), Reasons).

medium_reason_item(Ratio, _, 'Current stock moderately exceeds forecasted demand') :- Ratio >= 1.10.
medium_reason_item(_, HistWasteRate, 'Historical waste rate indicates slight overproduction') :- HistWasteRate >= 0.15.

% ---------------------------------------------------------------------
% 2. PRODUCTION REDUCTION RECOMMENDATION RULES
% ---------------------------------------------------------------------
% High Risk & Excess Stock -> Reduce production by 20-30%
recommend_production(Stock, ExpectedDemand, CurrentProduction, high, RecProduction, 'Reduce tomorrow production by 15-25% to exhaust existing inventory') :-
    Stock > ExpectedDemand,
    CurrentProduction > 0,
    RecProduction is max(0, round(CurrentProduction * 0.75)),
    !.

recommend_production(Stock, ExpectedDemand, CurrentProduction, high, RecProduction, 'Pause additional batch production until current stock clears') :-
    Stock >= ExpectedDemand,
    RecProduction is max(0, round(CurrentProduction * 0.50)),
    !.

% Medium Risk -> Slightly reduce by 10%
recommend_production(Stock, ExpectedDemand, CurrentProduction, medium, RecProduction, 'Slightly reduce production by 10% to prevent excess buffer') :-
    Stock > ExpectedDemand,
    CurrentProduction > 0,
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
% 4. REDISTRIBUTION RECOMMENDATION RULES
% ---------------------------------------------------------------------
evaluate_redistribution(Stock, ExpectedDemand, ExpiryDays, Surplus, true, 'Surplus exceeds daily demand and is within safe redistribution window') :-
    Surplus >= 5,
    ExpiryDays >= 1,
    ExpiryDays =< 4,
    Stock > ExpectedDemand,
    !.

evaluate_redistribution(_, _, ExpiryDays, _, false, 'Expired or unsafe for donation') :-
    ExpiryDays =< 0,
    !.

evaluate_redistribution(_, _, _, Surplus, false, 'No actionable surplus for external redistribution') :-
    Surplus < 5.
