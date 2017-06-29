/*
 * Copyright 2011-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.appng.search.indexer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.appng.api.SiteProperties;
import org.appng.api.model.Application;
import org.appng.api.model.Properties;
import org.appng.api.model.Site;
import org.appng.api.search.Consumer;
import org.appng.api.search.DocumentEvent;
import org.appng.api.search.DocumentProducer;
import org.appng.search.DocumentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the global lucene search index for a {@link Site}. Therefore, every {@link Application} of a {@link Site} is
 * being checked for instances of {@link DocumentProvider}, which can offer some {@link DocumentProducer}s.
 * 
 * @author Matthias Müller
 * 
 * @see DocumentProvider
 * @see DocumentProducer
 */
public class GlobalIndexer {

	private static final Logger LOG = LoggerFactory.getLogger(GlobalIndexer.class);
	private Consumer<DocumentEvent, DocumentProducer> indexer;

	public GlobalIndexer(Consumer<DocumentEvent, DocumentProducer> indexer) {
		this.indexer = indexer;
	}

	public void doIndex(Site site, String jspType) {
		LOG.debug("start indexing for site {}", site.getName());

		Properties properties = site.getProperties();
		String sitePath = properties.getString(SiteProperties.SITE_ROOT_DIR);
		String seData = sitePath + properties.getString(SiteProperties.WWW_DIR);
		File dataDir = new File(seData).getAbsoluteFile();

		String indexConfig = properties.getString(SiteProperties.INDEX_CONFIG);
		String tagPrefix = properties.getString(SiteProperties.TAG_PREFIX);
		IndexConfig config = IndexConfig.getInstance(indexConfig, tagPrefix);
		Integer timeout = properties.getInteger(SiteProperties.INDEX_TIMEOUT, 5000);
		List<String> extensions = site.getProperties().getList(SiteProperties.INDEX_FILETYPES, ",");

		FileSystemProvider fileSystemProvider = new FileSystemProvider(config, extensions, timeout, jspType, dataDir,
				new ArrayList<File>());
		processProducer(site, null, fileSystemProvider, timeout);

		for (Application application : site.getApplications()) {
			String[] documentProviders = application.getBeanNames(DocumentProvider.class);
			for (String documentProviderName : documentProviders) {
				DocumentProvider documentProvider = application.getBean(documentProviderName, DocumentProvider.class);
				int processed = processProducer(site, application, documentProvider, timeout);
				LOG.debug("processed {}  from application {} wich returned {} DocumentProducers", documentProvider
						.getClass().getName(), application.getName(), processed);
			}
		}
	}

	private int processProducer(Site site, Application application, DocumentProvider documentProvider, Integer timeout) {
		try {
			Iterable<DocumentProducer> documentProducers = documentProvider.getDocumentProducers(site, application);
			return putWithTimeout(documentProducers, timeout);
		} catch (InterruptedException e) {
			LOG.error("error while processing" + documentProvider.getClass().getName(), e);
		} catch (TimeoutException e) {
			LOG.error("error while processing" + documentProvider.getClass().getName(), e);
		}
		return 0;
	}

	private int putWithTimeout(Iterable<DocumentProducer> producers, Integer timeout) {
		int count = 0;
		if (null != producers) {
			for (DocumentProducer producer : producers) {
				try {
					indexer.putWithTimeout(producer, timeout);
					count++;
				} catch (InterruptedException e) {
					LOG.error("error processing " + producer, e);
				} catch (TimeoutException e) {
					LOG.error("error processing " + producer, e);
				}
			}
		}
		return count;
	}
}