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
package io.scigraph.internal;

import io.scigraph.frames.NodeProperties;
import io.scigraph.neo4j.GraphUtil;
import io.scigraph.owlapi.OwlLabels;
import io.scigraph.owlapi.OwlRelationships;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.Uniqueness;
import org.neo4j.tooling.GlobalGraphOperations;

import com.google.common.base.Optional;
import com.google.common.collect.Iterators;
import com.tinkerpop.blueprints.Graph;

public class EquivalenceAspect implements GraphAspect {
  private static final Logger logger = Logger.getLogger(EquivalenceAspect.class.getName());

  static final RelationshipType IS_EQUIVALENT = OwlRelationships.OWL_EQUIVALENT_CLASS;
  static final RelationshipType SAME_AS = OwlRelationships.OWL_SAME_AS;
  static final String ORIGINAL_REFERENCE_KEY_SOURCE = "equivalentOriginalNodeSource";
  static final String ORIGINAL_REFERENCE_KEY_TARGET = "equivalentOriginalNodeTarget";
  static final Label CLIQUE_LEADER_LABEL = DynamicLabel.label("cliqueLeader");
  static final String CLIQUE_LEADER_PROPERTY = "cliqueLeader";

  private List<String> prefixLeaderPriority; // TODO temporary
  private String leaderAnnotationProperty = "http://www.monarchinitiative.org/MONARCH_cliqueLeader"; // TODO
                                                                                                     // temporary

  private final GraphDatabaseService graphDb;

  @Inject
  public EquivalenceAspect(GraphDatabaseService graphDb) {
    this.graphDb = graphDb;
    tmpInitLeaderPriority();
  }

  private void tmpInitLeaderPriority() {
    prefixLeaderPriority =
        Arrays.asList("http://www.ncbi.nlm.nih.gov/gene/", "http://www.ncbi.nlm.nih.gov/pubmed/", "http://purl.obolibrary.org/obo/NCBITaxon_",
            "http://identifiers.org/ensembl/", "http://purl.obolibrary.org/obo/DOID_", "http://purl.obolibrary.org/obo/HP_");
  }

  @Override
  public void invoke(Graph graph) {
    logger.info("Starting clique merge");
    GlobalGraphOperations globalGraphOperations = GlobalGraphOperations.at(graphDb);

    Transaction tx = graphDb.beginTx();
    ResourceIterable<Node> allNodes = globalGraphOperations.getAllNodes();
    int size = Iterators.size(allNodes.iterator());
    tx.success();

    logger.info(size + " nodes left to process");

    for (Node baseNode : allNodes) {

      size -= 1;
      if (size % 10 == 0) {
        // Committing the batch
        tx.success();
        tx.close();
        tx = graphDb.beginTx();
      }

      if (size % 100000 == 0) {
        logger.info(size + " nodes left to process");
      }

      logger.fine("Processing Node - " + baseNode.getProperty(NodeProperties.IRI));

      // No equivalent, defacto CliqueLeader
      if (!baseNode.hasRelationship(IS_EQUIVALENT) && !baseNode.hasRelationship(SAME_AS)) {
        markAsCliqueLeader(baseNode);
      } else {
        // Keep a list of equivalentNodes
        List<Node> clique = new ArrayList<Node>();

        // Move all the edges except the equivalences
        for (Node currentNode : graphDb.traversalDescription().relationships(IS_EQUIVALENT, Direction.BOTH).relationships(SAME_AS, Direction.BOTH)
            .uniqueness(Uniqueness.NODE_GLOBAL).traverse(baseNode).nodes()) {
          clique.add(currentNode); // this will include the baseNode
        }

        if (!hasLeader(clique)) {
          Node leader = electCliqueLeader(clique, prefixLeaderPriority);
          markAsCliqueLeader(leader);
          clique.remove(leader); // keep only the peasants
          markLeaderEdges(leader);
          moveEdgesToLeader(leader, clique);
          ensureLabel(leader, clique);
        }

      }

    }

    tx.success();
  }

  // TODO that's hacky
  private void ensureLabel(Node leader, List<Node> clique) {
    // Move rdfs:label if non-existing on leader
    if (!leader.hasProperty(NodeProperties.LABEL)) {
      for (Node n : clique) {
        if (n.hasProperty(NodeProperties.LABEL) && n.hasProperty("http://www.w3.org/2000/01/rdf-schema#label")) {
          leader.setProperty(NodeProperties.LABEL, n.getProperty(NodeProperties.LABEL));
          leader.setProperty("http://www.w3.org/2000/01/rdf-schema#label", n.getProperty("http://www.w3.org/2000/01/rdf-schema#label"));
          return;
        }
      }
    }
  }

  private void moveEdgesToLeader(Node leader, List<Node> clique) {
    for (Node n : clique) {
      logger.fine("Processing underNode - " + n.getProperty(NodeProperties.IRI));
      Iterable<Relationship> rels = n.getRelationships();
      for (Relationship rel : rels) {
        if ((rel.isType(IS_EQUIVALENT) || rel.isType(SAME_AS))
            && (rel.getStartNode().getId() == leader.getId() || rel.getEndNode().getId() == leader.getId())) {
          logger.fine("equivalence relation which is already attached to the leader, do nothing");
        } else {
          if ((rel.getEndNode().getId() == n.getId())) {
            logger.fine("MOVE TARGET " + rel.getType() + " FROM " + n.getProperty(NodeProperties.IRI) + " TO "
                + leader.getProperty(NodeProperties.IRI));

            moveRelationship(n, leader, rel, ORIGINAL_REFERENCE_KEY_TARGET);
          } else if ((rel.getStartNode().getId() == n.getId())) {
            logger.fine("MOVE SOURCE " + rel.getType() + " FROM " + n.getProperty(NodeProperties.IRI) + " TO "
                + leader.getProperty(NodeProperties.IRI));

            moveRelationship(n, leader, rel, ORIGINAL_REFERENCE_KEY_SOURCE);
          }
        }
      }
    }
  }

