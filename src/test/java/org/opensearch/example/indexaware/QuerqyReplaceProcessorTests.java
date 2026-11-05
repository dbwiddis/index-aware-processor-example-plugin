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
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.indexaware.IndexAwareContext;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Map;

public class QuerqyReplaceProcessorTests extends OpenSearchTestCase {

    private final QuerqyReplaceProcessor processor = new QuerqyReplaceProcessor();

    private static IndexMetadata indexMetadata(String name, boolean enabled) throws IOException {
        return IndexMetadata.builder(name)
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT).build())
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putMapping(
                "{\"properties\":{\"brand\":{\"type\":\"text\",\"meta\":{\""
                    + QuerqyReplaceProcessor.ENABLED_META_KEY
                    + "\":\""
                    + enabled
                    + "\"}}}}"
            )
            .build();
    }

    public void testReplacesAbbreviationWhenTargetFieldOptedIn() throws IOException {
        final IndexAwareContext context = new IndexAwareContext(Map.of("brand_demo", indexMetadata("brand_demo", true)));
        final SearchSourceBuilder source = new SearchSourceBuilder().query(new MatchQueryBuilder("brand", "nb sneakers"));

        final SearchSourceBuilder result = processor.process(source, context);

        assertTrue(result.query() instanceof MatchQueryBuilder);
        final MatchQueryBuilder rewritten = (MatchQueryBuilder) result.query();
        assertEquals("brand", rewritten.fieldName());
        assertEquals("new balance sneakers", rewritten.value());
    }

    public void testLeavesQueryUnchangedWhenTargetFieldNotOptedIn() throws IOException {
        final IndexAwareContext context = new IndexAwareContext(Map.of("brand_demo", indexMetadata("brand_demo", false)));
        final QueryBuilder original = new MatchQueryBuilder("brand", "nb sneakers");
        final SearchSourceBuilder source = new SearchSourceBuilder().query(original);

        final SearchSourceBuilder result = processor.process(source, context);

        assertSame(original, result.query());
    }

    public void testLeavesQueryUnchangedWhenNoReplacementApplies() throws IOException {
        final IndexAwareContext context = new IndexAwareContext(Map.of("brand_demo", indexMetadata("brand_demo", true)));
        final QueryBuilder original = new MatchQueryBuilder("brand", "new balance sneakers");
        final SearchSourceBuilder source = new SearchSourceBuilder().query(original);

        final SearchSourceBuilder result = processor.process(source, context);

        assertSame(original, result.query());
    }
}
