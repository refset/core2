# 10. Arrow implementation

Date: 2021-09-09

## Status

Proposed

## Context

XTDB uses Arrow as its [data model](0002-data-model.md). We use [Arrow
Java](https://arrow.apache.org/docs/java/index.html), which is the
official Arrow implementation for the JVM.

Our current usage of this assumes a small set of predefined literal
types, and doesn't support nesting:

- null
- bigint
- float8
- varbinary
- varchar
- bit
- timestamp-milli
- duration-milli

It also makes assumptions about the type ids in unions, which are
based of the Flatbuffer ids of the types. This implementation lives in
`core2.types`. On top of this, during query processing, we use
`core2.relation`, which abstracts away parts of Arrow Java, also
limiting the type system to our small set, while adding support for
late materialisation. This allows to postpone copying during filters
for example.

A replacement for `core2.types` lives in `core2.types.nested` which
adds support for the full Arrow type system, including nesting, and
adds mapping of Java types to it. Its main addition is allowing to
build up nested unions incrementally based on valid Java input.

This replacement isn't yet integrated with the rest of the system. It
also currently represents all vectors as dense unions, even if there's
only a single type. This was done for simplicity.

On the write side, which is the complex part, there's functional
overlap between `core2.types.nested.ArrowAppendable`, and
`core2.relation.IAppendColumn` and `core2.types.PValueVector`.

On the read side, there's also functional overlap between
`core2.types.nested.ArrowReadable`, `core2.relation.ReadColumn` and
`core2.types.PValueVector`.

The two parts of the system which are most heavily affected by the
type system, `core2.expression` and
`core2.metadata`. `core2.tx-producer` and `core2.indexer` also uses
it, but in less involved ways.

There are two goals here:

1. to reach a single, pragmatic and well performing way of interacting
   with Arrow.
2. to add support for the full Arrow type system, and inference from
   Java to it.

There are several forces at play - easy of use and single
representation, which stands somewhat against having typed and
efficient code which can maybe only be generated at run time, like the
expression language. Clojure provides many powerful tools to work with
dynamically typed objects, like protocols and multi-methods, but these
all have their overhead. Java primitives can be magnitudes faster.

Pragmatically, there will be a difference between what's easiest done
with acceptable performance and reasonable amount of work, and how we
should really want this to ideally work.

## Decision

We will remove the current implementation in `core2.types` and also
remove all assumptions about Flatbuffer ids. We will support the full
Arrow type system with `core2.types.nested` as a base.

We will pragmatically work with Arrow Java where it makes sense, as it
gives a lot of stuff for free, despite its issues. In places where it
is hard, like constructing dense unions, we could try composing things
directly via child vectors and explicit offset/type buffers and
reconstruct the real vector when needed.

We should reach a single way to access the vectors, be it our own type
hierarchy (like `core2.relation`) or sub-classing ValueVector if
necessary to stay closer to Arrow Java. The representation needs to
stay close to Arrow in spirit, and not introduce too many other heap
allocated objects. The [Arrow C data
interface](https://arrow.apache.org/docs/format/CDataInterface.html)
could be a source of inspiration (as it was for the removed
`core2.mini-arrow` namespace).

We should only used boxed values when there's no choice, like on
initial inference from Java or when the vector is a dense union with
several incompatible children. Compatible types are things like the
different timestamps, numbers etc. that can be cast to each other in a
meaningful way. This implies that protocols and multi-methods cannot
be the core of the implementation - during run time. We should embrace
run time code generation and do this the Lisp way.

## Consequences

`core2.types.nested` is based on protocols, but these would only be
used when writing or reading data where the type isn't known as
above. Actual processing in `core2.expression` should only fallback to
these if necessary.

`core2.relation` needs to be consolidated into this world somehow:

1. sub-class ValueVector.
2. use dense unions with gaps in the offsets to simulate the
filtering. The [Arrow Columnar
Format](https://arrow.apache.org/docs/format/Columnar.html#dense-union)
allows this, "The respective offsets for each child value array must
be in order / increasing."
3. fully embrace it, but this may create duplication of Arrow Java
itself.