  private void markLeaderEdges(Node leader) {
    for (Relationship r : leader.getRelationships()) {
      if (r.getStartNode().getId() == leader.getId()) {
        r.setProperty(ORIGINAL_REFERENCE_KEY_SOURCE, CLIQUE_LEADER_PROPERTY);
      } else {
        r.setProperty(ORIGINAL_REFERENCE_KEY_TARGET, CLIQUE_LEADER_PROPERTY);
      }
    }
  }

  private boolean hasLeader(List<Node> clique) {
    for (Node n : clique) {
      if (n.hasLabel(CLIQUE_LEADER_LABEL)) {
        return true;
      }
    }
    return false;
  }

  private void markAsCliqueLeader(Node n) {
    if (!n.hasLabel(CLIQUE_LEADER_LABEL)) {
      n.addLabel(CLIQUE_LEADER_LABEL);
    }
  }

  public Node electCliqueLeader(List<Node> clique, List<String> prefixLeaderPriority) {
    List<Node> designatedLeaders = designatedLeader(clique);
    if (designatedLeaders.size() > 1) {
      logger.severe("More than one node in a clique designated as leader. Using failover strategy to elect a leader.");
      for (Node n : designatedLeaders) {
        logger.severe(n.getProperty(NodeProperties.IRI).toString());
      }
      return filterByIri(designatedLeaders, prefixLeaderPriority);
    } else if (designatedLeaders.size() == 1) {
      return designatedLeaders.get(0);
    } else {
      return filterByIri(clique, prefixLeaderPriority);
    }
  }

  private List<Node> designatedLeader(List<Node> clique) {
    List<Node> designatedNodes = new ArrayList<Node>();
    for (Node n : clique) {
      for (String k : n.getPropertyKeys()) {
        if (leaderAnnotationProperty.equals(k)) {
          designatedNodes.add(n);
        }
      }
    }
    return designatedNodes;
  }

  private Node filterByIri(List<Node> clique, List<String> leaderPriorityIri) {
    List<Node> filteredByIriNodes = new ArrayList<Node>();
    if (!leaderPriorityIri.isEmpty()) {
      String iriPriority = leaderPriorityIri.get(0);
      for (Node n : clique) {
        Optional<String> iri = GraphUtil.getProperty(n, NodeProperties.IRI, String.class);
        if (iri.isPresent() && iri.get().contains(iriPriority)) {
          filteredByIriNodes.add(n);
        }
      }
      if (filteredByIriNodes.isEmpty()) {
        filterByIri(clique, leaderPriorityIri.subList(1, leaderPriorityIri.size()));
      }
    }

    if (filteredByIriNodes.isEmpty()) {
      filteredByIriNodes = clique;
    }
    Collections.sort(filteredByIriNodes, new Comparator<Node>() {
      @Override
      public int compare(Node node1, Node node2) {
        Optional<String> iri1 = GraphUtil.getProperty(node1, NodeProperties.IRI, String.class);
        Optional<String> iri2 = GraphUtil.getProperty(node2, NodeProperties.IRI, String.class);
        return iri1.get().compareTo(iri2.get());
      }
    });

    // Anonymous nodes should be avoided as cliqueLeaders
    List<Node> filteredByIriNodesWithoutAnonymousNodes = new ArrayList<Node>();
    for (Node n : filteredByIriNodes) {
      if (!n.hasLabel(OwlLabels.OWL_ANONYMOUS)) {
        filteredByIriNodesWithoutAnonymousNodes.add(n);
      }
    }

    if (filteredByIriNodesWithoutAnonymousNodes.isEmpty()) {
      return filteredByIriNodes.get(0);
    } else {
      return filteredByIriNodesWithoutAnonymousNodes.get(0);
    }


  }

  private void moveRelationship(Node from, Node to, Relationship rel, String property) {
    Relationship newRel = null;
    if (property == ORIGINAL_REFERENCE_KEY_TARGET) {
      newRel = rel.getOtherNode(from).createRelationshipTo(to, rel.getType());
    } else {
      newRel = to.createRelationshipTo(rel.getOtherNode(from), rel.getType());
    }
    copyProperties(rel, newRel);
    rel.delete();
    newRel.setProperty(property, from.getProperty(NodeProperties.IRI));
  }

  private boolean targetHasAlreadyMoved(Relationship rel) {
    return rel.getProperty(ORIGINAL_REFERENCE_KEY_TARGET, null) != null;
  }

  private boolean sourceHasAlreadyMoved(Relationship rel) {
    return rel.getProperty(ORIGINAL_REFERENCE_KEY_SOURCE, null) != null;
  }

  private void copyProperties(PropertyContainer source, PropertyContainer target) {
    for (String key : source.getPropertyKeys())
      target.setProperty(key, source.getProperty(key));
  }
}