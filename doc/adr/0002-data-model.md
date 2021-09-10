# 2. Data model

Date: 2021-09-09

## Status

Accepted

## Context

XTDB is a dynamic document database and needs a clearly defined data
model.

## Decision

We will use [Apache Arrow]( https://arrow.apache.org/) which is an
emerging industry standard for columnar data.

Arrow supports nested data via lists and structs, and supports dynamic
typing via unions.

### EDN

Support for "standard" EDN will be provided via Arrow extension types
over the following Arrow types:

1. persistent list - list.
2. persistent set - list.
3. char - uint2.
4. keyword - varchar.
5. symbol - varchar.
6. uuid - fixedsizebinary(16).
7  map with arbitrary keys - map.

Non-Clojure users will simply see the basic Arrow type.

## Consequences

All internal query processing will use Arrow. Persistent storage in
XTDB are all valid Arrow IPC files. Arrow IPC generated by other tools
should be possible to query via XTDB seamlessly and vice versa.