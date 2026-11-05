/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.example.indexaware;

import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.indexaware.IndexAwareContext;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Map;

public class SynonymFieldProcessorTests extends OpenSearchTestCase {

    private final SynonymFieldProcessor processor = new SynonymFieldProcessor();

    private static IndexMetadata indexMetadata(String name, boolean enabled) throws IOException {
        return IndexMetadata.builder(name)
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT).build())
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putMapping(
                "{\"properties\":{\"brand\":{\"type\":\"text\",\"meta\":{\""
                    + SynonymFieldProcessor.ENABLED_META_KEY
                    + "\":\""
                    + enabled
                    + "\"}}}}"
            )
            .build();
    }

    private static IndexMetadata indexMetadataWithNoMeta(String name) throws IOException {
        return IndexMetadata.builder(name)
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT).build())
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putMapping("{\"properties\":{\"brand\":{\"type\":\"text\"}}}")
            .build();
    }

    public void testRewritesWhenTargetFieldOptedIn() throws IOException {
        final IndexAwareContext context = new IndexAwareContext(Map.of("brand_demo", indexMetadata("brand_demo", true)));
        final SearchSourceBuilder source = new SearchSourceBuilder().query(new MatchQueryBuilder("brand", "sneakers"));

        final SearchSourceBuilder result = processor.process(source, context);

        assertTrue(result.query() instanceof BoolQueryBuilder);
        final BoolQueryBuilder rewritten = (BoolQueryBuilder) result.query();
        assertEquals(2, rewritten.should().size());
        assertTrue(hasMatch(rewritten, "brand", "sneakers", 1.0f));
        assertTrue(hasMatch(rewritten, "brand", "trainers", 0.7f));
    }

    public void testLeavesQueryUnchangedWhenTargetFieldNotOptedIn() throws IOException {
        final IndexAwareContext context = new IndexAwareContext(Map.of("brand_demo", indexMetadata("brand_demo", false)));
        final QueryBuilder original = new MatchQueryBuilder("brand", "sneakers");
        final SearchSourceBuilder source = new SearchSourceBuilder().query(original);

        final SearchSourceBuilder result = processor.process(source, context);

        assertSame(original, result.query());
    }

    public void testLeavesQueryUnchangedWhenFieldMappingHasNoMeta() throws IOException {
        final IndexAwareContext context = new IndexAwareContext(Map.of("brand_demo", indexMetadataWithNoMeta("brand_demo")));
        final QueryBuilder original = new MatchQueryBuilder("brand", "sneakers");
        final SearchSourceBuilder source = new SearchSourceBuilder().query(original);

        final SearchSourceBuilder result = processor.process(source, context);

        assertSame(original, result.query());
    }

    public void testLeavesQueryUnchangedWhenFieldDoesNotMatch() throws IOException {
        final IndexAwareContext context = new IndexAwareContext(Map.of("brand_demo", indexMetadata("brand_demo", true)));
        final QueryBuilder original = new MatchQueryBuilder("title", "sneakers");
        final SearchSourceBuilder source = new SearchSourceBuilder().query(original);

        final SearchSourceBuilder result = processor.process(source, context);

        assertSame(original, result.query());
    }

    public void testSkipsWhenAnyTargetIndexHasNotOptedIn() throws IOException {
        final IndexAwareContext context = new IndexAwareContext(
            Map.of("brand_demo", indexMetadata("brand_demo", true), "brand_plain", indexMetadata("brand_plain", false))
        );
        final QueryBuilder original = new MatchQueryBuilder("brand", "sneakers");
        final SearchSourceBuilder source = new SearchSourceBuilder().query(original);

        final SearchSourceBuilder result = processor.process(source, context);

        assertSame(original, result.query());
    }

    private static boolean hasMatch(BoolQueryBuilder bool, String field, String value, float boost) {
        for (QueryBuilder clause : bool.should()) {
            if (clause instanceof MatchQueryBuilder) {
                MatchQueryBuilder match = (MatchQueryBuilder) clause;
                if (field.equals(match.fieldName()) && value.equals(String.valueOf(match.value())) && match.boost() == boost) {
                    return true;
                }
            }
        }
        return false;
    }
}
