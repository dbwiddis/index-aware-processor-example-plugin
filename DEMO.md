# Demo: IndexAwareSearchRequestProcessor end to end

Captured 2026-11-06 against a local single-node cluster running this plugin on top of
`dbwiddis/OpenSearch` branch `index-aware-rewrite-hooks`
([commit d1d5fce42](https://github.com/dbwiddis/OpenSearch/commit/d1d5fce42), diff against
upstream `main` [here](https://github.com/opensearch-project/OpenSearch/compare/main...dbwiddis:OpenSearch:index-aware-rewrite-hooks)),
published to `mavenLocal()` as `org.opensearch:opensearch:3.10.0-SNAPSHOT`. This is raw output,
not a benchmark: a single node, three documents per index, one request per case.

Companion RFC: "Index-aware coordinator rewrite and search request hooks"
([opensearch-project/OpenSearch#23569](https://github.com/opensearch-project/OpenSearch/issues/23569)).
This demo exercises its two worked examples directly.

## How to reproduce

```bash
# 1. Build and publish the core hook (from the OpenSearch fork, branch index-aware-rewrite-hooks)
cd OpenSearch && ./gradlew publishToMavenLocal

# 2. Build and run this plugin against it
cd index-aware-processor-example-plugin && ./gradlew run
# cluster comes up on 127.0.0.1:9200

# 3. Turn on debug logging so the actual rewrite is visible, not just its effect on ranking
curl -X PUT localhost:9200/_cluster/settings -H 'Content-Type: application/json' -d '
{"transient": {"logger.org.opensearch.example.indexaware": "DEBUG"}}'

# 4. Create one index per processor (opted in via the brand field's own mapping meta) plus a plain control index
curl -X PUT localhost:9200/demo_synonym -H 'Content-Type: application/json' -d '
{"mappings": {"properties": {"brand": {"type": "text", "meta": {"synonym_field_processor.enabled": "true"}}}}}'

curl -X PUT localhost:9200/demo_replace -H 'Content-Type: application/json' -d '
{"mappings": {"properties": {"brand": {"type": "text", "meta": {"querqy_replace_processor.enabled": "true"}}}}}'

curl -X PUT localhost:9200/demo_plain -H 'Content-Type: application/json' -d '
{"mappings": {"properties": {"brand": {"type": "text"}}}}'

# 5. Index the same three documents into all three
for idx in demo_synonym demo_replace demo_plain; do
  curl -X POST "localhost:9200/$idx/_bulk?refresh=true" -H 'Content-Type: application/json' -d '
{"index":{"_id":"1"}}
{"brand":"sneakers by acme"}
{"index":{"_id":"2"}}
{"brand":"trainers by acme"}
{"index":{"_id":"3"}}
{"brand":"new balance running shoes"}
'
done
```

## 1. Synonym processor (`SynonymFieldProcessor`, worked example 1)

**Request** (identical against both indices): `match` on `brand: "sneakers"`.

| Index | Hits |
|---|---|
| `demo_synonym` (opted in) | doc 1 "sneakers by acme" (0.4648), doc 2 "trainers by acme" (0.3254) |
| `demo_plain` (control, same documents, not opted in) | doc 1 "sneakers by acme" (0.4648) only |

The synonym doc only appears, at a lower score than the canonical term, where the processor is
turned on: confirming the rewrite, not the data, produced the second hit.

**Captured rewrite** (debug log):

```json
// before
{"match": {"brand": {"query": "sneakers", "boost": 1.0, ...}}}

// after
{"bool": {"should": [
  {"match": {"brand": {"query": "sneakers", "boost": 1.0, ...}}},
  {"match": {"brand": {"query": "trainers", "boost": 0.7, ...}}}
]}}
```

## 2. Querqy-style replace processor (`QuerqyReplaceProcessor`, worked example 2)

**Request** (identical against both indices): `match` on `brand: "nb sneakers"`.

| Index | Hits |
|---|---|
| `demo_replace` (opted in) | doc 3 "new balance running shoes" (0.8242), doc 1 "sneakers by acme" (0.4648) |
| `demo_plain` (control, same documents, not opted in) | doc 1 "sneakers by acme" (0.4648) only |

Doc 3 doesn't surface at all without the rewrite: "nb" never appears in the text, so there's
nothing for an unrewritten query to match it on.

**Captured rewrite** (debug log):

```json
// before
{"match": {"brand": {"query": "nb sneakers", "boost": 1.0, ...}}}

// after
{"match": {"brand": {"query": "new balance sneakers", "boost": 1.0, ...}}}
```

## 3. Mixed alias: the documented v1 limitation, confirmed live

Aliased `demo_synonym` (opted in) together with `demo_plain` (not opted in) as `demo_mixed`, then
reran the sneakers query against the alias.

```bash
curl -X POST localhost:9200/_aliases -H 'Content-Type: application/json' -d '
{"actions": [
  {"add": {"index": "demo_synonym", "alias": "demo_mixed"}},
  {"add": {"index": "demo_plain", "alias": "demo_mixed"}}
]}'

curl localhost:9200/demo_mixed/_search -H 'Content-Type: application/json' -d '
{"query": {"match": {"brand": "sneakers"}}, "_source": "brand"}'
```

**Result:** both indices came back with the plain, unrewritten query; `demo_synonym` did **not**
get its synonym rewrite even though it's individually opted in. This is exactly the limitation
each processor's Javadoc discloses up front: v1 is all-or-nothing on a mixed request rather than
emitting one `_index`-scoped branch per index, the way a production implementation would. Worth
citing as evidence for prioritizing that refinement, not as a defect: it was the documented,
intended v1 behavior, now confirmed rather than just asserted.

## Stability

The cluster stayed healthy (`status: yellow`, 1 node) through every case above, including the
mixed-alias one. No sign of the recursive-rewrite / `StackOverflowError` failure mode
`SubSearchMarker` guards against: expected here, since neither example processor emits a
composite query, but confirms the guard isn't interfering with ordinary requests either.

## 4. Both processors together on the same field: ordering is predictable, not correct

Created a fourth index, `demo_both`, with *both* processors' meta flags enabled on the `brand`
field, indexed the same three documents, and ran `match brand:"nb sneakers"`: a query either
processor could plausibly act on.

**Result:** only `SynonymFieldProcessor` fired (it's registered first). It turned the query into
a `bool`, and `QuerqyReplaceProcessor`'s own precondition (`instanceof MatchQueryBuilder`) then
silently stopped holding: no error, no warning, its rewrite just never ran. Doc 3 ("new balance
running shoes"), which should have surfaced once "nb" became "new balance", never appears:

```json
{"_id": "1", "brand": "sneakers by acme",  "_score": 0.4648}
{"_id": "2", "brand": "trainers by acme",  "_score": 0.3254}
```

Debug log shows exactly one rewrite, from `SynonymFieldProcessor` only.

This is real, useful signal for the RFC, not a bug in either processor: **registration order
makes the outcome predictable, but not automatically correct.** The RFC's "Reasons for caution"
calls this out as a separate, deferred question, not decided here: whether the fix is a priority
value per processor, explicit dependency declarations, or conflict detection in core.

## What this demo does and doesn't prove

- **Proves:** the hook fires in the right place (after pipeline processors, before the coordinator
  rewrite), sees per-index context, and a processor's rewrite measurably changes what a plain
  `match` returns, on a real cluster, not a mocked one.
- **Doesn't prove:** performance at scale, behavior under concurrent/async rewrites, or the
  mixed-alias `_index`-branching refinement (out of scope for both example processors by design;
  see each class's Javadoc).
