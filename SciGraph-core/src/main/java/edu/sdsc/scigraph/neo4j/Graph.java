/**
 * Copyright (C) 2014 The SciGraph authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.sdsc.scigraph.neo4j;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Sets.newHashSet;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.apache.lucene.analysis.StopAnalyzer;
import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.AutoIndexer;
import org.neo4j.graphdb.index.IndexManager;
import org.neo4j.graphdb.index.ReadableIndex;
import org.neo4j.graphdb.index.UniqueFactory;
import org.neo4j.helpers.collection.MapUtil;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Optional;

import edu.sdsc.scigraph.frames.CommonProperties;
import edu.sdsc.scigraph.frames.Concept;
import edu.sdsc.scigraph.frames.NodeProperties;
import edu.sdsc.scigraph.lucene.LuceneUtils;
import edu.sdsc.scigraph.lucene.VocabularyIndexAnalyzer;

public class Graph {

  private static final Logger logger = Logger.getLogger(Graph.class.getName());

  public static final String UNIQUE_PROPERTY = CommonProperties.URI;

  private static final Set<String> NODE_PROPERTIES_TO_INDEX = newHashSet(CommonProperties.URI,
      NodeProperties.LABEL, NodeProperties.LABEL + LuceneUtils.EXACT_SUFFIX,
      CommonProperties.CURIE, CommonProperties.ONTOLOGY, CommonProperties.FRAGMENT,
      Concept.CATEGORY, Concept.SYNONYM, Concept.SYNONYM + LuceneUtils.EXACT_SUFFIX);
  private static final Set<String> RELATIONSHIP_PROPERTIES_TO_INDEX = newHashSet(CommonProperties.URI);
  private static final Set<String> EXACT_PROPERTIES = newHashSet(NodeProperties.LABEL,
      Concept.SYNONYM);

  private static final Map<String, String> INDEX_CONFIG = MapUtil.stringMap(IndexManager.PROVIDER,
      "lucene", "analyzer", VocabularyIndexAnalyzer.class.getName());

  private final GraphDatabaseService graphDb;
  private final ExecutionEngine engine;
  private final ReadableIndex<Node> nodeAutoIndex;

  private final Class<?> nodeType;

  @Inject
  public Graph(GraphDatabaseService graphDb, Class<?> nodeType) {
    this.graphDb = graphDb;
    this.nodeType = nodeType;
    this.engine = new ExecutionEngine(graphDb);
    if (!graphDb.index().getNodeAutoIndexer().isEnabled()) {
      setupAutoIndexing();
    }
    this.nodeAutoIndex = graphDb.index().getNodeAutoIndexer().getAutoIndex();
  }

  private void setupIndex(AutoIndexer<?> index, Set<String> properties) {
    for (String property : properties) {
      index.startAutoIndexingProperty(property);
    }
    index.setEnabled(true);
  }

  private void setupAutoIndexing() {
    try (Transaction tx = graphDb.beginTx()) {
      graphDb.index().forNodes("node_auto_index", INDEX_CONFIG);
      setupIndex(graphDb.index().getNodeAutoIndexer(), NODE_PROPERTIES_TO_INDEX);
      graphDb.index().forRelationships("relationship_auto_index", INDEX_CONFIG);
      setupIndex(graphDb.index().getRelationshipAutoIndexer(), RELATIONSHIP_PROPERTIES_TO_INDEX);
      tx.success();
    }
  }

  public void shutdown() {
    graphDb.shutdown();
  }

  public static URI getURI(String uri) {
    checkNotNull(uri);
    try {
      return new URI(uri);
    } catch (URISyntaxException e) {
      checkState(false, "URIs passed to this method should always be valid: " + uri);
      return null;
    }
  }

  public GraphDatabaseService getGraphDb() {
    return graphDb;
  }

  public ExecutionEngine getExecutionEngine() {
    return engine;
  }

  public ReadableIndex<Node> getNodeAutoIndex() {
    return nodeAutoIndex;
  }

  public boolean nodeExists(String uri) {
    return nodeExists(getURI(uri));
  }

  @Transactional
  public boolean nodeExists(URI uri) {
    checkNotNull(uri);
    try (Transaction tx = graphDb.beginTx()) {
      boolean exists = null != nodeAutoIndex.get(CommonProperties.URI, uri.toString()).getSingle();
      tx.success();
      return exists;
    }
  }

  public Node getOrCreateNode(String uri) {
    return getOrCreateNode(getURI(uri));
  }

  public Node getOrCreateNode(final URI uri) {
    checkNotNull(uri);
    try (Transaction tx = graphDb.beginTx()) {
      UniqueFactory<Node> factory = new UniqueFactory.UniqueNodeFactory(graphDb, "uniqueNodeIndex") {
        @Override
        protected void initialize(Node created, Map<String, Object> properties) {
          logger.fine("Creating node: " + properties.get(UNIQUE_PROPERTY));
          try (Transaction tx = graphDb.beginTx()) {
            created.setProperty(UNIQUE_PROPERTY, properties.get(UNIQUE_PROPERTY));
            created.setProperty(CommonProperties.FRAGMENT, GraphUtil.getFragment(uri));
            tx.success();
          }
        }
      };
      Node n = factory.getOrCreate(UNIQUE_PROPERTY, uri.toString());
      tx.success();
      return n;
    }
  }

  public Optional<Node> getNode(String uri) {
    return getNode(getURI(uri));
  }

  public Optional<Node> getNode(final URI uri) {
    if (nodeExists(uri)) {
      return Optional.of(getOrCreateNode(uri));
    }
    return Optional.absent();
  }

  public Node getNode(Concept framedNode) {
    long id = framedNode.getId();
    return graphDb.getNodeById(id);
  }

  Concept getVertex(long id) {
    Concept concept = new Concept();
    try (Transaction tx = graphDb.beginTx()) {
      Node n = graphDb.getNodeById(id);
      concept.setAnonymous((boolean) n.getProperty(Concept.ANONYMOUS, false));
      concept.setInferred((boolean) n.getProperty(Concept.INFERRED, false));
      concept.setNegated((boolean) n.getProperty(Concept.NEGATED, false));
      concept.setCurie((String) n.getProperty(Concept.CURIE, null));

      concept.setFragment((String) n.getProperty(Concept.FRAGMENT, null));
      concept.setId(id);
      concept.setOntology((String) n.getProperty(Concept.ONTOLOGY, null));
      concept.setOntologyVersion((String) n.getProperty(Concept.ONTOLOGY_VERSION, null));
      concept.setParentOntology((String) n.getProperty(Concept.PARENT_ONTOLOGY, null));
      concept.setPreferredLabel((String) n.getProperty(Concept.PREFERRED_LABEL, null));
      concept.setUri((String) n.getProperty(Concept.URI, null));

      for (String definition : getProperties(n, Concept.DEFINITION, String.class)) {
        concept.addDefinition(definition);
      }
      for (String abbreviation : getProperties(n, Concept.ABREVIATION, String.class)) {
        concept.addAbbreviation(abbreviation);
      }
      for (String acronym : getProperties(n, Concept.ACRONYM, String.class)) {
        concept.addAcronym(acronym);
      }
      for (String category : getProperties(n, Concept.CATEGORY, String.class)) {
        concept.addCategory(category);
      }
      for (String label : getProperties(n, Concept.LABEL, String.class)) {
        concept.addLabel(label);
      }
      for (String synonym : getProperties(n, Concept.SYNONYM, String.class)) {
        concept.addSynonym(synonym);
      }
      for (String type : getProperties(n, Concept.TYPE, String.class)) {
        concept.addType(type);
      }
      tx.success();
    }

    return concept;
  }

  public Concept getOrCreateFramedNode(String uri) {
    Node n = getOrCreateNode(uri);
    return getVertex(n.getId());
  }

  public Concept getOrCreateFramedNode(Node node) {
    return getVertex(node.getId());
  }

  public Iterable<Concept> getOrCreateFramedNodes(Iterable<Node> nodes) {
    return transform(nodes, new Function<Node, Concept>() {

      @Override
      public Concept apply(Node node) {
        return getOrCreateFramedNode(node);
      }

    });
  }

  public Optional<Concept> getFramedNode(String uri) {
    if (nodeExists(uri)) {
      return Optional.of(getOrCreateFramedNode(uri));
    }
    return Optional.absent();
  }

  public boolean hasRelationship(Node a, Node b, RelationshipType type) {
    return hasRelationship(a, b, type, Optional.<URI> absent());
  }

  public boolean hasRelationship(Node a, Node b, RelationshipType type, String uri) {
    return hasRelationship(a, b, type, Optional.of(getURI(uri)));
  }

  public boolean hasRelationship(Node a, Node b, RelationshipType type, Optional<URI> uri) {
    checkNotNull(a);
    checkNotNull(b);
    checkNotNull(type);
    checkNotNull(uri);
    try (Transaction tx = graphDb.beginTx()) {
      for (Relationship r : a.getRelationships(type)) {
        if (uri.isPresent() && r.getEndNode().equals(b)) {
          if (r.getProperty(CommonProperties.URI).equals(uri.get().toString())) {
            tx.success();
            return true;
          }
        } else if (!uri.isPresent() && r.getEndNode().equals(b)) {
          tx.success();
          return true;
        }
      }
      tx.success();
      return false;
    }
  }

  public Relationship getOrCreateRelationship(Node a, Node b, RelationshipType type) {
    return getOrCreateRelationship(a, b, type, Optional.<URI> absent());
  }

  public Relationship getOrCreateRelationship(Node a, Node b, RelationshipType type, String uri) {
    return getOrCreateRelationship(a, b, type, Optional.of(getURI(uri)));
  }

  public Relationship getOrCreateRelationship(final Node a, final Node b,
      final RelationshipType type, final Optional<URI> uri) {
    checkNotNull(a);
    checkNotNull(b);
    checkNotNull(type);
    checkNotNull(uri);

    try (Transaction tx = graphDb.beginTx()) {
      UniqueFactory<Relationship> factory = new UniqueFactory.UniqueRelationshipFactory(graphDb,
          "uniqueRelationshipIndex") {
        @Override
        protected Relationship create(Map<String, Object> properties) {
          try (Transaction tx = graphDb.beginTx()) {
            Relationship r = a.createRelationshipTo(b, type);
            if (uri.isPresent()) {
              r.setProperty(CommonProperties.URI, uri.get().toString());
              r.setProperty(CommonProperties.FRAGMENT, GraphUtil.getFragment(uri.get()));
            }
            tx.success();
            return r;
          }
        }
      };

      Relationship r = factory.getOrCreate("relationship", a.getProperty(CommonProperties.URI)
          + type.name() + b.getProperty(CommonProperties.URI));
      tx.success();
      return r;
    }
  }

  public Collection<Relationship> getOrCreateRelationshipPairwise(Collection<Node> nodes,
      RelationshipType type, Optional<URI> uri) {
    Set<Relationship> relationships = new HashSet<>();
    for (Node start : nodes) {
      for (Node end : nodes) {
        if (start.equals(end)) {
          continue;
        }
        relationships.add(getOrCreateRelationship(start, end, type, uri));
      }
    }
    return relationships;
  }

  /***
   * Set property to single valued value for node or relationship
   * 
   * @param container
   *          node or relationship
   * @param property
   * @param value
   */
  @Transactional
  public void setProperty(PropertyContainer container, String property, Object value) {
    // Ignore whitespace properties and stop words
    // HACK: This stop word check should be done at OWL load time
    if (value instanceof String
        && (CharMatcher.WHITESPACE.matchesAllOf((String) value) || StopAnalyzer.ENGLISH_STOP_WORDS_SET
            .contains(((String) value).toLowerCase()))) {
      return;
    }
    container.setProperty(property, value);
    if (EXACT_PROPERTIES.contains(property)) {
      container.setProperty(property + LuceneUtils.EXACT_SUFFIX, value);
    }
  }

  /***
   * Add value to property for a node or relationship.
   * <p>
   * If necessary this will concatenate value to an array.
   * <ul>
   * <li>Duplicate values for the same property will be ignored.</li>
   * <li>Property value insertion order will be preserved.</li>
   * </ul>
   * 
   * @param container
   *          node or relationship
   * @param property
   * @param value
   */
  @Transactional
  public void addProperty(PropertyContainer container, String property, Object value) {
    // Ignore whitespace properties and stop words
    // HACK: This stop word check should be done at OWL load time
    if (value instanceof String
        && (CharMatcher.WHITESPACE.matchesAllOf((String) value) || StopAnalyzer.ENGLISH_STOP_WORDS_SET
            .contains(((String) value).toLowerCase()))) {
      return;
    }
    GraphUtil.addProperty(container, property, value);
    if (EXACT_PROPERTIES.contains(property)) {
      addProperty(container, property + LuceneUtils.EXACT_SUFFIX, value);
    }
  }

  /***
   * @param container
   * @param property
   * @param type
   * @return the single property value for node with the supplied type
   */
  public <T> Optional<T> getProperty(PropertyContainer container, String property, Class<T> type) {
    return GraphUtil.getProperty(container, property, type);
  }

  /***
   * @param container
   * @param property
   * @param type
   * @return a list of properties for node with the supplied type
   */
  public <T> List<T> getProperties(PropertyContainer container, String property, Class<T> type) {
    return GraphUtil.getProperties(container, property, type);
  }

  public ResourceIterator<Map<String, Object>> runCypherQuery(String query) {
    ExecutionResult result = engine.execute(query);
    return result.iterator();
  }

}
