/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.example.indexaware;

import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.indexaware.IndexAwareContext;
import org.opensearch.search.indexaware.IndexAwareSearchRequestProcessor;

import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Worked example 1 from the companion RFC ("Field-level search behavior without search pipelines",
 * Appendix A): a synonym-aware field.
 * <p>
 * The {@code synonym_graph} token filter already expands synonyms during analysis, but every expansion
 * scores equally: a match on {@value #CANONICAL_TERM} and its synonym {@value #SYNONYM_TERM} can't be
 * weighted differently, and an analyzer can't vary by index the way a mixed alias needs. This processor
 * rewrites a plain {@code match} on {@value #FIELD} into an explicit, weighted query instead, so the
 * canonical term outscores its synonym, and, because it's index-aware, only where the target field opts in.
 * <p>
 * Opt-in is read from the {@value #FIELD} field's own mapping {@code meta} parameter
 * ({@code "synonym_field_processor.enabled": "true"}), not an index-level setting. Per the RFC's preferred
 * attachment point, field-owned behavior is configured on the field's existing type, not through a
 * separate settings object and not through a new type per capability. Core has no general
 * plugin-contributed mapping parameter yet (only the experimental, storage-format-scoped
 * {@code DataFormatRegistry}), so this reads the field's existing, generic {@code meta} parameter
 * ({@code Parameter<Map<String, String>>}, already present on virtually every field mapper) as a stand-in.
 * <p>
 * <b>This is a deliberately minimal v1</b>, to demonstrate the hook, not a synonym engine:
 * <ul>
 *   <li>one hardcoded field and one hardcoded synonym pair, instead of reading a field's real synonym
 *       configuration ({@code synonyms}/{@code synonyms_path});</li>
 *   <li>only handles a bare top-level {@code match} query, not one nested in {@code bool}/{@code dis_max}/etc;</li>
 *   <li>treats a mixed-index request as all-or-nothing (skips entirely unless every target index's
 *       {@value #FIELD} field opts in), rather than emitting one {@code _index}-scoped branch per index the
 *       way a production implementation would.</li>
 * </ul>
 * Each of these is a natural next refinement, not a design limit of the hook itself.
 */
public class SynonymFieldProcessor implements IndexAwareSearchRequestProcessor {

    private static final Logger logger = LogManager.getLogger(SynonymFieldProcessor.class);

    /** The field-level mapping {@code meta} key this processor reads to opt a field in. */
    public static final String ENABLED_META_KEY = "synonym_field_processor.enabled";

    private static final String FIELD = "brand";
    private static final String CANONICAL_TERM = "sneakers";
    private static final String SYNONYM_TERM = "trainers";
    private static final float SYNONYM_BOOST = 0.7f;

    @Override
    public SearchSourceBuilder process(SearchSourceBuilder source, IndexAwareContext context) {
        final QueryBuilder query = source.query();
        if (!(query instanceof MatchQueryBuilder)) {
            return source;
        }
        final MatchQueryBuilder match = (MatchQueryBuilder) query;
        if (!FIELD.equals(match.fieldName())) {
            return source;
        }
        final String value = String.valueOf(match.value());
        if (!value.toLowerCase(Locale.ROOT).contains(CANONICAL_TERM) || !allTargetIndicesOptedIn(context)) {
            return source;
        }

        final BoolQueryBuilder rewritten = new BoolQueryBuilder();
        rewritten.should(new MatchQueryBuilder(FIELD, value).boost(match.boost()));
        rewritten.should(
            new MatchQueryBuilder(FIELD, value.toLowerCase(Locale.ROOT).replace(CANONICAL_TERM, SYNONYM_TERM)).boost(SYNONYM_BOOST)
        );
        logger.debug("rewrote [{}] to [{}]", query, rewritten);
        return source.query(rewritten);
    }

    /**
     * All-or-nothing for v1: skip unless every target index's {@value #FIELD} field has opted in via its
     * own mapping. See the class javadoc.
     */
    private boolean allTargetIndicesOptedIn(IndexAwareContext context) {
        final Map<String, IndexMetadata> targets = context.getTargetIndices();
        if (targets.isEmpty()) {
            return false;
        }
        for (IndexMetadata metadata : targets.values()) {
            if (!"true".equals(fieldMeta(metadata, FIELD, ENABLED_META_KEY))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a single {@code meta} entry off a field's own mapping ({@code properties.<field>.meta.<key>}),
     * or null if the mapping, the field, its {@code meta} map, or the entry itself isn't present. This
     * navigates the raw mapping source directly, rather than through a field-type-specific {@code Mapper},
     * so it works for any field type without core needing to expose a typed accessor for a single, already
     * generic, mapping parameter.
     */
    @SuppressWarnings("unchecked")
    private static String fieldMeta(IndexMetadata metadata, String field, String key) {
        final MappingMetadata mapping = metadata.mapping();
        if (mapping == null) {
            return null;
        }
        final Object properties = mapping.sourceAsMap().get("properties");
        if (!(properties instanceof Map)) {
            return null;
        }
        final Object fieldMapping = ((Map<String, Object>) properties).get(field);
        if (!(fieldMapping instanceof Map)) {
            return null;
        }
        final Object meta = ((Map<String, Object>) fieldMapping).get("meta");
        if (!(meta instanceof Map)) {
            return null;
        }
        final Object metaValue = ((Map<String, Object>) meta).get(key);
        return metaValue == null ? null : String.valueOf(metaValue);
    }
}
