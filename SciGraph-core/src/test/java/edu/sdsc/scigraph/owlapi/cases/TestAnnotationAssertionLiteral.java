package edu.sdsc.scigraph.owlapi.cases;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;
import org.neo4j.graphdb.Node;

/***
 * See https://github.com/SciCrunch/SciGraph/wiki/MappingToOWL#subclassof-axioms
 */
public class TestAnnotationAssertionLiteral extends OwlTestCase {

  @Test
  public void testAnnotationAssertion() {
    Node i = getNode("http://example.org/i");
    assertThat("property value is set to foo", i.getProperty("http://example.org/p").toString(),
        is("foo"));
  }

}