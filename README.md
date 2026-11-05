# index-aware-processor-example-plugin

**Status: throwaway demo, not production code.** This repo exists to support one RFC issue by
being clonable and runnable by anyone reviewing it. It isn't a real plugin, isn't maintained, and
nothing in it should be copied into production as-is: see each processor class's Javadoc for the
simplifications it deliberately makes.

## What this is

A worked example of the hook proposed in **"Field-level search behavior without search
pipelines"** ([opensearch-project/OpenSearch#23569](https://github.com/opensearch-project/OpenSearch/issues/23569)): a new
`SearchPlugin#getIndexAwareSearchRequestProcessors()` extension point that lets a plugin act on a
search request with resolved, per-index mapping context, before the coordinator query rewrite:
the same kind of thing a search pipeline request processor does, just driven by index/field
configuration instead of a pipeline attached to the request.

This plugin registers two independent example processors against that hook:

- **`SynonymFieldProcessor`**: rewrites a plain `match` on `brand` into an explicit,
  differently-weighted `bool`/`should` (canonical term vs. one hardcoded synonym), something a
  `synonym_graph` analyzer alone can't express.
- **`QuerqyReplaceProcessor`**: a single Querqy-style term-replacement rule
  (`opensearch-project/search-processor#184`'s simplest rewriter), applied index-aware instead of
  as a search pipeline processor.

Both are intentionally minimal (one hardcoded field, one hardcoded rule, bare top-level `match`
only, all-or-nothing on a mixed alias), enough to prove the hook works end to end, not a real
rule engine. Each field opts in through its own mapping `meta` parameter, not an index or cluster
setting, per the RFC's preferred attachment point. See [DEMO.md](DEMO.md) for a full captured run
(real requests, real results, the actual before/after rewrite from the debug log), and each
processor class's Javadoc for what's a demo shortcut vs. a limit of the hook itself.

## Prerequisites

The hook this plugin depends on isn't in any released OpenSearch version. You need a local build
of the proposed core change first:

```bash
git clone --branch index-aware-rewrite-hooks https://github.com/dbwiddis/OpenSearch.git
cd OpenSearch
./gradlew publishToMavenLocal
```

That publishes `org.opensearch:opensearch:3.10.0-SNAPSHOT` (with the new hook) to your local Maven
repository, which this plugin's `build.gradle` already points at via `mavenLocal()`.

## Build and run

```bash
git clone https://github.com/dbwiddis/index-aware-processor-example-plugin.git
cd index-aware-processor-example-plugin
./gradlew build          # compiles, runs the 8 unit tests, packages the plugin
./gradlew run             # starts a single-node cluster with the plugin installed, on :9200
```

Confirm it installed:

```bash
curl localhost:9200/_cat/plugins
```

From there, [DEMO.md](DEMO.md) has the exact commands to turn on debug logging, create indices
whose `brand` field mapping opts into each processor via its own `meta` parameter, index sample
documents, and see the rewrite happen: including a direct before/after comparison against a
control index with the processor turned off.

## License

Apache 2.0, see [LICENSE.txt](LICENSE.txt). Copyright OpenSearch Contributors, see
[NOTICE.txt](NOTICE.txt).
