# 11. Classic tx-fn

Date: 2021-09-14

## Status

Proposed

## Context

Provide classic-like functionality for tx-fns.

Currently users can submit/trigger tx-fns via HTTP or the peer-like API, therefore this ADR expects to maintain this.

There is no ADR currently for allowing users to trigger/manage tx-fns via SQL, whereby normal SQL transactions would be the expected correlating behavour.

## Decision

## Consequences

There may be some confusion/tension/discussion around a clean/pure remote-first API supporting what is minimally needed by SQL & Datalog users, and where the boundary lies between supporting classic like functionality exclusively for Clojure/Datalog users.