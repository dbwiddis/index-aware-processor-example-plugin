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
 * Worked example 2 from the companion RFC ("Field-level search behavior without search pipelines",
 * Appendix A): a Querqy-style replace processor.
 * <p>
 * {@code opensearch-project/search-processor#184} lists Querqy's rewriters (Common Rules, Replace, Word
 * Break, Number-Unit, Shingle) as candidates to implement as search pipeline processors. The simplest,
 * Replace, substitutes terms at a normalization stage (abbreviations, misspellings) before any other
 * rewriting runs. This processor implements that one primitive against the index-aware hook instead of a
 * pipeline processor, so it's correct per index without needing the client to attach a pipeline, and
 * without adopting the Querqy library wholesale.
 * <p>
 * Opt-in is read from the {@value #FIELD} field's own mapping {@code meta} parameter
 * ({@code "querqy_replace_processor.enabled": "true"}), not an index-level setting, for the same reason as
 * {@link SynonymFieldProcessor}: see its class javadoc for why {@code meta} is the stand-in used here.
 * <p>
 * <b>This is a deliberately minimal v1</b>, to demonstrate the hook, not a rewriting engine:
 * <ul>
 *   <li>one hardcoded field and one hardcoded replacement pair, instead of a real, configurable rule
 *       set;</li>
 *   <li>only handles a bare top-level {@code match} query, not one nested in {@code bool}/{@code dis_max}/etc;</li>
 *   <li>treats a mixed-index request as all-or-nothing (skips entirely unless every target index's
 *       {@value #FIELD} field opts in), rather than emitting one {@code _index}-scoped branch per index.</li>
 * </ul>
 * Each of these is a natural next refinement (see {@link SynonymFieldProcessor} for the same notes), not a
 * design limit of the hook itself.
 */
public class QuerqyReplaceProcessor implements IndexAwareSearchRequestProcessor {

    private static final Logger logger = LogManager.getLogger(QuerqyReplaceProcessor.class);

    /** The field-level mapping {@code meta} key this processor reads to opt a field in. */
    public static final String ENABLED_META_KEY = "querqy_replace_processor.enabled";

    private static final String FIELD = "brand";
    private static final Map<String, String> REPLACEMENTS = Map.of("nb", "new balance");

    @Override
    public SearchSourceBuilder process(SearchSourceBuilder source, IndexAwareContext context) {
        final QueryBuilder query = source.query();
        if (!(query instanceof MatchQueryBuilder)) {
            return source;
        }
        final MatchQueryBuilder match = (MatchQueryBuilder) query;
        if (!FIELD.equals(match.fieldName()) || !allTargetIndicesOptedIn(context)) {
            return source;
        }

        final String value = String.valueOf(match.value());
        final String replaced = applyReplacements(value);
        if (replaced.equals(value)) {
            return source;
        }
        final MatchQueryBuilder rewritten = new MatchQueryBuilder(FIELD, replaced).boost(match.boost());
        logger.debug("rewrote [{}] to [{}]", query, rewritten);
        return source.query(rewritten);
    }

    private String applyReplacements(String value) {
        final String[] tokens = value.split("\\s+");
        final StringBuilder rewritten = new StringBuilder();
        for (String token : tokens) {
            if (rewritten.length() > 0) {
                rewritten.append(' ');
            }
            rewritten.append(REPLACEMENTS.getOrDefault(token.toLowerCase(Locale.ROOT), token));
        }
        return rewritten.toString();
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
     * generic, mapping parameter (see {@link SynonymFieldProcessor} for the same approach).
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
