% =====================================================================
% FoodWaste AI - SWI-Prolog Expert System Knowledge Base
% Rule-based Reasoning Engine for Food Waste Prediction, Prevention & Redistribution
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
% RISK LEVEL ASSESSMENT RULES
% Risk levels: high, medium, low
% ---------------------------------------------------------------------

% High Risk Rule 1: Expiry imminent (<= 1 day) and stock > 0
assess_waste_risk(Stock, _, ExpiryDays, _, high, ['Expiry is imminent (within 1 day)', 'Stock remaining requires immediate consumption']) :-
    ExpiryDays =< 1,
    Stock > 0,
    !.

% High Risk Rule 2: Stock significantly exceeds demand (>130%) AND (near expiry <= 3 days OR high historical waste >= 0.20)
assess_waste_risk(Stock, ExpectedDemand, ExpiryDays, HistWasteRate, high, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.30,
    (ExpiryDays =< 3 ; HistWasteRate >= 0.20),
    build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons),
    !.

% High Risk Rule 3: Historical waste rate is severe (>= 30%) with excess stock
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, high, ['Historical waste rate is critically high (>= 30%)', 'Stock exceeds expected demand']) :-
    HistWasteRate >= 0.30,
    Stock > ExpectedDemand,
    !.

% Medium Risk Rule 1: Expiry approaching (2-3 days) with positive stock
assess_waste_risk(Stock, _, ExpiryDays, _, medium, ['Expiry approaching within 3 days', 'Requires monitoring to prevent spoilage']) :-
    ExpiryDays > 1,
    ExpiryDays =< 3,
    Stock > 0,
    !.

% Medium Risk Rule 2: Stock moderately exceeds expected demand (110% - 130%)
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, medium, Reasons) :-
    ExpectedDemand > 0,
    Ratio is Stock / ExpectedDemand,
    Ratio >= 1.10,
    Ratio < 1.30,
    build_medium_reasons(Ratio, HistWasteRate, Reasons),
    !.

% Medium Risk Rule 3: Moderate historical waste rate (15% - 30%)
assess_waste_risk(Stock, ExpectedDemand, _, HistWasteRate, medium, ['Moderate historical waste rate recorded', 'Potential over-ordering pattern']) :-
    HistWasteRate >= 0.15,
    Stock >= ExpectedDemand,
    !.

% Low Risk Rule: Well-balanced stock, safe expiry, and low historical waste
assess_waste_risk(_, _, ExpiryDays, HistWasteRate, low, ['Stock is balanced with expected demand', 'Safe shelf life remaining', 'Low historical waste rate']) :-
    ExpiryDays > 3,
    HistWasteRate < 0.15,
    !.

% Fallback Low Risk
assess_waste_risk(_, _, _, _, low, ['Standard operational parameters', 'Normal consumption expected']).

% ---------------------------------------------------------------------
% HELPER REASON BUILDERS
% ---------------------------------------------------------------------
build_high_reasons(Ratio, ExpiryDays, HistWasteRate, Reasons) :-
    findall(R, high_reason_item(Ratio, ExpiryDays, HistWasteRate, R), Reasons).

high_reason_item(Ratio, _, _, 'Current stock substantially exceeds expected demand') :- Ratio >= 1.30.
high_reason_item(_, ExpiryDays, _, 'Expiry date is near (within 3 days)') :- ExpiryDays =< 3.
high_reason_item(_, _, HistWasteRate, 'Historical waste rate is elevated (>= 20%)') :- HistWasteRate >= 0.20.

build_medium_reasons(Ratio, HistWasteRate, Reasons) :-
    findall(R, medium_reason_item(Ratio, HistWasteRate, R), Reasons).

medium_reason_item(Ratio, _, 'Current stock moderately exceeds forecasted demand') :- Ratio >= 1.10.
medium_reason_item(_, HistWasteRate, 'Historical waste rate indicates slight overproduction') :- HistWasteRate >= 0.15.

% ---------------------------------------------------------------------
% PRODUCTION RECOMMENDATION RULES
% ---------------------------------------------------------------------
% If High Risk with large surplus -> recommend reduction by surplus or 20-30%
recommend_production(Stock, ExpectedDemand, CurrentProduction, high, RecProduction, 'Reduce production by 25% to exhaust existing inventory') :-
    Stock > ExpectedDemand,
    CurrentProduction > 0,
    RecProduction is max(0, round(CurrentProduction * 0.75)),
    !.

recommend_production(Stock, ExpectedDemand, CurrentProduction, high, RecProduction, 'Pause additional batch production until current stock clears') :-
    Stock >= ExpectedDemand,
    RecProduction is max(0, round(CurrentProduction * 0.50)),
    !.

% If Medium Risk -> recommend slight reduction (10-15%)
recommend_production(Stock, ExpectedDemand, CurrentProduction, medium, RecProduction, 'Slightly reduce production by 10% to prevent excess buffer') :-
    Stock > ExpectedDemand,
    CurrentProduction > 0,
    RecProduction is max(0, round(CurrentProduction * 0.90)),
    !.

% If Low Risk and Demand is higher than stock -> maintain or adjust to demand
recommend_production(Stock, ExpectedDemand, CurrentProduction, low, RecProduction, 'Maintain optimal production aligned with customer demand') :-
    Stock < ExpectedDemand,
    Deficit is ExpectedDemand - Stock,
    RecProduction is max(CurrentProduction, Deficit),
    !.

recommend_production(_, _, CurrentProduction, low, CurrentProduction, 'Maintain standard scheduled production batch').

% ---------------------------------------------------------------------
% PRIORITY USAGE RULES
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
% REDISTRIBUTION RULES
% ---------------------------------------------------------------------
% Redistribute if surplus >= 5 units and expiry between 1 and 3 days (safe for consumption, won't sell in time)
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
