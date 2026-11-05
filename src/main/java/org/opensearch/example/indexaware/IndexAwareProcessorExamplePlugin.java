/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.example.indexaware;

import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.search.indexaware.IndexAwareSearchRequestProcessor;

import java.util.List;

/**
 * Example plugin for the proposed {@code SearchPlugin#getIndexAwareSearchRequestProcessors()} hook
 * (RFC: "Field-level search behavior without search pipelines", Appendix A). Registers two independent
 * example processors, see {@link SynonymFieldProcessor} and {@link QuerqyReplaceProcessor} for what
 * each one does and why it's deliberately minimal.
 * <p>
 * This class is intentionally thin: all of each processor's actual behavior lives in its own class,
 * registered here as a module, the same way a search pipeline processor's logic lives in its own
 * {@code Processor} implementation rather than in the plugin that registers it. Neither processor
 * registers a {@code Setting}: per-field opt-in is read from each target field's own mapping {@code meta}
 * parameter instead, not from index or cluster settings.
 */
public class IndexAwareProcessorExamplePlugin extends Plugin implements SearchPlugin {

    @Override
    public List<IndexAwareSearchRequestProcessor> getIndexAwareSearchRequestProcessors() {
        return List.of(new SynonymFieldProcessor(), new QuerqyReplaceProcessor());
    }
}
